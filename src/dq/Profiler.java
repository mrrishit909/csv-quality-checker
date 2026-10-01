package dq;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Profiles a whole CSV: per-column profiles, plus row-level problems (wrong field count, exact duplicates, outliers). */
public final class Profiler {
    public record Issue(String level, String column, String message) {}

    public final String file;
    public final List<ColumnProfile> columns = new ArrayList<>();
    public long rows, malformed, duplicates;
    public final List<Long> malformedLines = new ArrayList<>();     // first few, for the report
    public long[] outliers;                                         // per column, numeric columns only
    public double[][] fences;                                       // per column: {low, high}
    public double seconds;

    private Profiler(String file) { this.file = file; }

    public static Profiler run(Path path, char sep) throws IOException {
        long t0 = System.nanoTime();
        Profiler p = new Profiler(path.getFileName().toString());
        LongSet seenRows = new LongSet();
        try (Csv csv = open(path, sep)) {
            List<String> header = csv.next();
            if (header == null) throw new IOException("empty file");
            header.set(0, header.get(0).replace("﻿", ""));          // byte-order mark some exports add
            for (String h : header) p.columns.add(new ColumnProfile(h.strip()));
            for (List<String> r; (r = csv.next()) != null; ) {
                if (r.size() == 1 && r.get(0).isEmpty()) continue;     // blank line
                p.rows++;
                if (r.size() != header.size()) {
                    p.malformed++;
                    if (p.malformedLines.size() < 5) p.malformedLines.add(csv.line() - 1);
                    continue;
                }
                if (!seenRows.add(LongSet.hash(String.join("\u0001", r)))) p.duplicates++;
                for (int i = 0; i < r.size(); i++) p.columns.get(i).add(r.get(i));
            }
        }
        p.countOutliers(path, sep);
        p.seconds = (System.nanoTime() - t0) / 1e9;
        return p;
    }

    private static Csv open(Path path, char sep) throws IOException {
        Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        return new Csv(r, sep);
    }

    /** Second pass: count numbers outside Tukey's fences (1.5 x IQR beyond the quartiles). */
    private void countOutliers(Path path, char sep) throws IOException {
        int n = columns.size();
        outliers = new long[n];
        fences = new double[n][];
        for (int i = 0; i < n; i++) {
            ColumnProfile c = columns.get(i);
            if (!c.numeric() || c.numbers < 8) continue;
            double q1 = c.quantile(0.25), q3 = c.quantile(0.75), iqr = q3 - q1;
            if (iqr > 0) fences[i] = new double[]{q1 - 1.5 * iqr, q3 + 1.5 * iqr};
        }
        try (Csv csv = open(path, sep)) {
            csv.next();
            for (List<String> r; (r = csv.next()) != null; ) {
                if (r.size() != n) continue;
                for (int i = 0; i < n; i++) {
                    if (fences[i] == null || ColumnProfile.isNull(r.get(i))) continue;
                    String v = r.get(i).strip();
                    try {
                        double x = Double.parseDouble(v);
                        if (x < fences[i][0] || x > fences[i][1]) outliers[i]++;
                    } catch (NumberFormatException ignored) { /* already counted as a type violation */ }
                }
            }
        }
    }

    /** Plain-language findings, worst first. */
    public List<Issue> issues() {
        List<Issue> out = new ArrayList<>();
        if (malformed > 0) out.add(new Issue("error", "", String.format("%,d rows have the wrong number of fields (first at line %d)", malformed, malformedLines.get(0))));
        if (duplicates > 0) out.add(new Issue("warning", "", String.format("%,d rows are exact duplicates of an earlier row", duplicates)));
        for (int i = 0; i < columns.size(); i++) {
            ColumnProfile c = columns.get(i);
            double emptyPct = c.rows == 0 ? 0 : 100.0 * c.empty / c.rows;
            if (c.rows > 0 && c.empty == c.rows) out.add(new Issue("error", c.name, "column is entirely empty"));
            else if (emptyPct >= 5) out.add(new Issue("warning", c.name, String.format("%.1f%% of values are empty (%,d)", emptyPct, c.empty)));
            else if (c.empty > 0) out.add(new Issue("info", c.name, String.format("%,d empty values (%.2f%%)", c.empty, emptyPct)));
            if (c.typeViolations() > 0)
                out.add(new Issue("warning", c.name, String.format("%,d values don't look %s, e.g. %s", c.typeViolations(), c.type().name().toLowerCase(), c.violationExamples())));
            if (c.nonEmpty() > 1 && c.distinctCount() == 1) out.add(new Issue("info", c.name, "every value is the same"));
            if (c.nonEmpty() == rows - malformed && c.distinctCount() == c.nonEmpty() && rows > 1)
                out.add(new Issue("info", c.name, "every value is unique: a likely ID / key column"));
            if (outliers != null && outliers[i] > 0)
                out.add(new Issue("info", c.name, String.format("%,d values outside the usual range [%s, %s]", outliers[i], Report.num(fences[i][0]), Report.num(fences[i][1]))));
        }
        List<String> order = List.of("error", "warning", "info");
        out.sort((a, b) -> order.indexOf(a.level()) - order.indexOf(b.level()));
        return out;
    }
}
