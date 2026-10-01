# CSV data-quality checker (Java)

Point it at any CSV and get a one-page report of what's wrong with the data before you analyse it.
It reports empty values, values that don't match the column's type, rows with the wrong number of fields, exact
duplicate rows, likely ID columns, constant columns and unusual numbers. Java 21, standard library only,
no dependencies. It streams the file, so memory stays small however many rows there are.

```
./build.sh                                       # compile, run the 32 tests, build dq.jar
java -jar dq.jar data.csv                        # -> reports/data.html + reports/data.json
java -jar dq.jar data.csv --sep ';' --out out    # other separator / folder (--sep '\t' for tabs)
```
Exit code: `0` clean, `1` warnings, `2` errors (malformed rows or an all-empty column), `64` bad arguments.
A data pipeline can stop only on errors with `java -jar dq.jar new.csv; [ $? -lt 2 ] || exit 1`
(warnings such as empty values are often expected; see the results below).

Example output: [samples/reports/](samples/reports/) (real data, below) and [reports/messy.html](reports/messy.html)
(an 11-row file with planted problems, [samples/messy.csv](samples/messy.csv)).

## Steps

| Step | Files | What it does |
|---|---|---|
| 1 | `src/dq/Csv.java` | streaming RFC 4180 reader: quoted fields, `""` escapes, separators and line breaks inside quotes, CRLF; an unclosed quote is an error, not silently lost rows |
| 2 | `ColumnProfile.java`, `LongSet.java` | one pass per value: empties, type inference, distinct count, range, mean and std dev, quartiles |
| 3 | `Profiler.java`, `Report.java`, `Main.java` | whole-file checks (field count, duplicates, outliers in a second pass), plain-language findings, JSON + HTML, CLI |
| 4 | `check.py`, `samples/reports/` | run on two real files and recount everything in plain Python |

## How it works

- **Empty** means blank or one of `NA`, `N/A`, `null`, `none`, `NaN` (any case, spaces ignored).
- **Type**: each value is tested against integer, decimal, date-time, date (`2026-08-31` or `8/31/2026`) and
  boolean patterns. A column gets the most specific type that at least 98% of its non-empty values fit; the rest
  are reported as *type misfits* with examples. A 50/50 mix is just text.
- **Distinct values and duplicate rows** are counted with 64-bit FNV-1a hashes in an open-addressing `long[]` set
  (`LongSet`): 8 bytes per value instead of a Java `String` each. Two different values sharing a hash is possible
  but unlikely: about 1 in 50 million for an 868,000-row file.
- **Mean and standard deviation**: Welford's one-pass method, which stays accurate on long columns.
- **Quartiles** come from a reservoir sample of 100,000 numbers per column. They are exact up to 100,000 numbers
  and approximate above that.
- **Outliers**: a second pass counts numbers outside Tukey's fences (1.5 × the interquartile range beyond the
  quartiles). They are listed as *info*, not as errors: in skewed data such as delays, or in coordinates, many of
  them are perfectly real.

## Results on real data (Apple M3 Pro, Java 21)

**Divvy bike trips, August 2026** (868,191 rows, 13 columns, about 7 s; [report](samples/reports/202608-divvy-tripdata.html))
- 0 malformed rows, 0 duplicates; `ride_id` is unique on every row: a key column.
- About 22% of rides have no start or end station (189,327 and 197,920). Every ride with no start station is an e-bike, left outside a dock.
  Any station analysis has to say it covers only docked rides.
- 683 rides have no end coordinates.
- `started_at` begins at 2026-07-30 23:01. Rides that started in July sit in the August file, so a monthly analysis
  must filter on the start date. The SQL project ([How Chicago Rides](https://mrrishit909.github.io/projects/chicago-bike-share/))
  needed exactly that cleaning rule.
- The latitude/longitude "outliers" are just rides outside the central area. This is the case where the IQR rule isn't meaningful.

**Florida flights, Aug 2025 – Jul 2026** (the fact table from [Florida Flight Delays](https://mrrishit909.github.io/projects/florida-flight-delays/);
635,459 rows, 17 columns, under 3 s; [report](samples/reports/FactFlights.html))
- 13 rows repeat an earlier row exactly. I looked one up in the raw BTS file: the two "copies" of AA MIA→DFW on
  2025-10-14 at hour 18 are flights 2524 (18:49) and 2823 (18:05). They are different flights that look identical
  because the table keeps only the departure hour. The tool now says to check exactly this.
- `CancelCode` is 98.2% empty, which is expected: 11,371 flights were cancelled and exactly 11,371 have a code.
- The five delay-cause columns are 76.0% empty. BTS fills them only for flights arriving 15+ minutes late:
  152,476 such flights, 152,475 with causes. So one late flight has no cause breakdown.

An empty-value warning is only a question; the answer comes from knowing what the data is.

## Check

`./build.sh` runs 32 tests: CSV edge cases, type inference, statistics, and a generated file with known problems.
`check.py` recounts a report independently in Python, using the `csv` module and exact sets:

```
python3 check.py samples/data/202608-divvy-tripdata.csv samples/reports/202608-divvy-tripdata.json
```
On both real files, every row, malformed-row and duplicate count agreed, and so did each column's empty count,
distinct count, min and max, plus the mean of every number column (59 + 84 comparisons). Outlier counts were exact in 15 of the 17 columns with more than
100,000 numbers. In the other two they were off by 1.6% (`start_lat`) and 2.1% (`DepDelay`): the sampled quartile
landed one step from the exact one (`DepDelay` Q1 −6 vs −7 minutes), and many tied values crossed the fence.

## Data

- Divvy trip data, https://divvybikes.com/system-data (Lyft Bikes and Scooters, LLC, Divvy Data License Agreement).
  `samples/data/` is not committed. Unzip `202608-divvy-tripdata.zip` from there into it.
- BTS On-Time Performance, https://www.transtats.bts.gov/ (US DOT), via `~/florida-flight-delays/etl.py`.

## Not done

- No rules file: it can't be told "this column must be ≥ 0" or "these values only". It infers; it doesn't validate a schema.
- UTF-8 only; no encoding detection. One thread.
- Dates are recognised in ISO and US `m/d/yyyy` forms only. It doesn't check that a date is real (e.g. 2026-02-31).
- Quartiles, and so outlier counts, are approximate above 100,000 numbers per column (see Check).
