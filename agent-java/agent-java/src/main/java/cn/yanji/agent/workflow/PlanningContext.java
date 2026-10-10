package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;

/** A small allowlist projection; identity, leases, source IDs and internal URLs are not copied. */
public final class PlanningContext {
  private PlanningContext() {}

  public static ObjectNode build(ObjectNode context, ObjectMapper json) {
    ObjectNode out = json.createObjectNode().put("kind", context.path("kind").asText());
    out.put("question", shortText(context.at("/input/question").asText(), 500));
    ObjectNode stage = out.putObject("stage");
    for (String key : List.of("title", "goal"))
      if (context.path("stage").path(key).isTextual())
        stage.put(key, shortText(context.path("stage").path(key).asText(), 500));
    out.set("stage_rules", rules(context.path("stage_rules"), json, 0));
    for (String group : List.of("confirmed_experiments", "confirmed_notes", "tasks")) {
      ArrayNode rows = out.putArray(group);
      int count = 0;
      for (JsonNode row : context.path(group)) {
        if (count++ >= 6) break;
        ObjectNode summary = rows.addObject();
        for (String field :
            List.of("title", "status", "purpose", "results", "claims", "confirmed_at"))
          if (row.has(field)) summary.set(field, rules(row.get(field), json, 0));
      }
    }
    ObjectNode policy = out.putObject("learning_policy");
    for (String flag : List.of("feedback_learning_enabled", "constrained_reasoning_enabled"))
      policy.put(flag, context.path("learning_policy").path(flag).asBoolean(false));
    out.set("mentor_constraints", rules(context.path("mentor_constraints"), json, 0));
    if (context.at("/learning_policy/feedback_learning_enabled").asBoolean(false))
      out.set("feedback_memories", rules(context.path("feedback_memories"), json, 0));
    if (context.at("/learning_policy/constrained_reasoning_enabled").asBoolean(false))
      out.putObject("reasoning_constraints")
          .put("require_confirmation", true)
          .put("experiences_are_soft_preferences", true);
    if (context.at("/learning_policy/constrained_reasoning_enabled").asBoolean(false)) {
      ObjectNode constraints = (ObjectNode) out.path("reasoning_constraints");
      for (String key : List.of("dependency_order", "ready_tasks", "unresolved_tasks")) {
        var rows = constraints.putArray(key);
        int count = 0;
        for (JsonNode row : context.path("reasoning_constraints").path(key)) {
          if (count++ >= 6) break;
          rows.addObject().put("title", shortText(row.path("title").asText(), 200));
        }
      }
    }
    return out;
  }

  private static JsonNode rules(JsonNode node, ObjectMapper json, int depth) {
    if (depth > 3) return json.getNodeFactory().textNode("摘要略");
    if (node.isTextual()) return json.getNodeFactory().textNode(shortText(node.asText(), 400));
    if (node.isArray()) {
      ArrayNode array = json.createArrayNode();
      int count = 0;
      for (JsonNode item : node) {
        if (count++ >= 6) break;
        array.add(rules(item, json, depth + 1));
      }
      return array;
    }
    if (node.isObject()) {
      ObjectNode object = json.createObjectNode();
      for (String field :
          List.of(
              "text",
              "rule",
              "description",
              "required",
              "requirements",
              "acceptance_criteria",
              "rules",
              "minimum_repeats",
              "data_split",
              "content",
              "lesson",
              "topic",
              "origin_kind",
              "observations",
              "state",
              "completed_count",
              "accepted_tasks",
              "adoption_is_correctness",
              "category",
              "applicable_scenario",
              "improvement_request",
              "reuse_scope",
              "polarity",
              "explicit_reason",
              "reason_known",
              "generalization_requires_confirmation"))
        if (node.has(field)) object.set(field, rules(node.get(field), json, depth + 1));
      return object;
    }
    if (node.isNumber() || node.isBoolean()) return node.deepCopy();
    return json.nullNode();
  }

  private static String shortText(String text, int limit) {
    return text.substring(0, Math.min(limit, text.length()));
  }
}
