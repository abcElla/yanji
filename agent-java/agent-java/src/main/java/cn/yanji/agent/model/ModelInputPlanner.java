package cn.yanji.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.OffsetDateTime;
import java.util.List;

/** Builds the provider input without changing the authorized backend snapshot. */
public final class ModelInputPlanner {
  private ModelInputPlanner() {}

  public static ObjectNode prepare(ObjectNode context, ArrayNode evidence, ArrayNode risks) {
    ObjectNode selected = context.deepCopy();
    boolean learning = context.at("/learning_policy/feedback_learning_enabled").asBoolean(false);
    boolean constrained =
        context.at("/learning_policy/constrained_reasoning_enabled").asBoolean(false);
    if (!learning)
      selected.remove(java.util.List.of("feedback_memories", "feedback_interpretations"));
    if (!constrained) selected.remove("reasoning_constraints");
    if ("risk_brief".equals(context.path("kind").asText())
        && risks.size() > 0
        && context.at("/input/report_id").asText().isBlank()) {
      try {
        OffsetDateTime observed = OffsetDateTime.parse(risks.get(0).path("observed_at").asText());
        int horizon = context.at("/input/horizon_days").asInt(7);
        if (horizon > 0 && horizon <= 30) {
          selected
              .putObject("reschedule_window")
              .put("observed_at", observed.toString())
              .put("earliest_example", observed.plusDays(1).toString())
              .put("latest_example", observed.plusDays(Math.max(1, horizon - 1)).toString());
        }
      } catch (RuntimeException ignored) {
        // Missing observations cannot justify inventing a deadline window.
      }
    }
    if ("paper_guide".equals(context.path("kind").asText())) {
      ObjectNode subject = selected.withObject("/subject");
      if (!subject.hasNonNull("task_id")) subject.putNull("task_id");
      ArrayNode tasks = selected.putArray("tasks");
      if (subject.hasNonNull("task_id"))
        for (JsonNode task : context.path("tasks"))
          if (task.path("id").equals(subject.path("task_id"))) tasks.add(task.deepCopy());
    }
    if ("weekly_report".equals(context.path("kind").asText())) {
      selected.removeAll();
      for (String key :
          List.of(
              "schema_version",
              "kind",
              "input",
              "allowed_actions",
              "existing_report_version",
              "record_coverage",
              "evidence_policy",
              "source_scope_truncated",
              "generation_feedback",
              "learning_policy",
              "mentor_constraints",
              "stage_rules",
              "reasoning_constraints",
              "feedback_memories",
              "feedback_interpretations"))
        if (context.has(key)) selected.set(key, context.get(key).deepCopy());
      if (context.path("advice_mode").asBoolean(false))
        for (String key : List.of("tasks", "stage"))
          if (context.has(key)) selected.set(key, context.get(key).deepCopy());
      if (selected.path("record_coverage") instanceof ObjectNode coverage)
        coverage.remove(
            "omitted_sources"); // Not supplemented this run; avoid a long ID list in model input.
      for (String key : List.of("confirmed_experiments", "confirmed_notes")) {
        ArrayNode rows = selected.putArray(key);
        for (JsonNode row : context.path(key)) {
          ObjectNode anchor = row.deepCopy();
          rows.add(anchor);
          boolean citable = false;
          for (JsonNode chunk : evidence)
            if (chunk.path("source_id").equals(row.path("source_id"))
                && chunk.path("source_revision").equals(row.path("source_revision")))
              citable = true;
          anchor.put("has_citable_evidence", citable);
          var fields = row.fields();
          while (fields.hasNext()) {
            var field = fields.next();
            if (List.of("id", "paper_id", "confirmed_at", "source_id", "source_revision")
                .contains(field.getKey())) continue;
            if (!field.getValue().isTextual() || field.getValue().asText().isBlank()) continue;
            for (JsonNode chunk : evidence) {
              if (chunk.path("source_id").equals(row.path("source_id"))
                  && chunk.path("source_revision").equals(row.path("source_revision"))
                  && chunk.path("text").asText().contains(field.getValue().asText())) {
                anchor.remove(field.getKey());
                break;
              }
            }
          }
        }
      }
    }
    if (!learning) selected.remove("feedback_memories");
    if (!constrained) selected.remove("reasoning_constraints");
    ObjectNode input = JsonNodeFactory.instance.objectNode();
    input.set("context", selected);
    input.set("evidence", evidence.deepCopy());
    if (List.of("paper_guide", "stage_plan", "risk_brief", "weekly_report", "research_qa")
        .contains(context.path("kind").asText()))
      input.set("citation_catalog", cn.yanji.agent.contract.EvidenceCitations.catalog(evidence));
    input.set("rule_risks", risks.deepCopy());
    return input;
  }
}
