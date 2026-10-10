package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.workflow.BusinessPolicy;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessPolicyTest {
  @Test
  void reportFeedbackRunsEvenWithoutTaskRisksAndUsesOnlyTheSelectedReport() {
    var context = Fixtures.context("risk_brief");
    context.withObject("/input").put("report_id", Fixtures.STAGE);
    context
        .putObject("weekly_report")
        .put("id", Fixtures.STAGE)
        .put("current_revision", 2)
        .put("status", "submitted")
        .put("source_id", Fixtures.SOURCE)
        .put("source_revision", 1);
    assertThat(policy.preflight(context)).isNull();
    assertThat(policy.evidenceInBusinessScope(Fixtures.evidence().get(0), context)).isTrue();
    var unrelated = Fixtures.evidence().get(0).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unrelated).put("source_id", Fixtures.STUDENT);
    context
        .withArray("source_versions")
        .addObject()
        .put("source_id", Fixtures.STUDENT)
        .put("revision", 1);
    assertThat(policy.evidenceInBusinessScope(unrelated, context)).isFalse();
  }

  @Test
  void emptyWeeklyPeriodAsksForRecordsWithoutClaimingNoWorkWasDone() {
    var context = Fixtures.context("weekly_report");
    context.withObject("/input").put("week_start", "2026-10-05");
    var out = policy.preflight(context);
    assertThat(out.at("/result/outcome").asText()).isEqualTo("insufficient_evidence");
    assertThat(out.at("/result/summary").asText())
        .contains("还没有找到", "已确认", "本周")
        .doesNotContain("可核验的已确认", "没有开展", "没有完成");
    assertThat(out.path("proposal_drafts")).isEmpty();
  }

  @Test
  void riskExplanationRejectsUnrelatedMaterialAndAcceptsPinnedTaskAssociation() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(Fixtures.object("{\"id\":\"risk-task\",\"status\":\"blocked\",\"version\":1}"));
    var chunk = Fixtures.evidence().get(0);
    assertThat(policy.evidenceInBusinessScope(chunk, context)).isFalse();
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"task_id\":\"risk-task\",\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1}"));
    assertThat(policy.evidenceInBusinessScope(chunk, context)).isTrue();
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.at("/confirmed_experiments/0"))
        .put("task_id", "different-task");
    assertThat(policy.evidenceInBusinessScope(chunk, context)).isFalse();
  }

  @Test
  void invalidLongSummaryIsNotSilentlyRepaired() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(Fixtures.object("{\"id\":\"a\",\"status\":\"blocked\",\"version\":1}"));
    var draft = policy.fallback("insufficient_evidence", "长".repeat(5001), "原因");
    var before = draft.deepCopy();
    policy.retainRuleObservations(draft, policy.risks(context));
    assertThat(draft).isEqualTo(before);
  }

  @Test
  void longValidExplanationKeepsItsQualificationIntact() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(Fixtures.object("{\"id\":\"a\",\"status\":\"blocked\",\"version\":1}"));
    String explanation = "说明".repeat(2475) + "，但尚未证明原因。";
    var draft = policy.fallback("insufficient_evidence", explanation, "原因");
    policy.retainRuleObservations(draft, policy.risks(context));
    assertThat(draft.at("/result/summary").asText()).hasSizeLessThanOrEqualTo(5000);
    assertThat(draft.at("/result/limitations").toString().contains(explanation)).isTrue();
  }

  @Test
  void deterministicRiskSurvivesInsufficientEvidenceWithoutInventingReferences() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"task-a\",\"title\":\"撰写台灯估算局限\",\"version\":3,\"status\":\"blocked\",\"due_at\":\"2026-10-04T00:00:00Z\"}"));
    var draft = policy.fallback("insufficient_evidence", "没有解释依据", "风险原因");
    policy.retainRuleObservations(draft, policy.risks(context));
    assertThat(draft.at("/result/summary").asText())
        .contains("撰写台灯估算局限", "已逾期", "遇到困难")
        .doesNotContain("TASK_OVERDUE", "TASK_BLOCKED", "task-a", "版本3");
    assertThat(policy.risks(context).get(0).path("task_id").asText()).isEqualTo("task-a");
    assertThat(draft.at("/result/outcome").asText()).isEqualTo("insufficient_evidence");
    assertThat(draft.at("/result/claims")).isEmpty();
    assertThat(draft.path("proposal_drafts")).isEmpty();
  }

  BusinessPolicy policy =
      new BusinessPolicy(
          Fixtures.JSON, Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void weeklyIntervalUsesShanghaiMondayHalfOpenBounds() {
    var context = Fixtures.context("weekly_report");
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("input"))
        .put("week_start", "2026-10-05");
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"confirmed_at\":\"2026-10-05T00:00:00+08:00\",\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1}"));
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"confirmed_at\":\"2026-10-12T00:00:00+08:00\",\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1}"));
    assertThat(policy.preflight(context)).isNull();
    assertThat(context.path("confirmed_experiments")).hasSize(1);
  }

  @Test
  void invalidWeekStartIsNotGuessedByModel() {
    var context = Fixtures.context("weekly_report");
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("input"))
        .put("week_start", "2026-10-06");
    assertThat(policy.preflight(context).at("/result/outcome").asText()).isEqualTo("needs_input");
  }

  @Test
  void overdueIsDeterministicAndCompletedTaskIsExcluded() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"a\",\"status\":\"blocked\",\"due_at\":\"2026-10-04T00:00:00Z\"}"));
    context
        .withArray("tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"b\",\"status\":\"completed\",\"due_at\":\"2026-10-04T00:00:00Z\"}"));
    assertThat(policy.risks(context)).hasSize(2);
  }

  @Test
  void emptyOrHealthySnapshotReturnsOkWithoutAskingForRiskEvidence() {
    for (String tasks :
        List.of(
            "[]",
            "[{\"id\":\"healthy\",\"status\":\"in_progress\",\"due_at\":null}]",
            "[{\"id\":\"future\",\"status\":\"todo\",\"due_at\":\"2026-10-06T00:00:00+08:00\"}]")) {
      var c = Fixtures.context("risk_brief");
      try {
        c.set("tasks", Fixtures.JSON.readTree(tasks));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      var draft = policy.preflight(c);
      assertThat(draft).isNotNull();
      assertThat(draft.at("/result/outcome").asText()).isEqualTo("ok");
      assertThat(draft.at("/result/summary").asText()).contains("当前未检测到阻塞或逾期");
      assertThat(draft.at("/result/missing_inputs")).isEmpty();
      assertThat(draft.at("/result/claims")).isEmpty();
      assertThat(draft.path("proposal_drafts")).isEmpty();
    }
  }

  @Test
  void missingSnapshotUnknownStatusAndNaiveDateDoNotClaimNoRisk() {
    var missing = Fixtures.context("risk_brief");
    missing.remove("tasks");
    assertThat(policy.preflight(missing).at("/result/outcome").asText()).isEqualTo("needs_input");
    for (String task :
        List.of(
            "{\"id\":\"x\",\"status\":\"unknown\",\"due_at\":null}",
            "{\"id\":\"x\",\"status\":\"todo\",\"due_at\":\"2026-10-04T00:00:00\"}")) {
      var c = Fixtures.context("risk_brief");
      c.withArray("tasks").add(Fixtures.object(task));
      var draft = policy.preflight(c);
      assertThat(draft.at("/result/outcome").asText()).isEqualTo("needs_input");
      assertThat(draft.at("/result/summary").asText()).doesNotContain("当前未检测到阻塞或逾期");
    }
  }

  @Test
  void blockedAndOverdueSnapshotsStillUseRiskExplanationFlow() {
    for (String task :
        List.of(
            "{\"id\":\"x\",\"status\":\"blocked\",\"due_at\":null}",
            "{\"id\":\"x\",\"status\":\"todo\",\"due_at\":\"2026-10-04T00:00:00Z\"}")) {
      var c = Fixtures.context("risk_brief");
      c.withArray("tasks").add(Fixtures.object(task));
      assertThat(policy.preflight(c)).isNull();
      assertThat(policy.risks(c)).hasSize(1);
    }
  }
}
