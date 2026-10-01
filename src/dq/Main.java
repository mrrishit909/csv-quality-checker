package dq;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * java -jar dq.jar data.csv [--sep ';'] [--out reports]
 * Writes reports/<name>.html and reports/<name>.json and prints the findings.
 * Exit code 0 = clean, 1 = warnings, 2 = errors (so it can gate a data pipeline), 64 = bad arguments.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.println("usage: java -jar dq.jar data.csv [--sep ,] [--out reports]");
            System.exit(64);
        }
        Path file = Path.of(args[0]), out = Path.of("reports");
        char sep = ',';
        for (int i = 1; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--sep" -> sep = args[i + 1].equals("\\t") ? '\t' : args[i + 1].charAt(0);
                case "--out" -> out = Path.of(args[i + 1]);
                default -> { System.err.println("unknown option " + args[i]); System.exit(64); }
            }
        }
        Profiler p = Profiler.run(file, sep);
        Files.createDirectories(out);
        String base = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Files.writeString(out.resolve(base + ".json"), Report.json(p));
        Files.writeString(out.resolve(base + ".html"), Report.html(p));
        System.out.printf("%s: %,d rows, %d columns, %.1f s%n", p.file, p.rows, p.columns.size(), p.seconds);
        int worst = 0;
        for (Profiler.Issue s : p.issues()) {
            System.out.printf("  %-7s %-24s %s%n", s.level(), s.column(), s.message());
            worst = Math.max(worst, s.level().equals("error") ? 2 : s.level().equals("warning") ? 1 : 0);
        }
        System.out.println("  -> " + out.resolve(base + ".html"));
        System.exit(worst);
    }
}
