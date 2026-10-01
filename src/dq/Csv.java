package dq;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * A streaming RFC 4180 CSV reader: one record at a time, so a file of any size fits in memory.
 * Handles quoted fields, doubled quotes ("") inside them, separators and line breaks inside quotes, and \n or \r\n endings.
 */
public final class Csv implements Closeable {
    private final Reader in;
    private final char sep;
    private int peeked = -2;          // -2 = nothing peeked yet
    private long line = 1;

    public Csv(Reader in, char sep) {
        this.in = in instanceof BufferedReader ? in : new BufferedReader(in, 1 << 16);
        this.sep = sep;
    }

    private int read() throws IOException {
        if (peeked != -2) { int c = peeked; peeked = -2; return c; }
        return in.read();
    }

    private int peek() throws IOException {
        if (peeked == -2) peeked = in.read();
        return peeked;
    }

    public long line() { return line; }

    /** The next record, or null at the end of the input. */
    public List<String> next() throws IOException {
        int c = read();
        if (c == -1) return null;
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false, startedQuoted = false;
        while (true) {
            if (inQuotes) {
                if (c == -1) throw new IOException("unterminated quoted field starting before line " + line);
                if (c == '"') {
                    if (peek() == '"') { read(); field.append('"'); }   // "" inside quotes is one literal quote
                    else inQuotes = false;
                } else {
                    if (c == '\n') line++;
                    field.append((char) c);
                }
            } else if (c == '"' && field.length() == 0 && !startedQuoted) {
                inQuotes = true; startedQuoted = true;
            } else if (c == sep) {
                record.add(field.toString()); field.setLength(0); startedQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && peek() == '\n') read();
                line++;
                break;
            } else if (c == -1) {
                break;
            } else {
                field.append((char) c);
            }
            c = read();
        }
        record.add(field.toString());
        return record;
    }

    @Override
    public void close() throws IOException { in.close(); }
}
