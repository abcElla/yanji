package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Task-graph preconditions are business constraints; this does not judge scientific truth. */
public final class ConstraintAwarePlanning {
  private ConstraintAwarePlanning() {}

  public static boolean enabled(JsonNode context) {
    return context.at("/learning_policy/constrained_reasoning_enabled").asBoolean(false);
  }

  private static String normalized(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：]+", "");
  }

  private static LinkedHashMap<String, JsonNode> tasks(JsonNode context) {
    var tasks = new LinkedHashMap<String, JsonNode>();
    for (JsonNode task : context.path("tasks"))
      if (!task.path("id").asText().isBlank()) tasks.put(task.path("id").asText(), task);
    return tasks;
  }

  public static ObjectNode prepare(ObjectNode supplied) {
    var context = supplied.deepCopy();
    if (!enabled(context)) return context;
    var graph = tasks(context);
    String requirements =
        context.path("stage_rules").toString()
            + " "
            + context.path("mentor_constraints").toString();
    // Only a narrow, explicit ordering phrase is compiled; other prose stays a soft instruction.
    if (requirements.matches("(?is).*先.{0,35}(基线|baseline).{0,35}再.{0,35}(改进|improv).*")
        && !requirements.matches("(?is).*(不要|无需|不必)先.{0,35}(基线|baseline).*")) {
      var baselines =
          graph.values().stream()
              .filter(task -> task.path("title").asText().matches("(?is).*(基线|baseline).*"))
              .toList();
      for (JsonNode task : graph.values())
        if (task.path("title").asText().matches("(?is).*(改进|improv).*")) {
          ArrayNode dependencies = ((ObjectNode) task).withArray("/dependency_ids");
          for (JsonNode baseline : baselines) {
            boolean present = false;
            for (JsonNode dep : dependencies)
              if (dep.asText().equals(baseline.path("id").asText())) present = true;
            if (!present && !task.path("id").equals(baseline.path("id")))
              dependencies.add(baseline.path("id").asText());
          }
        }
    }
    var completed = new HashSet<String>();
    graph.forEach(
        (id, task) -> {
          if (task.path("status").asText().equals("completed")) completed.add(id);
        });
    var known = new HashSet<>(completed);
    var remaining = new LinkedHashSet<>(graph.keySet());
    remaining.removeAll(completed);
    var constraints = context.withObject("/reasoning_constraints");
    constraints.put(
        "structured_planning_enabled",
        context.at("/learning_policy/structured_planning_enabled").asBoolean(false));
    var order = constraints.putArray("dependency_order");
    var ready = constraints.putArray("ready_tasks");
    for (var entry : graph.entrySet()) {
      if (completed.contains(entry.getKey())) continue;
      boolean executable = true;
      for (JsonNode dep : entry.getValue().path("dependency_ids"))
        if (!completed.contains(dep.asText())) executable = false;
      if (executable)
        ready
            .addObject()
            .put("task_id", entry.getKey())
            .put("title", entry.getValue().path("title").asText());
    }
    boolean progress = true;
    while (progress && !remaining.isEmpty()) {
      progress = false;
      for (String id : new ArrayList<>(remaining)) {
        boolean satisfied = true;
        for (JsonNode dep : graph.get(id).path("dependency_ids"))
          if (!known.contains(dep.asText())) satisfied = false;
        if (satisfied) {
          order.addObject().put("task_id", id).put("title", graph.get(id).path("title").asText());
          known.add(id);
          remaining.remove(id);
          progress = true;
        }
      }
    }
    var unresolved = constraints.putArray("unresolved_tasks");
    for (String id : remaining)
      unresolved
          .addObject()
          .put("task_id", id)
          .put("title", graph.get(id).path("title").asText())
          .put("reason", "依赖形成循环或前置任务不在当前快照，需核对");
    constraints
        .put("solver", "topological-preconditions-v1")
        .put("max_next_steps", 5)
        .put("max_model_calls", 2)
        .put("require_confirmation", true);
    return context;
  }

  public static List<String> violations(JsonNode context, JsonNode draft) {
    if (!enabled(context) || !context.path("kind").asText().equals("research_followup"))
      return List.of();
    var graph = tasks(context);
    var satisfied = new HashSet<String>();
    graph.forEach(
        (id, task) -> {
          if (task.path("status").asText().equals("completed")) satisfied.add(id);
        });
    var errors = new LinkedHashSet<String>();
    boolean structured =
        context.at("/learning_policy/structured_planning_enabled").asBoolean(false);
    var stepIds = new HashSet<String>();
    var taskBindings = new HashMap<String, String>();
    JsonNode steps = draft.at("/result/next_steps");
    if (!steps.isArray()) steps = draft.path("next_steps");
    if (steps.size() > 5) errors.add("STEP_BUDGET_EXCEEDED");
    for (JsonNode step : steps) {
      String id = step.path("existing_task_id").asText("");
      String stepId = step.path("step_id").asText();
      String mode = step.path("task_mode").asText();
      if (structured) {
        if (!stepId.matches("[A-Za-z][A-Za-z0-9_-]{0,39}")
            || graph.containsKey(stepId)
            || !stepIds.add(stepId)) errors.add("INVALID_STEP_ID");
        if (!Set.of("existing", "new").contains(mode)) errors.add("TASK_ASSOCIATION_MISSING");
        if (mode.equals("existing") && id.isBlank()) errors.add("TASK_ASSOCIATION_MISSING");
        if (mode.equals("new") && !id.isBlank()) errors.add("TASK_ASSOCIATION_CONFLICT");
        if (!step.path("depends_on").isArray()) errors.add("DEPENDENCY_BINDING_MISSING");
        for (JsonNode dependency : step.path("depends_on"))
          if (!satisfied.contains(dependency.asText())) errors.add("DEPENDENCY_PRECONDITION_UNMET");
      }
      if (!id.isBlank() && !graph.containsKey(id)) {
        errors.add("UNKNOWN_TASK");
        continue;
      }
      if (id.isBlank())
        for (var entry : graph.entrySet())
          if (!normalized(entry.getValue().path("title").asText()).isBlank()
              && normalized(entry.getValue().path("title").asText())
                  .equals(normalized(step.path("title").asText()))) {
            id = entry.getKey();
            break;
          }
      if (id.isBlank()) {
        if (structured && mode.equals("new") && !stepId.isBlank()) satisfied.add(stepId);
        continue; // New-task semantics still require human confirmation.
      }
      if (structured && mode.equals("new")) errors.add("TASK_ASSOCIATION_CONFLICT");
      var task = graph.get(id);
      if (task.path("status").asText().equals("completed")) errors.add("TASK_ALREADY_COMPLETED");
      else if (task.path("status").asText().equals("cancelled")) errors.add("TASK_CANCELLED");
      else {
        boolean ready = true;
        for (JsonNode dependency : task.path("dependency_ids")) {
          if (!satisfied.contains(dependency.asText())) ready = false;
          if (structured) {
            boolean declared = false;
            for (JsonNode declaredDep : step.path("depends_on"))
              if (dependency
                  .asText()
                  .equals(taskBindings.getOrDefault(declaredDep.asText(), declaredDep.asText())))
                declared = true;
            if (!declared) errors.add("DEPENDENCY_BINDING_MISSING");
          }
        }
        if (!ready) errors.add("DEPENDENCY_PRECONDITION_UNMET");
        else {
          satisfied.add(id);
          if (structured && !stepId.isBlank()) {
            satisfied.add(stepId);
            taskBindings.put(stepId, id);
          }
        }
      }
    }
    return List.copyOf(errors);
  }

  public static ObjectNode repairContext(ObjectNode context, List<String> violations) {
    var corrected = context.deepCopy();
    corrected.put(
        "generation_feedback",
        "计划的任务约束检查未通过："
            + String.join(",", violations)
            + "。重新生成完整JSON。不得重复已完成任务；先安排尚未完成的前置任务，再安排依赖任务。无法满足的依赖请说明缺项。不能更改权限、事实或任务状态。");
    return corrected;
  }

  public static ObjectNode fallback(ObjectNode context, List<String> violations) {
    var draft = JsonNodeFactory.instance.objectNode();
    var result = draft.putObject("result");
    result.put("outcome", "needs_input").put("summary", "当前计划的前置条件尚未满足，请核对任务依赖或补充任务状态后再安排。");
    result.putArray("claims");
    result.putArray("next_steps");
    result.putArray("suggested_skills");
    result.putArray("missing_inputs").add("可执行的任务顺序与最新前置任务状态");
    result.putArray("limitations").add("本次仅检查明确的任务依赖和完成状态，不评价科研结论正确性。");
    if (!context.at("/reasoning_constraints/unresolved_tasks").isEmpty()) {
      var names = new ArrayList<String>();
      for (var task : context.at("/reasoning_constraints/unresolved_tasks")) {
        if (names.size() >= 5) break;
        names.add(task.path("title").asText());
      }
      result.put("summary", "任务依赖可能形成循环或缺少前置任务：" + String.join("、", names) + "。请先与导师核对顺序，再推进这些任务。");
      var step = ((ArrayNode) result.path("next_steps")).addObject();
      step.put("step_id", "clarify_dependencies")
          .put("task_mode", "new")
          .putNull("existing_task_id");
      step.putArray("depends_on");
      step.put("title", "与导师核对任务依赖")
          .put("type", "other")
          .put(
              "description", "核对“" + String.join("、", names) + "”的前置关系，明确起始步骤或补充缺失前置；澄清前不执行被阻塞任务。");
      step.putArray("acceptance_criteria").add("获得导师对前置顺序或缺失任务状态的明确反馈，再更新原任务依赖。");
    }
    draft.putArray("proposal_drafts");
    return draft;
  }
}
