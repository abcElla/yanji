package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.OutputGuard;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class DisplayOutputTest {
  @Test
  void topLevelFollowupSkillsArePreservedUsingTrustedCatalog() {
    var context = Fixtures.context("research_followup");
    var available =
        context
            .putArray("skill_catalog")
            .addObject()
            .put("id", "risk_overview")
            .put("label", "检查任务安排")
            .put("kind", "risk_brief");
    available.putObject("subject").put("student_id", Fixtures.STUDENT);
    available.putObject("input").put("horizon_days", 7);
    var answer = Fixtures.answer();
    answer
        .putArray("suggested_skills")
        .addObject()
        .put("id", "risk_overview")
        .put("reason", "先核对现有安排")
        .put("kind", "invented");
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, context, Fixtures.evidence(), 1);
    assertThat(out.at("/result/suggested_skills/0/kind").asText()).isEqualTo("risk_brief");
    assertThat(out.at("/result/suggested_skills/0/subject/student_id").asText())
        .isEqualTo(Fixtures.STUDENT);
  }

  @Test
  void followupSkillsUseOnlyServerCatalogAndIgnoreInventedArguments() {
    var context = Fixtures.context("research_followup");
    context
        .putArray("skill_catalog")
        .addObject()
        .put("id", "risk_overview")
        .put("label", "检查任务安排")
        .put("kind", "risk_brief")
        .set("subject", Fixtures.JSON.createObjectNode().put("student_id", Fixtures.STUDENT));
    ((ObjectNode) context.at("/skill_catalog/0")).putObject("input").put("horizon_days", 7);
    var answer = Fixtures.answer();
    answer
        .withObject("/result")
        .putArray("suggested_skills")
        .addObject()
        .put("id", "risk_overview")
        .put("reason", "看看哪些工作需要先处理")
        .put("kind", "weekly_report")
        .put("route", "/unknown");
    answer.withArray("/result/suggested_skills").addObject().put("id", "invented_tool");
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, context, Fixtures.evidence(), 1);
    assertThat(out.at("/result/suggested_skills")).hasSize(1);
    assertThat(out.at("/result/suggested_skills/0/kind").asText()).isEqualTo("risk_brief");
    assertThat(out.at("/result/suggested_skills/0/subject/student_id").asText())
        .isEqualTo(Fixtures.STUDENT);
    assertThat(out.at("/result/suggested_skills/0/route").isMissingNode()).isTrue();
  }

  @Test
  void followupAlsoPreservesStepsReturnedBesideTheResult() {
    var answer = Fixtures.answer();
    answer
        .putArray("next_steps")
        .addObject()
        .put("title", "整理台灯计算复核表")
        .put("description", "记录给定参数的单位换算，不冒充实测")
        .put("type", "writing")
        .putArray("acceptance_criteria")
        .add("标明理论估算");
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, Fixtures.context("research_followup"), Fixtures.evidence(), 1);
    assertThat(out.at("/result/next_steps/0/title").asText()).isEqualTo("整理台灯计算复核表");
    assertThat(out.path("proposal_drafts")).isEmpty();
  }

  @Test
  void followupNextStepsRemainEditableWithoutInventingExecutionActions() {
    var answer = Fixtures.answer();
    answer
        .withObject("/result")
        .putArray("next_steps")
        .addObject()
        .put("title", "核对台灯功率和每天使用时长")
        .put("description", "先写清楚给定参数，再复核计算结果")
        .put("type", "other")
        .putArray("acceptance_criteria")
        .add("记录完整的公式和单位");
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, Fixtures.context("research_followup"), Fixtures.evidence(), 1);
    assertThat(out.at("/result/next_steps/0/title").asText()).isEqualTo("核对台灯功率和每天使用时长");
    assertThat(out.path("proposal_drafts")).isEmpty();
  }

  @Test
  void excessExecutableActionsBecomeTextInsteadOfFailingTheDisplay() {
    var context = Fixtures.planningContext();
    context.withObject("/limits").put("max_proposals", 1);
    var answer = Fixtures.answer();
    for (int i = 0; i < 21; i++) answer.withArray("proposal_drafts").add(Fixtures.plan());
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, context, Fixtures.evidence(), 1);
    assertThat(out.path("proposal_drafts")).hasSize(1);
    assertThat(out.at("/result/claims").toString()).contains("验证实验");
  }

  @Test
  void validStageActionStillSupportsTheExistingConfirmationFlow() {
    var answer = Fixtures.answer();
    answer.withArray("proposal_drafts").add(Fixtures.plan());
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1);
    assertThat(out.path("proposal_drafts")).hasSize(1);
    assertThat(out.at("/proposal_drafts/0/action_type").asText()).isEqualTo("task.create_batch");
    assertThat(out.at("/proposal_drafts/0/target_student_id").asText()).isEqualTo(Fixtures.STUDENT);
  }

  @Test
  void unauthorizedModelSuggestionIsDisplayedWithoutCreatingAnExecutableAction() {
    var answer = Fixtures.answer();
    answer.withArray("proposal_drafts").add(Fixtures.plan());
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1);
    assertThat(out.at("/result/summary").asText()).isEqualTo("有实验结果，尚需验证。");
    assertThat(out.at("/result/claims").toString()).contains("补充验证", "验证实验");
    assertThat(out.path("proposal_drafts")).isEmpty();
  }

  @Test
  void modelTextWithoutMatchingEvidenceIsStillDisplayedWithoutInventedReferences() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0")).put("text", "建议至少重复三次，再比较差异。");
    var out =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(answer, Fixtures.context("research_qa"), Fixtures.JSON.createArrayNode(), 1);
    assertThat(out.at("/result/claims/0/text").asText()).isEqualTo("建议至少重复三次，再比较差异。");
    assertThat(out.at("/result/claims/0/source_refs")).isEmpty();
  }
}
