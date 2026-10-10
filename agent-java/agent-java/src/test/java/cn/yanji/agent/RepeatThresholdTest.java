package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.contract.OutputGuard;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class RepeatThresholdTest {
  @Test
  void explicitlyNegatedMinimumCannotAuthorizeNewAcceptanceThreshold() {
    var raw = Fixtures.answer();
    var proposal = Fixtures.plan();
    ((com.fasterxml.jackson.databind.node.ObjectNode) proposal.at("/payload/tasks/0"))
        .putArray("acceptance_criteria")
        .add("论文必须至少3项局限");
    raw.withArray("proposal_drafts").add(proposal);
    var evidence = Fixtures.evidence();
    ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.get(0))
        .put("text", "实验结果为10，导师不要求至少3项局限。");
    assertThatThrownBy(
            () ->
                new OutputGuard(Fixtures.JSON, Clock.systemUTC())
                    .prepare(raw, Fixtures.planningContext(), evidence, 1))
        .isInstanceOf(AgentFailure.class)
        .hasMessageContaining("数量门槛");
  }

  @Test
  void unsourcedChineseMinimumInTaskAcceptanceIsRejected() {
    var raw = Fixtures.answer();
    var proposal = Fixtures.plan();
    ((com.fasterxml.jackson.databind.node.ObjectNode) proposal.at("/payload/tasks/0"))
        .putArray("acceptance_criteria")
        .add("论文包含三项以上具体限制");
    raw.withArray("proposal_drafts").add(proposal);
    assertThatThrownBy(
            () ->
                new OutputGuard(Fixtures.JSON, Clock.systemUTC())
                    .prepare(raw, Fixtures.planningContext(), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class)
        .hasMessageContaining("数量门槛");
  }

  @Test
  void explicitlySourcedTaskMinimumIsPreserved() {
    var raw = Fixtures.answer();
    var proposal = Fixtures.plan();
    ((com.fasterxml.jackson.databind.node.ObjectNode) proposal.at("/payload/tasks/0"))
        .putArray("acceptance_criteria")
        .add("论文包含三项以上具体限制");
    raw.withArray("proposal_drafts").add(proposal);
    var evidence = Fixtures.evidence();
    ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.get(0))
        .put("text", "实验结果为10；导师要求至少3项具体限制。");
    assertThat(
            new OutputGuard(Fixtures.JSON, Clock.systemUTC())
                .prepare(raw, Fixtures.planningContext(), evidence, 1))
        .isNotNull();
  }

  @Test
  void unsupportedMinimumIsRejectedAcrossUserFacingFields() {
    var guard = new OutputGuard(Fixtures.JSON, Clock.systemUTC());
    for (String field : java.util.List.of("summary", "missing_inputs", "limitations")) {
      var raw = Fixtures.answer();
      if (field.equals("summary")) raw.withObject("/result").put(field, "必须补充不少于5次独立重复测量");
      else raw.withObject("/result").withArray(field).add("至少五次独立重复实验");
      assertThatThrownBy(
              () -> guard.prepare(raw, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
          .isInstanceOf(AgentFailure.class);
    }
  }

  @Test
  void sourcedMinimumAndObservedCountsRemainValid() {
    var guard = new OutputGuard(Fixtures.JSON, Clock.systemUTC());
    var raw = Fixtures.answer();
    raw.withObject("/result").put("summary", "当前规则要求至少5次重复测量；现有3次记录。");
    var evidence = Fixtures.evidence();
    ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.get(0))
        .put("text", "实验结果为10，规则要求不少于五次重复测量。");
    assertThat(guard.prepare(raw, Fixtures.context("research_qa"), evidence, 1)).isNotNull();
    raw.withObject("/result").put("summary", "测量结果10、10、11，已记录3次，稳定性尚未证实。");
    assertThat(guard.prepare(raw, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isNotNull();
  }

  @Test
  void reversedOrderAndChineseMultiDigitMinimumAreRejected() {
    var guard = new OutputGuard(Fixtures.JSON, Clock.systemUTC());
    for (String text : java.util.List.of("必须至少重复5次才能验证稳定性", "至少十二次独立重复实验")) {
      var raw = Fixtures.answer();
      raw.withObject("/result").put("summary", text);
      assertThatThrownBy(
              () -> guard.prepare(raw, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
          .isInstanceOf(AgentFailure.class);
    }
  }

  @Test
  void negatedMinimumAndUnrelatedOccurrencesAreNotRequirements() {
    var guard = new OutputGuard(Fixtures.JSON, Clock.systemUTC());
    for (String text : java.util.List.of("不能将至少5次重复作为门槛", "该设备至少5次报错")) {
      var raw = Fixtures.answer();
      raw.withObject("/result").put("summary", text);
      assertThat(guard.prepare(raw, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
          .isNotNull();
    }
  }

  @Test
  void internalFieldNamesAreHumanizedWithoutChangingCitationQuotes() {
    var raw = Fixtures.answer();
    raw.withObject("/result")
        .put("summary", "无confirmed_notes支持内容；confirmed_experiments按confirmed_at筛选。");
    raw.withObject("/result").withArray("limitations").add("缺少confirmed_notes");
    var quotedEvidence = Fixtures.evidence();
    ((com.fasterxml.jackson.databind.node.ObjectNode) quotedEvidence.get(0))
        .put("text", "实验结果为10，confirmed_notes记录。");
    ((com.fasterxml.jackson.databind.node.ObjectNode) raw.at("/result/claims/0/source_refs/0"))
        .put("quote", "confirmed_notes记录");
    var prepared =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC())
            .prepare(raw, Fixtures.context("research_qa"), quotedEvidence, 1);
    assertThat(prepared.at("/result/summary").asText())
        .contains("确认")
        .doesNotContain("confirmed_notes", "confirmed_experiments", "confirmed_at");
    assertThat(prepared.at("/result/claims/0/text").asText()).isEqualTo("confirmed_notes记录");
    assertThat(prepared.at("/result/limitations/0").asText()).isEqualTo("缺少已确认笔记");
    assertThat(prepared.at("/result/claims/0/source_refs/0/quote"))
        .isEqualTo(raw.at("/result/claims/0/source_refs/0/quote"));
  }
}
