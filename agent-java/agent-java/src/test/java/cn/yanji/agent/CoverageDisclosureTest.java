package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.ModelInputPlanner;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class CoverageDisclosureTest {
  final OutputGuard guard = new OutputGuard(Fixtures.JSON, Clock.systemUTC());

  ObjectNode partialContext() {
    var context = Fixtures.context("weekly_report");
    context.withObject("/input").put("week_start", "2026-10-05");
    context.set(
        "record_coverage",
        Fixtures.object(
            "{\"total_records\":12,\"included_records\":10,\"omitted_records\":2,\"partial\":true,\"omitted_records_searchable\":true,\"source_scope_truncated\":false,\"omitted_sources\":[{\"source_id\":\"ignored-for-model\"}]}"));
    context.set(
        "evidence_policy",
        Fixtures.object("{\"task_facts_basis\":\"tasks_and_business_versions\"}"));
    return context;
  }

  @Test
  void weeklyModelInputPreservesCoverageWithoutSendingLongOmittedIdList() {
    var context = partialContext();
    var input =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.at("/context/record_coverage/partial").asBoolean()).isTrue();
    assertThat(input.at("/context/record_coverage/omitted_records").asInt()).isEqualTo(2);
    assertThat(input.at("/context/evidence_policy/task_facts_basis").asText())
        .isEqualTo("tasks_and_business_versions");
    assertThat(input.at("/context/record_coverage/omitted_sources").isMissingNode()).isTrue();
    assertThat(context.at("/record_coverage/omitted_sources")).hasSize(1);
  }

  @Test
  void partialScopePersistsInApprovedWeeklyPayloadAsWellAsSummary() {
    var context = partialContext();
    context.withArray("allowed_actions").add("weekly_report.draft");
    context.put("existing_report_version", 0);
    var answer = Fixtures.answer();
    answer.withObject("/result").put("summary", "已总结本周所有记录。");
    var proposal =
        Fixtures.object(
            "{\"action_type\":\"weekly_report.draft\",\"reason\":\"完整周报\",\"source_refs\":[],\"payload\":{\"week_start\":\"2026-10-05\",\"done_items\":[{\"text\":\"结果为10\",\"source_refs\":[]}],\"issues\":[],\"next_plan\":[],\"help_needed\":[]}}");
    proposal.withArray("source_refs").add(Fixtures.reference());
    ((ObjectNode) proposal.at("/payload/done_items/0"))
        .withArray("source_refs")
        .add(Fixtures.reference());
    answer.withArray("proposal_drafts").add(proposal);
    var out = guard.prepare(answer, context, Fixtures.evidence(), 1);
    assertThat(out.at("/result/summary").asText())
        .contains("12", "10", "2", "还没有读取")
        .doesNotContain("已总结本周所有记录");
    assertThat(out.at("/result/limitations").toString()).contains("还没有读取");
    assertThat(out.at("/proposal_drafts/0/payload/done_items/0/text").asText())
        .contains("还没有读取", "结果为10");
    assertThat(out.at("/proposal_drafts/0/payload/done_items/0/source_refs/0/quote").asText())
        .isEqualTo("结果为10");
  }

  @Test
  void sourceLimitNeverPromisesThatOmittedSourcesCanAllBeRead() {
    var context = partialContext();
    context
        .withObject("/record_coverage")
        .put("source_scope_truncated", true)
        .put("excluded_by_source_limit", 3)
        .put("omitted_records_searchable", false);
    var out = guard.prepare(Fixtures.answer(), context, Fixtures.evidence(), 1);
    assertThat(out.at("/result/limitations").toString()).contains("材料数量有限", "不能保证读取全部");
  }

  @Test
  void paperSearchCoverageDoesNotBecomeFullPaperUnderstanding() {
    var context = Fixtures.context("paper_guide");
    context.set(
        "retrieval_coverage",
        Fixtures.object(
            "{\"included_chunks\":3,\"eligible_chunks\":20,\"coverage_complete\":false,\"stats_available\":true}"));
    var out = guard.prepare(Fixtures.answer(), context, Fixtures.evidence(), 1);
    assertThat(out.at("/result/summary").asText())
        .contains("3", "20", "段")
        .doesNotContain("授权分块", "后端候选", "内联");
    assertThat(out.at("/result/limitations").toString()).contains("没有阅读全文");
  }
}
