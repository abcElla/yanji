package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;

public final class ResearchPlanGuard {
  public ResearchPlan parse(JsonNode raw) {
    require(
        raw != null
            && raw.isObject()
            && only(raw, java.util.Set.of("goal", "queries", "feedback_interpretations")));
    require(
        !raw.has("feedback_interpretations")
            || (raw.path("feedback_interpretations").isArray()
                && raw.path("feedback_interpretations").size() <= 5));
    String goal = text(raw.path("goal"), 500);
    var queries = raw.path("queries");
    require(queries.isArray() && queries.size() <= 2);
    var selected = new java.util.ArrayList<ResearchPlan.Query>();
    var seen = new java.util.HashSet<String>();
    for (JsonNode row : queries) {
      require(row.isObject() && only(row, java.util.Set.of("query", "reason")));
      String query = text(row.path("query"), 120).replaceAll("\\s+", " ").trim();
      String reason = text(row.path("reason"), 200).trim();
      require(
          !java.util.regex.Pattern.compile(
                  "(?i)(https?://|jdbc:|\\b(select|insert|delete|update|drop|approve)\\b)")
              .matcher(query)
              .find());
      if (!query.isBlank() && seen.add(query.toLowerCase(java.util.Locale.ROOT)))
        selected.add(new ResearchPlan.Query(query, reason));
    }
    return new ResearchPlan(goal.trim(), selected, raw.path("feedback_interpretations"));
  }

  private static String text(JsonNode node, int max) {
    require(node.isTextual() && node.asText().length() <= max);
    return node.asText();
  }

  private static boolean only(JsonNode node, java.util.Set<String> fields) {
    var names = node.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) return false;
    return true;
  }

  private static void require(boolean condition) {
    if (!condition)
      throw new cn.yanji.agent.contract.AgentFailure(
          "PLANNER_OUTPUT_INVALID", "规划输出不符合受限查询协议", false);
  }
}
