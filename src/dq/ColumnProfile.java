package dq;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/** Everything learned about one column in a single pass: emptiness, type, distinct values, range, spread. */
public final class ColumnProfile {
    public enum Type { INTEGER, DECIMAL, DATETIME, DATE, BOOLEAN, TEXT }

    static final double TYPE_SHARE = 0.98;      // a type is inferred if at least 98% of non-empty values fit it
    static final int SAMPLE = 100_000;           // reservoir sample of numbers, for quartiles
    static final java.util.Set<String> NULLS = java.util.Set.of("", "na", "n/a", "null", "none", "nan");

    private static final Pattern INTEGER = Pattern.compile("[-+]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4}");
    private static final Pattern DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern BOOLEAN = Pattern.compile("(?i)true|false|yes|no");

    public final String name;
    long rows, empty;
    final EnumMap<Type, Long> fits = new EnumMap<>(Type.class);
    final EnumMap<Type, List<String>> misfits = new EnumMap<>(Type.class);   // up to 3 example values that don't fit each type
    final LongSet distinct = new LongSet();
    String minText, maxText;
    int minLen = Integer.MAX_VALUE, maxLen;
    long numbers;
    double mean, m2, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    final double[] sample = new double[SAMPLE];
    int sampled;
    private final Random rng = new Random(42);

    ColumnProfile(String name) {
        this.name = name;
        for (Type t : Type.values()) { fits.put(t, 0L); misfits.put(t, new ArrayList<>()); }
    }

    static boolean isNull(String v) {
        String t = v.strip();
        return t.isEmpty() || NULLS.contains(t.toLowerCase());
    }

    void add(String raw) {
        rows++;
        if (isNull(raw)) { empty++; return; }
        String v = raw.strip();
        distinct.add(LongSet.hash(v));
        if (minText == null || v.compareTo(minText) < 0) minText = v;
        if (maxText == null || v.compareTo(maxText) > 0) maxText = v;
        minLen = Math.min(minLen, v.length()); maxLen = Math.max(maxLen, v.length());
        tally(Type.INTEGER, INTEGER.matcher(v).matches(), v);
        boolean num = DECIMAL.matcher(v).matches();
        tally(Type.DECIMAL, num, v);
        tally(Type.DATETIME, DATETIME.matcher(v).matches(), v);
        tally(Type.DATE, DATE.matcher(v).matches(), v);
        tally(Type.BOOLEAN, BOOLEAN.matcher(v).matches(), v);
        tally(Type.TEXT, true, v);
        if (num) number(Double.parseDouble(v));
    }

    private void tally(Type t, boolean ok, String v) {
        if (ok) fits.merge(t, 1L, Long::sum);
        else if (misfits.get(t).size() < 3) misfits.get(t).add(v);
    }

    private void number(double x) {
        numbers++;
        double d = x - mean;                     // Welford: running mean and variance in one pass, numerically stable
        mean += d / numbers;
        m2 += d * (x - mean);
        min = Math.min(min, x); max = Math.max(max, x);
        if (sampled < SAMPLE) sample[sampled++] = x;     // reservoir sampling keeps a fair sample of any length
        else { long j = (long) (rng.nextDouble() * numbers); if (j < SAMPLE) sample[(int) j] = x; }
    }

    long nonEmpty() { return rows - empty; }

    /** The most specific type that at least 98% of the non-empty values fit. */
    public Type type() {
        long n = nonEmpty();
        if (n == 0) return Type.TEXT;
        for (Type t : new Type[]{Type.INTEGER, Type.DECIMAL, Type.DATETIME, Type.DATE, Type.BOOLEAN})
            if (fits.get(t) >= TYPE_SHARE * n) return t;
        return Type.TEXT;
    }

    /** Non-empty values that don't fit the inferred type (0 for TEXT). */
    public long typeViolations() { return nonEmpty() - fits.get(type()); }

    public List<String> violationExamples() { return misfits.get(type()); }

    public long distinctCount() { return distinct.size(); }

    double stdDev() { return numbers > 1 ? Math.sqrt(m2 / (numbers - 1)) : 0; }

    /** Quartile from the sample (exact when the column has at most 100,000 numbers). */
    double quantile(double q) {
        double[] s = java.util.Arrays.copyOf(sample, sampled);
        java.util.Arrays.sort(s);
        if (s.length == 0) return Double.NaN;
        double pos = q * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    boolean numeric() { return type() == Type.INTEGER || type() == Type.DECIMAL; }
}
