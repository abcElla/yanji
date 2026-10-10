package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.Set;

/** Model interpretations are bounded annotations of authorized quotes, never new authority. */
public final class SemanticFeedback {
  private SemanticFeedback() {}

  public static boolean enabled(JsonNode context) {
    return context.at("/learning_policy/feedback_learning_enabled").asBoolean(false);
  }

  public static ArrayNode accept(JsonNode context, JsonNode supplied) {
    var accepted = JsonNodeFactory.instance.arrayNode();
    if (!enabled(context) || !supplied.isArray()) return accepted;
    for (JsonNode item : supplied) {
      if (accepted.size() >= 5) break;
      int index = item.path("memory_index").asInt(-1);
      JsonNode memory = context.path("feedback_memories").path(index);
      String quote = item.path("source_quote").asText();
      String source = memory.path("lesson").asText();
      if (index < 0 || quote.isBlank() || quote.length() > 400 || !source.contains(quote)) continue;
      if (!item.path("direct_request").isBoolean()) continue;
      String intent = item.path("intent").asText();
      if (!Set.of(
              "baseline",
              "ablation",
              "data_split",
              "specificity",
              "prerequisite",
              "evidence",
              "resources",
              "report_style",
              "general_guidance",
              "out_of_scope")
          .contains(intent)) continue;
      var row =
          accepted
              .addObject()
              .put("memory_index", index)
              .put("source_quote", quote)
              .put("direct_request", item.path("direct_request").asBoolean())
              .put("intent", intent);
      for (String field : new String[] {"target", "requested_change", "applicable_scenario"}) {
        String value = item.path(field).asText();
        row.put(field, value.substring(0, Math.min(value.length(), 400)));
      }
      row.put("reuse_scope", memory.path("reuse_scope").asText("project_local"));
      row.put("interpretation_is_confirmed", false);
    }
    return accepted;
  }

  public static void attach(ObjectNode context, JsonNode supplied) {
    context.set("feedback_interpretations", accept(context, supplied));
  }
}
