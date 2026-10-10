package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.workflow.ResearchOutputPolicy;
import org.junit.jupiter.api.Test;

class ResearchOutputPolicyTest {
  @Test
  void deployedRuleRequirementsUseSeparateSourceMetadataAndRemainVisibleToPlanner() {
    var context = Fixtures.planningContext();
    context.set("stage_rules", Fixtures.object("{\"requirements\":[\"重复验证\"]}"));
    var source = Fixtures.reference();
    source.remove(java.util.List.of("quote", "chunk_id"));
    context.set("stage_rule_source", source);
    assertThat(
            cn.yanji.agent.workflow.PlanningContext.build(context, Fixtures.JSON)
                .at("/stage_rules/requirements/0")
                .asText())
        .isEqualTo("重复验证");
    assertThat(ResearchOutputPolicy.rulesLocated(context, Fixtures.evidence())).isTrue();
    source.put("source_revision", 2);
    assertThat(ResearchOutputPolicy.rulesLocated(context, Fixtures.evidence())).isFalse();
    source.put("source_revision", 1);
    source.withObject("/locator").put("page", 2);
    assertThat(ResearchOutputPolicy.rulesLocated(context, Fixtures.evidence())).isFalse();
  }

  @Test
  void unrelatedChunkCannotLocateRulesAndNewTasksCannotRepeat() {
    var context = Fixtures.planningContext();
    context.set(
        "stage_rules",
        Fixtures.object(
            "{\"source_id\":\""
                + Fixtures.SOURCE
                + "\",\"source_revision\":1,\"description\":\"必须完成三次重复实验\"}"));
    assertThat(ResearchOutputPolicy.rulesLocated(context, Fixtures.evidence())).isFalse();
    var raw = Fixtures.answer();
    raw.withArray("proposal_drafts").add(Fixtures.plan());
    var tasks =
        (com.fasterxml.jackson.databind.node.ArrayNode) raw.at("/proposal_drafts/0/payload/tasks");
    tasks.add(tasks.get(0).deepCopy());
    assertThatThrownBy(() -> ResearchOutputPolicy.validate(raw, context))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void duplicateExistingTaskAndFourTaskSuggestionsAreRejected() {
    var context = Fixtures.planningContext();
    var raw = Fixtures.answer();
    raw.withArray("proposal_drafts").add(Fixtures.plan());
    context
        .withArray("tasks")
        .add(Fixtures.JSON.createObjectNode().put("stage_id", Fixtures.STAGE).put("title", "验证实验"));
    assertThatThrownBy(() -> ResearchOutputPolicy.validate(raw, context))
        .isInstanceOf(AgentFailure.class);
    context.withArray("tasks").removeAll();
    var tasks =
        (com.fasterxml.jackson.databind.node.ArrayNode) raw.at("/proposal_drafts/0/payload/tasks");
    for (int i = 0; i < 3; i++) tasks.add(tasks.get(0).deepCopy());
    assertThatThrownBy(() -> ResearchOutputPolicy.validate(raw, context))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void fiveClaimsAndThreeTasksAreActualBoundaries() {
    var raw = Fixtures.answer();
    var claims = raw.withObject("/result").withArray("claims");
    for (int i = 0; i < 5; i++) claims.add(claims.get(0).deepCopy());
    assertThatThrownBy(() -> ResearchOutputPolicy.validate(raw, Fixtures.context("research_qa")))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void stageRuleMustHaveMatchingLocatedEvidence() {
    var context = Fixtures.planningContext();
    context.set(
        "stage_rules",
        Fixtures.object(
            "{\"source_id\":\""
                + Fixtures.SOURCE
                + "\",\"source_revision\":1,\"required\":[\"重复验证\"]}"));
    assertThat(ResearchOutputPolicy.rulesLocated(context, Fixtures.evidence())).isTrue();
    assertThat(ResearchOutputPolicy.rulesLocated(context, Fixtures.JSON.createArrayNode()))
        .isFalse();
  }
}
