package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.workflow.ConstraintAwarePlanning;
import org.junit.jupiter.api.Test;

class ConstraintAwarePlanningTest {
  static com.fasterxml.jackson.databind.node.ObjectNode context() {
    var context = Fixtures.context("research_followup");
    context.putObject("learning_policy").put("constrained_reasoning_enabled", true);
    context
        .withArray("/tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"improve\",\"title\":\"改进模型\",\"status\":\"todo\",\"dependency_ids\":[\"baseline\"]}"));
    context
        .withArray("/tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"baseline\",\"title\":\"基线实验\",\"status\":\"todo\",\"dependency_ids\":[]}"));
    return context;
  }

  @Test
  void topologicalOrderPlacesBaselineBeforeImprovementWithoutChangingSnapshot() {
    var original = context();
    var prepared = ConstraintAwarePlanning.prepare(original);
    assertThat(prepared.at("/reasoning_constraints/dependency_order/0/task_id").asText())
        .isEqualTo("baseline");
    assertThat(prepared.at("/reasoning_constraints/ready_tasks")).hasSize(1);
    assertThat(original.has("reasoning_constraints")).isFalse();
  }

  @Test
  void rejectsReverseOrderAndAcceptsExplicitPrerequisiteOrder() {
    var context = context();
    var wrong =
        Fixtures.object(
            "{\"result\":{\"next_steps\":[{\"title\":\"改进模型\"},{\"title\":\"基线实验\"}]}}");
    assertThat(ConstraintAwarePlanning.violations(context, wrong))
        .containsExactly("DEPENDENCY_PRECONDITION_UNMET");
    var correct =
        Fixtures.object(
            "{\"result\":{\"next_steps\":[{\"title\":\"基线实验\"},{\"title\":\"改进模型\"}]}}");
    assertThat(ConstraintAwarePlanning.violations(context, correct)).isEmpty();
  }

  @Test
  void reportsCyclesAndMissingDependenciesRatherThanInventingReadiness() {
    var context = context();
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.at("/tasks/1"))
        .withArray("/dependency_ids")
        .add("improve");
    var prepared = ConstraintAwarePlanning.prepare(context);
    assertThat(prepared.at("/reasoning_constraints/ready_tasks")).isEmpty();
    assertThat(prepared.at("/reasoning_constraints/unresolved_tasks")).hasSize(2);
  }

  @Test
  void rejectsCompletedTaskAndMakesNoScientificTruthJudgment() {
    var context = context();
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.at("/tasks/1"))
        .put("status", "completed");
    assertThat(
            ConstraintAwarePlanning.violations(
                context, Fixtures.object("{\"result\":{\"next_steps\":[{\"title\":\"基线实验\"}]}}")))
        .containsExactly("TASK_ALREADY_COMPLETED");
    assertThat(
            ConstraintAwarePlanning.violations(
                context,
                Fixtures.object("{\"result\":{\"summary\":\"模型效果已经证明\",\"next_steps\":[]}}")))
        .isEmpty();
    context.withObject("/learning_policy").put("constrained_reasoning_enabled", false);
    assertThat(
            ConstraintAwarePlanning.violations(
                context, Fixtures.object("{\"result\":{\"next_steps\":[{\"title\":\"基线实验\"}]}}")))
        .isEmpty();
  }
}
