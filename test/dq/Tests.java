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

    public static void main(String[] args) throws Exception {
        csvTests();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
