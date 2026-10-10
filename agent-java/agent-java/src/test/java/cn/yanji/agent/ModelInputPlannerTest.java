package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.model.ModelInputPlanner;
import org.junit.jupiter.api.Test;

class ModelInputPlannerTest {
  @Test
  void riskInputProvidesLiteralFutureDeadlineChoicesFromObservationTime() {
    var context = Fixtures.context("risk_brief");
    context.withObject("/input").put("horizon_days", 7);
    var risks =
        Fixtures.JSON
            .createArrayNode()
            .add(
                Fixtures.object("{\"observed_at\":\"2026-10-08T08:00:00Z\",\"task_id\":\"task\"}"));
    var input = ModelInputPlanner.prepare(context, Fixtures.evidence(), risks);
    assertThat(input.at("/context/reschedule_window/earliest_example").asText())
        .isEqualTo("2026-10-09T08:00Z");
    assertThat(input.at("/context/reschedule_window/latest_example").asText())
        .isEqualTo("2026-10-14T08:00Z");
    assertThat(context.has("reschedule_window")).isFalse();
  }

  @Test
  void researchAnswerUsesTheSameAuthorizedCitationCatalogAsPaperGuide() {
    var input =
        ModelInputPlanner.prepare(
            Fixtures.context("research_qa"), Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.path("citation_catalog")).isNotEmpty();
    assertThat(input.at("/citation_catalog/0/source_id").asText()).isEqualTo(Fixtures.SOURCE);
  }

  @Test
  void paperInputOffersShortExactCitationChoicesWithoutChangingEvidence() {
    var evidence = Fixtures.evidence();
    String original = "QA 第一行\n第二行😀。".repeat(45);
    ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.get(0)).put("text", original);
    var input =
        ModelInputPlanner.prepare(
            Fixtures.context("paper_guide"), evidence, Fixtures.JSON.createArrayNode());
    assertThat(input.path("citation_catalog").isArray()).isTrue();
    assertThat(input.path("citation_catalog")).isNotEmpty();
    for (var choice : input.path("citation_catalog")) {
      assertThat(choice.path("quote").asText()).isNotBlank().hasSizeLessThanOrEqualTo(180);
      assertThat(original).contains(choice.path("quote").asText());
      assertThat(choice.path("citation_id").asText()).isNotBlank();
    }
    assertThat(input.path("evidence")).isEqualTo(evidence);
  }

  @Test
  void independentPaperGuideMakesNullTaskExplicitAndExcludesUnrelatedTasks() {
    var context = Fixtures.context("paper_guide");
    context.withArray("tasks").add(Fixtures.object("{\"id\":\"unrelated-task\"}"));
    var input =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.at("/context/subject/task_id").isNull()).isTrue();
    assertThat(input.at("/context/tasks")).isEmpty();
    assertThat(context.at("/subject/task_id").isMissingNode()).isTrue();
    assertThat(context.path("tasks")).hasSize(1);
    context.withObject("/subject").put("task_id", "related-task");
    context.withArray("tasks").add(Fixtures.object("{\"id\":\"related-task\"}"));
    var related =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(related.at("/context/tasks")).hasSize(1);
    assertThat(related.at("/context/tasks/0/id").asText()).isEqualTo("related-task");
  }

  @Test
  void differentSourceRevisionMustNotDeduplicateBody() {
    var context = Fixtures.context("weekly_report");
    var row =
        Fixtures.JSON
            .createObjectNode()
            .put("source_id", Fixtures.SOURCE)
            .put("source_revision", 2)
            .put("results", Fixtures.evidence().get(0).path("text").asText());
    context.withArray("confirmed_experiments").add(row);
    var input =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.at("/context/confirmed_experiments/0/results")).isEqualTo(row.path("results"));
  }

  @Test
  void otherKindsRetainContextAndDoNotMutateCallerData() {
    var context = Fixtures.context("research_qa");
    var input =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.path("context")).isEqualTo(context);
    ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("context")).put("kind", "changed");
    assertThat(context.path("kind").asText()).isEqualTo("research_qa");
  }

  @Test
  void weeklyDropsDuplicateBodyButPreservesEveryEvidenceByteAndAnchor() {
    var context = Fixtures.context("weekly_report");
    context.withArray("tasks").add(Fixtures.object("{\"id\":\"irrelevant\"}"));
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"id\":\"record\",\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1,\"confirmed_at\":\"2026-10-05T00:00:00Z\",\"results\":\"实验结果为10，尚未重复验证。\",\"reflection\":\"未检索到的反思\"}"));
    var input =
        ModelInputPlanner.prepare(context, Fixtures.evidence(), Fixtures.JSON.createArrayNode());
    assertThat(input.path("evidence")).isEqualTo(Fixtures.evidence());
    assertThat(input.at("/context/confirmed_experiments/0/source_revision").asInt()).isEqualTo(1);
    assertThat(input.at("/context/confirmed_experiments/0/reflection").asText())
        .isEqualTo("未检索到的反思");
    assertThat(input.at("/context/confirmed_experiments/0/results").isMissingNode()).isTrue();
    assertThat(input.at("/context/tasks").isMissingNode()).isTrue();
    assertThat(context.path("tasks")).hasSize(1);
  }
}
