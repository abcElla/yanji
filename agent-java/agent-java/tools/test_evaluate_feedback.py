import unittest
from evaluate_feedback import evaluate


class ScoringTest(unittest.TestCase):
    def rows(self):
        return [dict(case_id="case", repeat=0, variant=variant, status="succeeded", rubric={"correct": "正确"}, scores={"correct": 1}) for variant in ("baseline", "constrained", "feedback")]

    def test_missing_rating_cannot_produce_accuracy(self):
        rows = self.rows()
        rows[0]["scores"] = {}
        with self.assertRaises(ValueError):
            evaluate(rows)

    def test_failed_run_stays_in_denominator(self):
        rows = self.rows()
        rows[2].update(status="failed", scores={})
        result = evaluate(rows)
        self.assertEqual(result["metrics"]["feedback"]["task_success_rate"], 0)
        self.assertEqual(result["metrics"]["feedback"]["run_failure_rate"], 1)
        self.assertEqual(result["paired_comparisons"]["feedback_minus_baseline"]["case_mean_success_difference"], -1)

    def test_missing_pair_and_duplicate_rejected(self):
        with self.assertRaises(ValueError):
            evaluate(self.rows()[:2])
        with self.assertRaises(ValueError):
            evaluate(self.rows() + [self.rows()[0]])

    def test_scoped_metrics_include_failed_runs_and_ignore_unrelated_items(self):
        rows = self.rows()
        for row in rows:
            row.update(rubric={"no_repeat": "不重复", "uses_experience": "落实反馈", "other": "其他"},
                       scores={"no_repeat": 1, "uses_experience": 0, "other": 1}, model_calls=2)
        rows[2].update(status="failed", scores={})
        metrics = evaluate(rows)["metrics"]
        self.assertEqual(metrics["baseline"]["constraint_rubric_rate"], 1)
        self.assertEqual(metrics["baseline"]["applicable_feedback_adherence_rate"], 0)
        self.assertEqual(metrics["feedback"]["constraint_rubric_rate"], 0)
        self.assertEqual(metrics["feedback"]["constraint_rubric_rate_items"], 1)
        self.assertEqual(metrics["baseline"]["mean_model_calls"], 2)


if __name__ == "__main__":
    unittest.main()
