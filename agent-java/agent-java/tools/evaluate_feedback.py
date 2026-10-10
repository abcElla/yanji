"""Score independently annotated paired observations. Missing ratings never become a claimed accuracy."""
import argparse
import json
import random
from collections import defaultdict
from pathlib import Path

VARIANTS = {"baseline", "constrained", "feedback"}
CONSTRAINT_ITEMS = {"no_invention", "current_priority", "conflict_handling", "no_repeat", "scope_safe", "confirmation", "no_execution", "detect_cycle", "prerequisite_order"}
FEEDBACK_ITEMS = {"uses_experience"}


def evaluate(records):
    paired = defaultdict(dict)
    for row in records:
        key = (row["case_id"], row["repeat"])
        variant = row["variant"]
        if variant not in VARIANTS or variant in paired[key]:
            raise ValueError("Unknown or duplicate variant")
        rubric = row.get("rubric", {})
        if not rubric:
            raise ValueError("Every case needs a fixed rubric")
        status = row.get("status")
        if status not in {"succeeded", "failed"}:
            raise ValueError("Unfinished runs cannot be omitted from the denominator")
        scores = row.get("scores", {})
        if status == "succeeded":
            if set(scores) != set(rubric) or any(type(v) is not int or v not in (0, 1) for v in scores.values()):
                raise ValueError(f"Complete independent 0/1 ratings required: {key}, {variant}")
            values = list(scores.values())
        else:
            values = [0] * len(rubric)
        paired[key][variant] = dict(row, task_success=int(all(values)), adherence=sum(values) / len(values))
        paired[key][variant]["effective_scores"] = dict(zip(rubric, values))
    if not paired or any(set(rows) != VARIANTS for rows in paired.values()):
        raise ValueError("Each case/repeat must include all three variants, including failures")
    for key, rows in paired.items():
        if len({json.dumps(row["rubric"], sort_keys=True, ensure_ascii=False) for row in rows.values()}) != 1:
            raise ValueError(f"Paired rubrics differ: {key}")
    result = {"samples_per_variant": len(paired), "metrics": {}}
    for variant in sorted(VARIANTS):
        rows = [group[variant] for group in paired.values()]
        result["metrics"][variant] = {
            "task_success_rate": sum(row["task_success"] for row in rows) / len(rows),
            "rubric_adherence_rate": sum(row["adherence"] for row in rows) / len(rows),
            "run_failure_rate": sum(row["status"] == "failed" for row in rows) / len(rows),
            "mean_latency_ms": sum(row.get("latency_ms", 0) for row in rows) / len(rows),
            "observed_tokens": sum(row.get("tokens", 0) for row in rows),
            "mean_model_calls": sum(row.get("model_calls", 0) for row in rows) / len(rows),
            "needs_input_rate": sum(row.get("answer", {}).get("result", {}).get("outcome") == "needs_input" for row in rows) / len(rows),
        }
        for metric, keys in (("constraint_rubric_rate", CONSTRAINT_ITEMS), ("applicable_feedback_adherence_rate", FEEDBACK_ITEMS)):
            values = [score for row in rows for key, score in row["effective_scores"].items() if key in keys]
            result["metrics"][variant][metric] = sum(values) / len(values) if values else None
            result["metrics"][variant][metric + "_items"] = len(values)
    comparisons = {}
    for baseline in ("baseline", "constrained"):
        by_case = defaultdict(list)
        for (case_id, _), rows in paired.items():
            by_case[case_id].append(rows["feedback"]["task_success"] - rows[baseline]["task_success"])
        deltas = [sum(values) / len(values) for values in by_case.values()]
        rng = random.Random(42)
        bootstrap = sorted(sum(rng.choices(deltas, k=len(deltas))) / len(deltas) for _ in range(2000))
        comparisons[f"feedback_minus_{baseline}"] = {
            "case_mean_success_difference": sum(deltas) / len(deltas),
            "case_bootstrap_95_percent_interval": [bootstrap[49], bootstrap[1949]],
            "independent_case_count": len(deltas),
        }
    result["paired_comparisons"] = comparisons
    result["note"] = "Synthetic-case rubric results; not general scientific correctness or public-benchmark accuracy."
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("observations", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    summary = json.dumps(evaluate(json.loads(args.observations.read_text(encoding="utf-8"))), ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(summary + "\n", encoding="utf-8")
    else:
        print(summary)
