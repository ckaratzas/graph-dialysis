#!/usr/bin/env python3
"""Merges a BenchmarkRunner CSV (dialysis.benchmark.BenchmarkRunner) with a ground_truth.py CSV,
joined on the `instance` column, filling in `true_orbits`/`gt_source` and recomputing `status`.

Usage:
    python3 scripts/merge_ground_truth.py --benchmark=results/d3-1000v.csv --groundTruth=results/gt-d3.csv --out=results/d3-1000v-merged.csv

Status (only recomputed for rows whose ground truth we actually have; rows with no matching
ground-truth row, or where ground truth itself timed out, keep their original status unchanged):
    unknown >= 1                         -> PARTIAL
    unknown == 0 AND recovered == true    -> EXACT
    unknown == 0 AND recovered != true    -> PARTIAL  (certified but disagrees with ground truth --
                                                        this is a correctness bug, never silently EXACT)
"""
import argparse
import csv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--benchmark", required=True)
    ap.add_argument("--groundTruth", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    with open(args.groundTruth) as f:
        gt_rows = {row["instance"]: row for row in csv.DictReader(f)}

    with open(args.benchmark) as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames
        rows = list(reader)

    matched = 0
    disagreements = 0
    for row in rows:
        gt = gt_rows.get(row["instance"])
        if gt is None or gt["gt_timed_out"] != "false" or not gt["true_orbits"]:
            continue
        true_orbits = int(gt["true_orbits"])
        row["true_orbits"] = true_orbits
        row["gt_source"] = "traces"
        matched += 1
        if row.get("unknown") and row["unknown"] not in ("", "0"):
            row["status"] = "PARTIAL"
            continue
        if row.get("recovered_orbits") and row["recovered_orbits"].isdigit():
            recovered = int(row["recovered_orbits"])
            if recovered == true_orbits:
                row["status"] = "EXACT"
            else:
                row["status"] = "PARTIAL"
                disagreements += 1
                print(f"DISAGREEMENT: {row['instance']} recovered={recovered} true_orbits={true_orbits}")

    with open(args.out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Merged {matched}/{len(rows)} row(s) with ground truth ({disagreements} disagreement(s)). Written to {args.out}")


if __name__ == "__main__":
    main()