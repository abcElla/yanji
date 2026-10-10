package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResearchEvaluationAcceptanceTest {
  @Test
  void failedSufficientCaseCannotPassEvaluation() {
    var rows = Fixtures.JSON.createArrayNode();
    rows.add(
        Fixtures.object("{\"category\":\"sufficient\",\"outcome\":\"insufficient_evidence\"}"));
    assertThatThrownBy(() -> LiveResearchEvaluationTest.validateOutcomes(rows))
        .isInstanceOf(AssertionError.class);
    rows.removeAll();
    rows.add(
        Fixtures.object(
            "{\"category\":\"sufficient\",\"outcome\":\"ok\",\"planning_calls\":1,\"generation_calls\":1}"));
    assertThatCode(() -> LiveResearchEvaluationTest.validateOutcomes(rows))
        .doesNotThrowAnyException();
  }
}
