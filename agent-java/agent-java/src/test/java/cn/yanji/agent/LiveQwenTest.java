package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Opt-in billable smoke test; only synthetic fixtures are sent to Qwen. */
@EnabledIfSystemProperty(named = "liveQwen", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveQwenTest {
  @Autowired ModelGateway model;
  @Autowired OutputGuard guard;

  @Test
  void syntheticEvidenceProducesSchemaValidLocatedAnswer() {
    var context = Fixtures.context("research_qa");
    context.withObject("/input").put("question", "现有结果与导师给定的重复验证要求分别是什么？");
    var evidence = Fixtures.evidence();
    ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.get(0))
        .put("text", "实验结果为10，尚未重复验证。导师要求至少3次重复实验。");
    var raw = model.generate(context, evidence, Fixtures.JSON.createArrayNode());
    var validated = guard.prepare(raw, context, evidence, 1);
    assertThat(validated.at("/result/outcome").asText()).isEqualTo("ok");
    assertThat(validated.at("/result/claims")).isNotEmpty();
  }

  @Test
  void stagePlanProducesApprovableTaskPayload() {
    var context = Fixtures.planningContext();
    String requirement = "导师要求至少3次重复实验并保留完整记录。";
    context.set(
        "stage_rules",
        Fixtures.JSON
            .createObjectNode()
            .put("source_id", Fixtures.SOURCE)
            .put("source_revision", 1)
            .put("description", requirement));
    var evidence = Fixtures.evidence();
    var rule =
        Fixtures.JSON
            .createObjectNode()
            .put("source_id", Fixtures.SOURCE)
            .put("source_revision", 1)
            .put("chunk_id", "stage-rule-1")
            .put("text", requirement);
    rule.set("locator", Fixtures.object("{\"kind\":\"record_field\",\"field\":\"stage_rules\"}"));
    evidence.add(rule);
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("stage"))
        .put("title", "验证实验结果")
        .put("goal", "重复实验检查结果稳定性");
    var validated =
        guard.prepare(
            model.generate(context, evidence, Fixtures.JSON.createArrayNode()),
            context,
            evidence,
            1);
    assertThat(validated.at("/result/outcome").asText()).isEqualTo("ok");
    assertThat(validated.path("proposal_drafts")).isNotEmpty();
  }

  @Test
  void paperGuideProducesLocatedReadingNoteDraft() {
    var context = Fixtures.context("paper_guide");
    context.withArray("allowed_actions").add("reading_note.draft");
    context.set(
        "paper",
        Fixtures.object(
            "{\"id\":\""
                + Fixtures.STAGE
                + "\",\"source_id\":\""
                + Fixtures.SOURCE
                + "\",\"version\":1,\"title\":\"实验验证方法\"}"));
    var validated =
        guard.prepare(
            model.generate(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode()),
            context,
            Fixtures.evidence(),
            1);
    assertThat(validated.at("/result/outcome").asText()).isEqualTo("ok");
    assertThat(validated.path("proposal_drafts")).isNotEmpty();
  }

  @Test
  void weeklyReportProducesDraftFromPeriodScopedEvidence() {
    var context = Fixtures.context("weekly_report");
    context.withArray("allowed_actions").add("weekly_report.draft");
    context.put("existing_report_version", 0);
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("input"))
        .put("week_start", "2026-10-05");
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"confirmed_at\":\"2026-10-05T08:00:00+08:00\",\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1,\"results\":\"实验结果为10，尚未重复验证。\"}"));
    var validated =
        guard.prepare(
            model.generate(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode()),
            context,
            Fixtures.evidence(),
            1);
    assertThat(validated.at("/result/outcome").asText()).isEqualTo("ok");
    assertThat(validated.at("/proposal_drafts/0/payload/week_start").asText())
        .isEqualTo("2026-10-05");
  }

  @Test
  void riskBriefKeepsBusinessWritesDisabled() {
    var context = Fixtures.context("risk_brief");
    var risks =
        Fixtures.JSON
            .createArrayNode()
            .add(
                Fixtures.object(
                    "{\"rule\":\"TASK_BLOCKED\",\"task_id\":\"" + Fixtures.STAGE + "\"}"));
    var validated =
        guard.prepare(
            model.generate(context, Fixtures.evidence(), risks), context, Fixtures.evidence(), 1);
    assertThat(validated.path("proposal_drafts")).isEmpty();
  }
}
