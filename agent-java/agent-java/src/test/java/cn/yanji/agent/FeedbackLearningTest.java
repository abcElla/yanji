package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.model.*;
import cn.yanji.agent.workflow.PlanningContext;
import org.junit.jupiter.api.Test;

class FeedbackLearningTest {
  @Test
  void switchRemovesMemoryFromBothPlannerAndGeneratorWithoutMutatingSnapshot() {
    var context = Fixtures.context("weekly_report");
    context
        .putObject("learning_policy")
        .put("feedback_learning_enabled", false)
        .put("constrained_reasoning_enabled", false);
    context.putArray("feedback_memories").addObject().put("lesson", "先补充对照");
    context.putObject("reasoning_constraints").put("require_confirmation", true);
    var input =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.path("context").has("feedback_memories")).isFalse();
    assertThat(input.path("context").has("reasoning_constraints")).isFalse();
    assertThat(PlanningContext.build(context, Fixtures.JSON).has("feedback_memories")).isFalse();
    assertThat(ExperienceInstructions.forContext(context)).isEmpty();
    assertThat(context.path("feedback_memories")).hasSize(1);
  }

  @Test
  void memoriesReachBothModelStagesButPlannerNeverCopiesIdentityOrProvenance() {
    var context = Fixtures.context("weekly_report");
    context
        .putObject("learning_policy")
        .put("feedback_learning_enabled", true)
        .put("constrained_reasoning_enabled", true);
    var memory =
        context
            .putArray("feedback_memories")
            .addObject()
            .put("lesson", "用户修改为先补充基线对照")
            .put("source_id", "private-source");
    memory.putObject("observations").put("adoption_is_correctness", false);
    context
        .putArray("mentor_constraints")
        .addObject()
        .put("content", "当前要求优先补充消融实验")
        .put("mentor_id", "private-mentor");
    var generation =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(generation.at("/context/feedback_memories/0/lesson").asText()).contains("基线");
    var planning = PlanningContext.build(context, Fixtures.JSON);
    assertThat(planning.toString())
        .contains("基线", "消融")
        .doesNotContain("private-source", "private-mentor");
    assertThat(ExperienceInstructions.forContext(context)).contains("当前要求优先", "不证明科研结论正确", "依赖未完成");
  }
}
