package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Checks exact provenance and known UI names; this is not general semantic entailment. */
public final class GroundedGuidance {
  private GroundedGuidance() {}

  public static boolean enabled(JsonNode context) {
    return context.at("/learning_policy/grounded_guidance_enabled").asBoolean(false)
        && context.path("kind").asText().equals("research_followup");
  }

  public static ObjectNode prepare(ObjectNode context, ArrayNode evidence) {
    var out = context.deepCopy();
    if (!enabled(context)) return out;
    var anchors = out.putArray("fact_anchors");
    for (JsonNode task : context.path("tasks")) {
      String state =
          switch (task.path("status").asText()) {
            case "todo" -> "待开始";
            case "in_progress" -> "进行中";
            case "completed" -> "已完成";
            case "blocked" -> "等待前置任务";
            case "cancelled" -> "已取消";
            default -> "状态未确认";
          };
      add(anchors, "task", task.path("title").asText() + "：" + state);
    }
    for (String group : List.of("confirmed_experiments", "confirmed_notes"))
      for (JsonNode record : context.path(group))
        for (String field : List.of("title", "purpose", "results", "content", "claims"))
          if (record.path(field).isTextual()) add(anchors, "record", record.path(field).asText());
    for (JsonNode mentor : context.path("mentor_constraints"))
      add(anchors, "mentor", mentor.path("content").asText());
    for (JsonNode item : evidence) add(anchors, "evidence", item.path("text").asText());
    return out;
  }

  private static void add(ArrayNode anchors, String kind, String text) {
    if (!text.isBlank() && anchors.size() < 40) {
      String id = "f" + anchors.size();
      anchors
          .addObject()
          .put("anchor_id", id)
          .put("kind", kind)
          .put("text", text.substring(0, Math.min(text.length(), 1200)));
    }
  }

  public static List<String> violations(JsonNode context, JsonNode draft) {
    if (!enabled(context)) return List.of();
    var errors = new LinkedHashSet<String>();
    for (JsonNode claim : draft.at("/result/claims")) {
      String quote = claim.path("text").asText();
      String id = claim.path("anchor_id").asText();
      boolean supported = false;
      for (JsonNode anchor : context.path("fact_anchors"))
        if (id.equals(anchor.path("anchor_id").asText())
            && !quote.isBlank()
            && anchor.path("text").asText().contains(quote)) supported = true;
      if (!supported) errors.add("UNSUPPORTED_FACT");
    }
    // Names inside UI brackets must come from the actual catalog, not model memory.
    var visibleResult = draft.path("result").deepCopy();
    if (visibleResult instanceof ObjectNode object)
      object.remove(List.of("feedback_interpretations", "claims"));
    String visible = visibleResult.toString();
    var matcher = java.util.regex.Pattern.compile("【([^【】]{1,80})】").matcher(visible);
    while (matcher.find()) {
      String name = matcher.group(1);
      boolean known = false;
      for (JsonNode skill : context.path("skill_catalog"))
        if (name.equals(skill.path("title").asText())
            || name.equals(skill.path("name").asText())
            || name.equals(skill.path("label").asText())) known = true;
      if (!known) errors.add("UNKNOWN_UI_FUNCTION");
    }
    for (JsonNode skill : draft.at("/result/suggested_skills")) {
      boolean known = false;
      for (JsonNode available : context.path("skill_catalog"))
        if (skill.path("id").equals(available.path("id"))) known = true;
      if (!known) errors.add("UNKNOWN_SKILL");
    }
    return List.copyOf(errors);
  }

  public static ObjectNode fallback(ObjectNode context, List<String> errors) {
    var out = ConstraintAwarePlanning.fallback(context, errors);
    if (context.at("/reasoning_constraints/unresolved_tasks").isEmpty())
      ((ObjectNode) out.path("result")).put("summary", "建议的依据或任务关联尚未通过核对，请补充材料或确认前置条件后再规划。");
    ((ArrayNode) out.at("/result/limitations")).add("未发布无法核对的事实或软件功能；检查不代表科学结论已得到证明。");
    return out;
  }

  /** Removes unsupported UI directions without inventing replacement routes. */
  public static ObjectNode publish(ObjectNode context, ObjectNode draft) {
    var out = draft.deepCopy();
    if (!enabled(context) || !(out.path("result") instanceof ObjectNode result)) return out;
    if (context.path("advice_mode").asBoolean(false)) {
      var skills = result.putArray("suggested_skills");
      for (JsonNode selected : draft.at("/result/suggested_skills"))
        for (JsonNode available : context.path("skill_catalog"))
          if (selected.path("id").equals(available.path("id"))) {
            skills.add(selected.deepCopy());
            break;
          }
      // A UI-name mismatch must not erase the actual research explanation.
      return out;
    }
    boolean changed = false;
    var skills = result.putArray("suggested_skills");
    for (JsonNode selected : draft.at("/result/suggested_skills")) {
      boolean known = false;
      for (JsonNode available : context.path("skill_catalog"))
        if (selected.path("id").equals(available.path("id"))) known = true;
      if (known) skills.add(selected.deepCopy());
      else changed = true;
    }
    if (unknownUi(context, result.path("summary").asText())) {
      result.put("summary", "以下是针对当前问题的待确认研究步骤；具体软件填写位置尚需核对。");
      changed = true;
    }
    for (String field : List.of("missing_inputs", "limitations"))
      if (result.path(field) instanceof ArrayNode array)
        for (int i = 0; i < array.size(); i++)
          if (unknownUi(context, array.get(i).asText())) {
            array.set(i, TextNode.valueOf("请补充与当前任务相关的材料，具体填写位置尚需核对。"));
            changed = true;
          }
    for (JsonNode raw : result.path("next_steps"))
      if (raw instanceof ObjectNode step) {
        if (unknownUi(context, step.path("title").asText())) {
          step.put("title", "核对研究材料与执行方式");
          changed = true;
        }
        if (unknownUi(context, step.path("description").asText())) {
          step.put("description", step.path("title").asText() + "。请整理相关材料，并与导师确认实施方式和记录位置。");
          changed = true;
        }
        if (step.path("acceptance_criteria") instanceof ArrayNode criteria)
          for (int i = 0; i < criteria.size(); i++)
            if (unknownUi(context, criteria.get(i).asText())) {
              criteria.set(i, TextNode.valueOf("形成可供导师核对的材料或记录。"));
              changed = true;
            }
      }
    if (changed) result.withArray("/limitations").add("部分功能推荐或填写位置无法核对，已移除或改为待确认；请以实际软件功能为准。");
    return out;
  }

  private static boolean unknownUi(JsonNode context, String text) {
    var matcher = java.util.regex.Pattern.compile("【([^【】]{1,80})】").matcher(text);
    while (matcher.find()) {
      boolean known = false;
      for (JsonNode skill : context.path("skill_catalog"))
        for (String field : List.of("title", "name", "label"))
          if (matcher.group(1).equals(skill.path(field).asText())) known = true;
      if (!known) return true;
    }
    return false;
  }
}
