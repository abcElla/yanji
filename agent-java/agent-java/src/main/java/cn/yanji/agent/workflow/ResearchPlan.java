package cn.yanji.agent.workflow;

import java.util.List;

public record ResearchPlan(
    String goal,
    List<Query> queries,
    @com.fasterxml.jackson.annotation.JsonProperty("feedback_interpretations")
        com.fasterxml.jackson.databind.JsonNode feedbackInterpretations) {
  public ResearchPlan(String goal, List<Query> queries) {
    this(goal, queries, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
  }

  public ResearchPlan {
    queries = List.copyOf(queries);
    feedbackInterpretations =
        feedbackInterpretations == null || feedbackInterpretations.isMissingNode()
            ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
            : feedbackInterpretations.deepCopy();
  }

  public record Query(String query, String reason) {}
}
