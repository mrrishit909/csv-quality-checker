package dq;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** The project's check: plain asserts, no framework. Run with ./build.sh (exits 1 if anything fails). */
public final class Tests {
    private static int passed, failed;

    static void check(boolean ok, String what) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); }
    }

    static List<List<String>> parse(String text) throws IOException {
        List<List<String>> out = new ArrayList<>();
        try (Csv csv = new Csv(new StringReader(text), ',')) {
            for (List<String> r; (r = csv.next()) != null; ) out.add(r);
        }
        return out;
    }

    static void csvTests() throws IOException {
        check(parse("a,b,c\n1,2,3\n").equals(List.of(List.of("a", "b", "c"), List.of("1", "2", "3"))), "simple rows");
        check(parse("a,b\r\n1,2\r\n").get(1).equals(List.of("1", "2")), "CRLF line endings");
        check(parse("x,\"hello, world\",y\n").get(0).get(1).equals("hello, world"), "separator inside quotes");
        check(parse("\"she said \"\"hi\"\"\"\n").get(0).get(0).equals("she said \"hi\""), "doubled quotes become one");
        check(parse("\"line1\nline2\",z\n").get(0).equals(List.of("line1\nline2", "z")), "line break inside quotes");
        check(parse("a,,c,\n").get(0).equals(List.of("a", "", "c", "")), "empty fields, including a trailing one");
        check(parse("a,b").get(0).equals(List.of("a", "b")), "last line without a newline");
        check(parse("").isEmpty(), "empty input has no records");
        boolean threw = false;
        try { parse("\"never closed\n"); } catch (IOException e) { threw = true; }
        check(threw, "unterminated quote is an error, not silent data loss");
    }

    static ColumnProfile profile(String... values) {
        ColumnProfile c = new ColumnProfile("x");
        for (String v : values) c.add(v);
        return c;
    }

    static void profileTests() {
        check(profile("1", "2", "3").type() == ColumnProfile.Type.INTEGER, "integers");
        check(profile("1.5", "2", "-3e2").type() == ColumnProfile.Type.DECIMAL, "decimals, including 2 and -3e2");
        check(profile("2026-01-02", "2025-12-31").type() == ColumnProfile.Type.DATE, "ISO dates");
        check(profile("2026-07-12 22:05:11.820").type() == ColumnProfile.Type.DATETIME, "date-times with milliseconds");
        check(profile("yes", "No", "TRUE").type() == ColumnProfile.Type.BOOLEAN, "booleans");
        check(profile("Tampa", "Miami").type() == ColumnProfile.Type.TEXT, "text");
        ColumnProfile e = profile("1", "", "NA", " null ", "2");
        check(e.empty == 3 && e.nonEmpty() == 2, "'', NA and null all count as empty");
        String[] vals = new String[100];
        for (int i = 0; i < 99; i++) vals[i] = String.valueOf(i);
        vals[99] = "oops";
        ColumnProfile v = profile(vals);
        check(v.type() == ColumnProfile.Type.INTEGER && v.typeViolations() == 1 && v.violationExamples().equals(java.util.List.of("oops")),
              "99 integers + 1 stray value: still INTEGER, 1 violation, example kept");
        ColumnProfile half = profile("1", "2", "a", "b");
        check(half.type() == ColumnProfile.Type.TEXT && half.typeViolations() == 0, "50/50 mix falls back to TEXT");
        ColumnProfile s = profile("2", "4", "4", "4", "5", "5", "7", "9");
        check(Math.abs(s.mean - 5) < 1e-9 && Math.abs(s.stdDev() - 2.138) < 1e-3 && s.min == 2 && s.max == 9, "mean, sample std dev, min, max");
        check(s.distinctCount() == 5, "distinct count");
        check(Math.abs(profile("1", "2", "3", "4", "5").quantile(0.25) - 2) < 1e-9, "quartile by linear interpolation");
        LongSet set = new LongSet();
        for (int i = 0; i < 100_000; i++) set.add(LongSet.hash("id" + i));
        for (int i = 0; i < 100_000; i++) set.add(LongSet.hash("id" + i));
        check(set.size() == 100_000, "LongSet keeps 100,000 distinct hashes through resizing and repeats");
    }

    /** Whole-file checks on a generated CSV whose problems we know exactly. */
    static void profilerTests() throws IOException {
        StringBuilder b = new StringBuilder("﻿id,price,city\n");
        for (int i = 1; i <= 200; i++) b.append(i).append(',').append(10 + i % 5).append(",Tampa\n");
        b.append("201,1000,Tampa\n");            // 1 far-out price
        b.append("202,oops,Tampa\n");            // 1 non-number in a number column
        b.append("5,10,Tampa\n");                // exact duplicate of row 5
        b.append("203,12\n");                    // too few fields
        b.append("\n");                          // blank line: ignored
        java.nio.file.Path f = java.nio.file.Files.createTempFile("dq", ".csv");
        java.nio.file.Files.writeString(f, b);
        Profiler p = Profiler.run(f, ',');
        java.nio.file.Files.delete(f);
        check(p.columns.get(0).name.equals("id"), "byte-order mark stripped from the first header");
        check(p.rows == 204 && p.malformed == 1 && p.malformedLines.equals(List.of(205L)), "row count, 1 malformed row at line 205");
        check(p.duplicates == 1, "1 exact duplicate row");
        ColumnProfile price = p.columns.get(1);
        check(price.type() == ColumnProfile.Type.INTEGER && price.typeViolations() == 1, "price stays INTEGER with 1 violation");
        check(p.outliers[1] == 1, "the 1000 price is the only outlier");
        check(p.columns.get(2).distinctCount() == 1, "constant column");
        List<String> msgs = p.issues().stream().map(i -> i.level() + " " + i.column() + " " + i.message()).toList();
        check(msgs.get(0).startsWith("error"), "errors sort first");
        check(msgs.stream().anyMatch(m -> m.contains("city every value is the same")), "constant column reported");
        check(msgs.stream().noneMatch(m -> m.contains("likely ID")), "id has a duplicate, so it is not called a key");
        check(Report.json(p).contains("\"duplicate_rows\": 1") && Report.html(p).contains("1 duplicate rows"), "JSON and HTML reports carry the numbers");
    }

    public static void main(String[] args) throws Exception {
        csvTests();
        profileTests();
        profilerTests();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
