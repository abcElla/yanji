package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.workflow.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class GuidanceV3Test {
  @Test
  void blockedCycleGetsClarificationActionWithoutBypassingBlockedTasks() {
    var context = ConstraintAwarePlanningTest.context();
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.at("/tasks/1"))
        .putArray("dependency_ids")
        .add("improve");
    context
        .withObject("/learning_policy")
        .put("structured_planning_enabled", true)
        .put("grounded_guidance_enabled", true);
    context = ConstraintAwarePlanning.prepare(context);
    var fallback =
        GroundedGuidance.fallback(context, java.util.List.of("DEPENDENCY_PRECONDITION_UNMET"));
    assertThat(fallback.at("/result/outcome").asText()).isEqualTo("needs_input");
    assertThat(fallback.at("/result/summary").asText()).contains("循环");
    assertThat(fallback.at("/result/next_steps/0/task_mode").asText()).isEqualTo("new");
    assertThat(ConstraintAwarePlanning.violations(context, fallback)).isEmpty();
    assertThat(fallback.at("/result/next_steps/0/description").asText()).contains("不执行被阻塞任务");
  }

  @Test
  void priorStepAliasMayDeclareTheSameActualTaskPrerequisiteButFutureAliasCannot() {
    var context = ConstraintAwarePlanningTest.context();
    context.withObject("/learning_policy").put("structured_planning_enabled", true);
    var draft =
        Fixtures.object(
            "{\"result\":{\"next_steps\":[{\"step_id\":\"s1\",\"task_mode\":\"existing\",\"existing_task_id\":\"baseline\",\"title\":\"启动基线\",\"depends_on\":[]},{\"step_id\":\"s2\",\"task_mode\":\"existing\",\"existing_task_id\":\"improve\",\"title\":\"改进模型\",\"depends_on\":[\"s1\"]}]}}");
    assertThat(ConstraintAwarePlanning.violations(context, draft)).isEmpty();
    ((com.fasterxml.jackson.databind.node.ObjectNode) draft.at("/result/next_steps/1"))
        .putArray("depends_on")
        .add("s2");
    assertThat(ConstraintAwarePlanning.violations(context, draft))
        .contains("DEPENDENCY_PRECONDITION_UNMET", "DEPENDENCY_BINDING_MISSING");
  }

  @Test
  void optionalPlannerFeedbackSurvivesTheWorkflowSerializationBoundary() {
    var guard = new ResearchPlanGuard();
    var parsed = guard.parse(Fixtures.object("{\"goal\":\"核对材料\",\"queries\":[]}"));
    var checked = guard.parse(Fixtures.JSON.valueToTree(parsed));
    assertThat(checked.feedbackInterpretations().isArray()).isTrue();
    assertThat(checked.queries()).isEmpty();
  }

  @Test
  void publicationRemovesInventedRoutesButKeepsTaskAndDependencyAndFailsUnsupportedFacts() {
    var context = ConstraintAwarePlanningTest.context();
    context.withObject("/learning_policy").put("grounded_guidance_enabled", true);
    context = GroundedGuidance.prepare(context, Fixtures.JSON.createArrayNode());
    var raw =
        Fixtures.object(
            "{\"result\":{\"summary\":\"安排基线\",\"claims\":[{\"text\":\"准确率98%\"}],\"next_steps\":[{\"step_id\":\"s1\",\"title\":\"固定数据划分\",\"depends_on\":[],\"description\":\"打开【自动基线平台】填写\",\"acceptance_criteria\":[]}],\"suggested_skills\":[{\"id\":\"invented\"}]}}");
    var published = GroundedGuidance.publish(context, raw);
    assertThat(published.at("/result/next_steps/0/title").asText()).isEqualTo("固定数据划分");
    assertThat(published.at("/result/next_steps/0/depends_on")).isEmpty();
    assertThat(published.at("/result/suggested_skills")).isEmpty();
    assertThat(published.toString()).doesNotContain("自动基线平台");
    assertThat(GroundedGuidance.violations(context, published)).containsExactly("UNSUPPORTED_FACT");
    assertThat(raw.toString()).contains("自动基线平台");
  }

  @Test
  void feedbackMustQuoteAuthorizedMemoryAndCannotUpgradeItsScope() {
    var context = Fixtures.context("research_followup");
    context.putObject("learning_policy").put("feedback_learning_enabled", true);
    context
        .putArray("feedback_memories")
        .addObject()
        .put("lesson", "本周不要调整配色")
        .put("reuse_scope", "temporary_requirement");
    var supplied =
        Fixtures.object(
                "{\"items\":[{\"memory_index\":0,\"source_quote\":\"本周不要调整配色\",\"direct_request\":true,\"intent\":\"general_guidance\",\"reuse_scope\":\"global\"},{\"memory_index\":0,\"source_quote\":\"导师已经批准\",\"direct_request\":true,\"intent\":\"general_guidance\"}]}")
            .path("items");
    var accepted = SemanticFeedback.accept(context, supplied);
    assertThat(accepted).hasSize(1);
    assertThat(accepted.get(0).path("reuse_scope").asText()).isEqualTo("temporary_requirement");
    assertThat(accepted.get(0).path("interpretation_is_confirmed").asBoolean()).isFalse();
  }

  @Test
  void renamedExistingTaskStillChecksActualDependencies() {
    var context = ConstraintAwarePlanningTest.context();
    context.withObject("/learning_policy").put("structured_planning_enabled", true);
    var wrong =
        Fixtures.object(
            "{\"result\":{\"next_steps\":[{\"step_id\":\"s1\",\"task_mode\":\"existing\",\"existing_task_id\":\"improve\",\"title\":\"训练更好的网络\",\"depends_on\":[\"baseline\"]}]}}");
    assertThat(ConstraintAwarePlanning.violations(context, wrong))
        .contains("DEPENDENCY_PRECONDITION_UNMET");
    var missing = Fixtures.object("{\"result\":{\"next_steps\":[{\"title\":\"未知关联\"}]}}");
    assertThat(ConstraintAwarePlanning.violations(context, missing))
        .contains("TASK_ASSOCIATION_MISSING");
  }

  @Test
  void newStepDependencyRequiresEarlierStepOrCompletedTask() {
    var context = ConstraintAwarePlanningTest.context();
    context.withObject("/learning_policy").put("structured_planning_enabled", true);
    var draft =
        Fixtures.object(
            "{\"result\":{\"next_steps\":[{\"step_id\":\"s1\",\"task_mode\":\"new\",\"title\":\"整理依赖\",\"depends_on\":[]},{\"step_id\":\"s2\",\"task_mode\":\"new\",\"title\":\"导师确认\",\"depends_on\":[\"s1\"]}]}}");
    assertThat(ConstraintAwarePlanning.violations(context, draft)).isEmpty();
    for (var step : draft.at("/result/next_steps"))
      ((com.fasterxml.jackson.databind.node.ObjectNode) step).putNull("existing_task_id");
    assertThat(ConstraintAwarePlanning.violations(context, draft)).isEmpty();
    ((com.fasterxml.jackson.databind.node.ObjectNode) draft.at("/result/next_steps/0"))
        .putArray("depends_on")
        .add("s2");
    assertThat(ConstraintAwarePlanning.violations(context, draft))
        .contains("DEPENDENCY_PRECONDITION_UNMET");
  }

  @Test
  void exactFactAnchorAndRealCatalogLabelAreRequired() {
    var context = ConstraintAwarePlanningTest.context();
    context.withObject("/learning_policy").put("grounded_guidance_enabled", true);
    context.putArray("skill_catalog").addObject().put("id", "record").put("label", "补充科研记录");
    context = GroundedGuidance.prepare(context, Fixtures.JSON.createArrayNode());
    var legal =
        Fixtures.object(
            "{\"result\":{\"summary\":\"请使用【补充科研记录】\",\"claims\":[{\"anchor_id\":\"f0\",\"text\":\"改进模型：待开始\"}]}}");
    assertThat(GroundedGuidance.violations(context, legal)).isEmpty();
    var wrong =
        Fixtures.object(
            "{\"result\":{\"summary\":\"打开【自动证明平台】\",\"claims\":[{\"anchor_id\":\"f0\",\"text\":\"准确率达到98%\"}]}}");
    assertThat(GroundedGuidance.violations(context, wrong))
        .contains("UNSUPPORTED_FACT", "UNKNOWN_UI_FUNCTION");
  }

  @Test
  void displayPreservesVerifiedAnnotationsAndTaskVersionWithoutTrustingModelVersion() {
    var context = ConstraintAwarePlanningTest.context();
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.at("/tasks/0")).put("version", 4);
    context.withObject("/learning_policy").put("feedback_learning_enabled", true);
    context.putArray("feedback_memories").addObject().put("lesson", "补充基线");
    var raw =
        Fixtures.object(
            "{\"result\":{\"outcome\":\"ok\",\"summary\":\"待确认建议\",\"next_steps\":[{\"title\":\"改进\",\"existing_task_id\":\"improve\",\"expected_version\":999}],\"feedback_interpretations\":[{\"memory_index\":0,\"source_quote\":\"补充基线\",\"direct_request\":true,\"intent\":\"baseline\"}]}}");
    var result =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(raw, context, Fixtures.JSON.createArrayNode(), 1);
    assertThat(result.at("/result/next_steps/0/expected_version").asInt()).isEqualTo(4);
    assertThat(result.at("/result/feedback_interpretations")).hasSize(1);
  }
}
