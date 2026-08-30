#!/usr/bin/env python3
"""Merges a BenchmarkRunner CSV (dialysis.benchmark.BenchmarkRunner) with a ground_truth.py CSV,
joined on the `instance` column, filling in `true_orbits`/`gt_source`/`gt_ms`/`gt_timed_out` and
recomputing `status`.

Usage:
    python3 scripts/merge_ground_truth.py --benchmark=results/d3-1000v.csv --groundTruth=results/gt-d3.csv --out=results/d3-1000v-merged.csv

`gt_ms`/`gt_timed_out` are always taken from the ground_truth.py row when one exists for the
instance, whether or not it actually returned a `true_orbits` value -- a timed-out ground-truth run
is itself useful information (how long it ran before being killed), not just a reason to skip.

Status:
    ground truth available, unknown >= 1                  -> PARTIAL
    ground truth available, unknown == 0, recovered==true  -> EXACT
    ground truth available, unknown == 0, recovered!=true  -> PARTIAL  (certified but disagrees with
                                                               ground truth -- a correctness bug,
                                                               never silently EXACT)
    no ground truth available                              -> CERTIFIED if unknown == 0, else PARTIAL
                                                               (recomputed from `unknown` alone,
                                                               never left as whatever placeholder
                                                               the producer originally wrote)
    ground truth row has gt_subdivided == "true"           -> treated as "no ground truth available"
                                                               for the recovered/true comparison --
                                                               true_orbits there counts a different
                                                               graph's orbits than recovered_orbits
                                                               does (see this script's own comment
                                                               at the check). Re-run ground_truth.py
                                                               with --subdivide=never to compare.
"""
import argparse
import csv

# Pre-fix versions of the JUnit campaign sweep wrote gt_ms/gt_timed_out as dead placeholder columns
# (always blank or the literal string "pending"/"PENDING_GT") before this script filled them from
# real ground-truth data -- reset them from any input schema that still has them, so a stale value
# never survives into the merged output disguised as real data.
RESET_BEFORE_MERGE = ("gt_ms", "gt_timed_out")


def heal_status(row):
    """For a row with no usable ground truth match, recompute status from `unknown` alone -- guards
    against a stale placeholder status (e.g. a pre-fix producer's "PENDING_GT") rather than passing
    it through unexamined. Rows with no `unknown` value (PI_ONLY, ERROR) are left untouched."""
    unknown = row.get("unknown")
    if not unknown:
        return
    row["status"] = "CERTIFIED" if unknown == "0" else "PARTIAL"


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
        fieldnames = list(reader.fieldnames)
        rows = list(reader)

    fieldnames = [c for c in fieldnames if c not in RESET_BEFORE_MERGE]
    for row in rows:
        for col in RESET_BEFORE_MERGE:
            row.pop(col, None)

    # true_orbits/gt_source/gt_ms/gt_timed_out are filled in below regardless of whether the input
    # CSV's own header already has them -- append any that are missing rather than assuming the
    # producer's schema matches, or DictWriter rejects the row with "dict contains fields not in
    # fieldnames".
    for col in ("true_orbits", "gt_source", "gt_ms", "gt_timed_out"):
        if col not in fieldnames:
            fieldnames.append(col)

    matched = 0
    disagreements = 0
    not_comparable = 0
    for row in rows:
        gt = gt_rows.get(row["instance"])
        if gt is None:
            heal_status(row)
            continue

        row["gt_ms"] = gt["gt_ms"]
        row["gt_timed_out"] = gt["gt_timed_out"]

        # ground_truth.py's gt_subdivided=true means true_orbits counts orbits over the SUBDIVIDED
        # graph (original vertices + one per edge), which includes edge orbits that
        # recovered_orbits -- always over the original graph's own vertices since
        # FINAL_MEASUREMENTS_SPEC.md Task 1 -- never counts. Comparing them would flag a spurious
        # DISAGREEMENT on a correct result, so this instance's ground truth is treated as unusable
        # for the recovered/true comparison (still contributes gt_ms/gt_timed_out above). Absent
        # entirely (older ground_truth.py output, no gt_subdivided column) behaves exactly as
        # before -- this only changes rows explicitly marked subdivided.
        if gt.get("gt_subdivided") == "true":
            not_comparable += 1
            heal_status(row)
            continue

        if gt["gt_timed_out"] != "false" or not gt["true_orbits"]:
            heal_status(row)
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

    print(
        f"Merged {matched}/{len(rows)} row(s) with ground truth ({disagreements} disagreement(s)), "
        f"{not_comparable} row(s) skipped as not comparable (gt_subdivided=true -- re-run "
        f"ground_truth.py with --subdivide=never for these to compare). Written to {args.out}",
    )


if __name__ == "__main__":
    main()