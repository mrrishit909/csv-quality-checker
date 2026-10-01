"""Independent re-count in plain Python (csv module, exact sets) of what dq.jar reported.

    python3 check.py data.csv reports/data.json

Fails (exit 1) if rows, malformed rows, duplicate rows, or any column's empty count,
distinct count, min, max or mean disagree. Also prints how far the outlier counts
(whose quartiles Java takes from a 100,000-value sample) are from exact ones.
"""
import csv, json, math, statistics, sys

NULLS = {"", "na", "n/a", "null", "none", "nan"}
NUMERIC = {"integer", "decimal"}

def is_null(v):
    return v.strip().lower() in NULLS

def main(data, report):
    rep = json.load(open(report))
    cols = rep["columns"]
    n = len(cols)
    rows = malformed = dups = 0
    seen = set()
    empty = [0] * n
    distinct = [set() for _ in range(n)]
    nums = [[] for _ in range(n)]
    lo, hi = [None] * n, [None] * n
    with open(data, newline="", encoding="utf-8-sig") as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            if not row:
                continue                      # blank line
            rows += 1
            if len(row) != n:
                malformed += 1
                continue
            t = tuple(row)
            if t in seen:
                dups += 1
            seen.add(t)
            for i, v in enumerate(row):
                if is_null(v):
                    empty[i] += 1
                    continue
                v = v.strip()
                distinct[i].add(v)
                if cols[i]["type"] in NUMERIC:
                    try:
                        nums[i].append(float(v))
                    except ValueError:
                        pass
                else:
                    lo[i] = v if lo[i] is None or v < lo[i] else lo[i]
                    hi[i] = v if hi[i] is None or v > hi[i] else hi[i]

    bad = []
    def same(what, java, py):
        ok = java == py if not isinstance(py, float) else (java is not None and math.isclose(java, py, rel_tol=1e-9, abs_tol=1e-9))
        if not ok:
            bad.append(f"{what}: java {java} vs python {py}")
    same("rows", rep["rows"], rows)
    same("malformed rows", rep["malformed_rows"], malformed)
    same("duplicate rows", rep["duplicate_rows"], dups)
    print(f"{rep['file']}: {rows:,} rows, {malformed:,} malformed, {dups:,} duplicates")
    print(f"  {'column':<20} {'outliers java':>14} {'exact':>9}")
    for i, c in enumerate(cols):
        name = c["name"]
        same(f"{name} empty", c["empty"], empty[i])
        same(f"{name} distinct", c["distinct"], len(distinct[i]))
        if c["type"] in NUMERIC and nums[i]:
            same(f"{name} min", c["min"], float(min(nums[i])))
            same(f"{name} max", c["max"], float(max(nums[i])))
            same(f"{name} mean", c["mean"], math.fsum(nums[i]) / len(nums[i]))
            if len(nums[i]) >= 8:
                q1, _, q3 = statistics.quantiles(nums[i], n=4, method="inclusive")
                iqr = q3 - q1
                exact = sum(x < q1 - 1.5 * iqr or x > q3 + 1.5 * iqr for x in nums[i]) if iqr > 0 else 0
                print(f"  {name:<20} {c['outliers']:>14,} {exact:>9,}")
        elif c["type"] not in NUMERIC:
            same(f"{name} min", c["min"], lo[i])
            same(f"{name} max", c["max"], hi[i])
    if bad:
        print("MISMATCH\n  " + "\n  ".join(bad))
        sys.exit(1)
    print(f"  all {3 + 5 * n} comparable numbers agree (rows, malformed, duplicates; per column empty, distinct, min, max, mean)")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
