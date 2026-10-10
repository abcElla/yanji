package cn.yanji.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.yanji.agent.contract.EvidenceCitations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class ReadableSummaryTest {
  @Test
  void wrappedCodeRemainsInOriginalCitationRatherThanBecomingAMalformedSummaryFormula() {
    var output = Fixtures.answer();
    var claim = (ObjectNode) output.at("/result/claims/0");
    String original = "实际运行Python Decimal计算：Decimal(8)/1000*5*3\r\n0=1.2。";
    claim.put("text", original);
    ((ObjectNode) claim.at("/source_refs/0")).put("quote", original);
    EvidenceCitations.constrainSummary(output, Fixtures.context("research_qa"), Clock.systemUTC());
    assertThat(output.at("/result/summary").asText())
        .contains("Python Decimal", "原文")
        .doesNotContain("*3 0");
    assertThat(claim.at("/source_refs/0/quote").asText()).isEqualTo(original);
  }

  @Test
  void thesisFeedbackUsesOneHeadingAndSeparatesManuscriptQuotes() {
    var context = Fixtures.context("research_qa");
    context.putObject("subject").put("paper_id", "paper");
    var output = Fixtures.answer();
    var claims = output.withObject("/result").putArray("claims");
    for (String text : new String[] {"请说明参数来源。", "请核对单位。", "这是理论估算。"}) {
      var claim = claims.addObject().put("text", text);
      claim
          .putArray("source_refs")
          .addObject()
          .putObject("locator")
          .put("kind", "record_field")
          .put("field", text.startsWith("请") ? "content" : "results");
    }
    EvidenceCitations.constrainSummary(output, context, Clock.systemUTC());
    String summary = output.at("/result/summary").asText();
    assertThat(summary.split("导师对这份稿件的意见：", -1)).hasSize(2);
    assertThat(summary).contains("稿件中的相关内容：", "请说明参数来源。", "请核对单位。", "这是理论估算。");
  }

  @Test
  void rawDecisionIsReadableWithoutInventingMentorApproval() {
    var output = Fixtures.answer();
    var claim = (ObjectNode) output.at("/result/claims/0");
    claim.put("text", "changes_requested");
    var ref = (ObjectNode) claim.at("/source_refs/0");
    ref.put("quote", "changes_requested");
    ref.set("locator", Fixtures.object("{\"kind\":\"record_field\",\"field\":\"decision\"}"));
    EvidenceCitations.constrainSummary(output, Fixtures.context("research_qa"), Clock.systemUTC());
    assertThat(output.at("/result/summary").asText())
        .contains("原文标记", "要求修改")
        .doesNotContain("导师意见")
        .doesNotContain("changes_requested");
    assertThat(output.at("/result/claims/0/source_refs/0/quote").asText())
        .isEqualTo("changes_requested");
  }

  @Test
  void openingQuestionDoesNotPretendToBeAnExperimentReview() throws Exception {
    var mapper = new ObjectMapper();
    var context = mapper.createObjectNode().put("kind", "research_qa");
    ObjectNode output =
        (ObjectNode)
            mapper.readTree(
                "{\"result\":{\"outcome\":\"ok\",\"claims\":[],\"missing_inputs\":[\"研究问题\"]}}");
    EvidenceCitations.constrainSummary(output, context, Clock.systemUTC());
    assertThat(output.at("/result/summary").asText())
        .doesNotContain("实验目的", "实验已经完成")
        .contains("需要补充", "确认");
  }

  @Test
  void allResearchSummariesUsePlainLanguageWithoutSnapshotJargon() throws Exception {
    var mapper = new ObjectMapper();
    for (String kind : new String[] {"stage_plan", "risk_brief", "research_qa", "paper_guide"}) {
      var context = mapper.createObjectNode().put("kind", kind);
      context.putObject("stage").put("title", "开题准备");
      context.putObject("paper").put("title", "图像分类方法");
      context.putArray("tasks");
      ObjectNode output =
          (ObjectNode) mapper.readTree("{\"result\":{\"outcome\":\"ok\",\"claims\":[]}}");
      EvidenceCitations.constrainSummary(output, context, Clock.systemUTC());
      assertThat(output.at("/result/summary").asText())
          .doesNotContain("授权", "快照", "事实条目", "规则引用", "核验");
    }
  }
}
