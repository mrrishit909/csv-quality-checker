package dq;

import java.util.List;
import java.util.Locale;

/** Turns a Profiler into JSON (for programs) and a one-page HTML report (for people). */
public final class Report {
    private Report() {}

    static String num(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return "";
        if (x == Math.rint(x) && Math.abs(x) < 1e15) return String.format(Locale.US, "%,d", (long) x);
        return String.format(Locale.US, "%,.4f", x).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static String h(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jnum(double x) {
        if (!Double.isFinite(x)) return "null";
        return x == Math.rint(x) && Math.abs(x) < 1e15 ? Long.toString((long) x) : Double.toString(x);
    }

    public static String json(Profiler p) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"file\": ").append(js(p.file)).append(",\n  \"rows\": ").append(p.rows)
         .append(",\n  \"malformed_rows\": ").append(p.malformed).append(",\n  \"duplicate_rows\": ").append(p.duplicates)
         .append(",\n  \"seconds\": ").append(String.format(Locale.US, "%.2f", p.seconds)).append(",\n  \"columns\": [");
        for (int i = 0; i < p.columns.size(); i++) {
            ColumnProfile c = p.columns.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"name\": ").append(js(c.name))
             .append(", \"type\": ").append(js(c.type().name().toLowerCase()))
             .append(", \"empty\": ").append(c.empty).append(", \"distinct\": ").append(c.distinctCount())
             .append(", \"type_violations\": ").append(c.typeViolations())
             .append(", \"min\": ").append(c.numeric() ? jnum(c.min) : (c.minText == null ? "null" : js(c.minText)))
             .append(", \"max\": ").append(c.numeric() ? jnum(c.max) : (c.maxText == null ? "null" : js(c.maxText)))
             .append(", \"mean\": ").append(c.numeric() ? jnum(c.mean) : "null")
             .append(", \"outliers\": ").append(p.outliers[i]).append("}");
        }
        b.append("\n  ],\n  \"issues\": [");
        List<Profiler.Issue> issues = p.issues();
        for (int i = 0; i < issues.size(); i++) {
            Profiler.Issue s = issues.get(i);
            b.append(i == 0 ? "\n" : ",\n").append("    {\"level\": ").append(js(s.level())).append(", \"column\": ")
             .append(js(s.column())).append(", \"message\": ").append(js(s.message())).append("}");
        }
        return b.append("\n  ]\n}\n").toString();
    }

    public static String html(Profiler p) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < p.columns.size(); i++) {
            ColumnProfile c = p.columns.get(i);
            double pct = c.rows == 0 ? 0 : 100.0 * c.empty / c.rows;
            String range = c.numeric() ? num(c.min) + " – " + num(c.max)
                    : (c.minText == null ? "" : (c.type() == ColumnProfile.Type.TEXT ? c.minLen + "–" + c.maxLen + " chars" : c.minText + " – " + c.maxText));
            rows.append("<tr><td>").append(h(c.name)).append("</td><td>").append(c.type().name().toLowerCase())
                .append("</td><td class=n>").append(String.format(Locale.US, "%,d", c.empty)).append("</td><td class=n>")
                .append(String.format(Locale.US, "%.1f%%", pct)).append("</td><td class=bar><i style=\"width:").append(String.format(Locale.US, "%.1f", pct))
                .append("%\"></i></td><td class=n>").append(String.format(Locale.US, "%,d", c.distinctCount()))
                .append("</td><td class=n>").append(String.format(Locale.US, "%,d", c.typeViolations())).append("</td><td>")
                .append(h(range)).append("</td><td class=n>").append(c.numeric() ? num(c.mean) : "").append("</td><td class=n>")
                .append(p.outliers[i] > 0 ? String.format(Locale.US, "%,d", p.outliers[i]) : "").append("</td></tr>\n");
        }
        StringBuilder iss = new StringBuilder();
        for (Profiler.Issue s : p.issues())
            iss.append("<li class=").append(s.level()).append("><b>").append(s.level()).append("</b>")
               .append(s.column().isEmpty() ? "" : "<span>" + h(s.column()) + "</span>").append(h(s.message())).append("</li>\n");
        return TEMPLATE.replace("{{FILE}}", h(p.file))
                .replace("{{SUMMARY}}", String.format(Locale.US, "%,d rows · %d columns · %,d malformed · %,d duplicate rows · checked in %.1f s",
                        p.rows, p.columns.size(), p.malformed, p.duplicates, p.seconds))
                .replace("{{ISSUES}}", iss.toString()).replace("{{ROWS}}", rows.toString());
    }

    private static final String TEMPLATE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Data quality: {{FILE}}</title>
            <link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500&family=Geist+Mono&display=swap">
            <style>
            :root{--bg:#000;--card:#0b0b0b;--fg:#f2f2f0;--dim:#8a8a87;--line:#1d1d1d;--err:#e66767;--warn:#c98500;--info:#3987e5}
            *{box-sizing:border-box}html{background:var(--bg);color-scheme:dark}
            body{margin:0;background:var(--bg);color:var(--fg);font-family:Geist,"Helvetica Neue",Arial,sans-serif;font-size:15px;line-height:1.5;padding:32px 40px 60px}
            main{max-width:1180px;margin:0 auto}.cap{font-size:12px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;color:var(--dim)}
            h1{margin:8px 0 6px;font-size:clamp(30px,5vw,56px);font-weight:500;letter-spacing:-.035em;line-height:1.05;word-break:break-all}
            h2{margin:34px 0 10px;font-size:20px;font-weight:500}
            ul{list-style:none;margin:0;padding:0;display:grid;gap:6px}
            li{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:9px 12px;display:flex;gap:10px;flex-wrap:wrap;align-items:baseline}
            li b{font-size:11px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;min-width:62px}
            li.error b{color:var(--err)}li.warning b{color:var(--warn)}li.info b{color:var(--info)}
            li span{font-family:"Geist Mono",ui-monospace,monospace;font-size:13px;color:var(--fg)}
            .tw{overflow-x:auto}table{border-collapse:collapse;font-size:13px;min-width:100%}
            th,td{padding:7px 12px 7px 0;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
            th{font-size:11px;font-weight:500;letter-spacing:.06em;text-transform:uppercase;color:var(--dim)}
            td:first-child{font-family:"Geist Mono",ui-monospace,monospace}.n{text-align:right;font-variant-numeric:tabular-nums}
            td.bar{width:110px}td.bar i{display:block;height:6px;border-radius:3px;background:var(--info);min-width:1px}
            @media (max-width:640px){body{padding:20px}}
            </style></head><body><main>
            <p class="cap">CSV data-quality report</p><h1>{{FILE}}</h1><p class="cap">{{SUMMARY}}</p>
            <h2>Findings</h2><ul>
            {{ISSUES}}</ul>
            <h2>Columns</h2><div class="tw"><table><thead><tr><th>Column</th><th>Type</th><th class=n>Empty</th><th class=n>Empty %</th><th></th><th class=n>Distinct</th><th class=n>Type misfits</th><th>Range</th><th class=n>Mean</th><th class=n>Outliers</th></tr></thead><tbody>
            {{ROWS}}</tbody></table></div>
            <p class="cap" style="margin-top:28px">Generated by dq.jar · Java 21, standard library only</p>
            </main></body></html>
            """;
}
