package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EvidenceCitationsTest {
  @Test
  void legacyParameterJsonOffersExactValueExcerptsWithoutJsonWrappers() {
    var evidence = Fixtures.evidence();
    String original = "{\"运行环境与设置\":\"功率8 W。使用30天。\"}";
    var row = (com.fasterxml.jackson.databind.node.ObjectNode) evidence.get(0);
    row.put("text", original);
    row.set("locator", Fixtures.object("{\"kind\":\"record_field\",\"field\":\"parameters\"}"));
    var choices = EvidenceCitations.catalog(evidence);
    assertThat(choices.get(0).path("quote").asText()).isEqualTo("功率8 W。");
    for (var choice : choices) {
      assertThat(original).contains(choice.path("quote").asText());
      assertThat(choice.path("quote").asText()).doesNotContain("{", "\"运行环境与设置\"");
    }
  }

  @Test
  void shortSentencesRemainWholeAndExact() {
    var evidence = Fixtures.evidence();
    String text =
        "Research question: does y=2*x hold for the test fixture? Method: use inputs 1, 2, 3 and record outputs 2, 4, 6. Result: synthetic data only, not scientific evidence.";
    ((ObjectNode) evidence.get(0)).put("text", text);
    var catalog = EvidenceCitations.catalog(evidence);
    assertThat(catalog.get(0).path("quote").asText())
        .isEqualTo("Research question: does y=2*x hold for the test fixture?");
    assertThat(catalog.get(1).path("quote").asText())
        .isEqualTo(" Method: use inputs 1, 2, 3 and record outputs 2, 4, 6.");
    for (var choice : catalog) assertThat(text).contains(choice.path("quote").asText());
  }

  @Test
  void repeatedSelectionsPublishOneQuotedFact() {
    var output = Fixtures.answer();
    ((ObjectNode) output.at("/result/claims/0"))
        .putArray("source_refs")
        .add(Fixtures.object("{\"citation_id\":\"c1_1\"}"));
    output.withObject("/result").withArray("claims").add(output.at("/result/claims/0").deepCopy());
    EvidenceCitations.expand(output, Fixtures.context("paper_guide"), Fixtures.evidence());
    assertThat(output.at("/result/claims")).hasSize(1);
  }

  @Test
  void malformedCompactProposalIsValidationFailure() {
    var output = Fixtures.answer();
    ((ObjectNode) output.at("/result/claims/0"))
        .putArray("source_refs")
        .add(Fixtures.object("{\"citation_id\":\"c1_1\"}"));
    output
        .withArray("proposal_drafts")
        .add(Fixtures.object("{\"action_type\":\"reading_note.draft\",\"payload\":1}"));
    assertThatThrownBy(
            () ->
                EvidenceCitations.expand(
                    output, Fixtures.context("paper_guide"), Fixtures.evidence()))
        .isInstanceOf(AgentFailure.class);
  }
}
