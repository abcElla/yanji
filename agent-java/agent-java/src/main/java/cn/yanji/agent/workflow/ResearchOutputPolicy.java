package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.node.*;

public final class ResearchOutputPolicy {
  private ResearchOutputPolicy() {}

  public static boolean rulesLocated(ObjectNode context, ArrayNode evidence) {
    var rules = context.path("stage_rules");
    var source = context.path("stage_rule_source");
    if (rules.isObject() && !rules.has("source_id") && source.isObject()) {
      var locatedRules = ((ObjectNode) rules).deepCopy();
      for (String field : java.util.List.of("source_id", "source_revision", "locator"))
        if (source.has(field)) locatedRules.set(field, source.get(field));
      return ruleLocated(locatedRules, evidence);
    }
    return ruleLocated(rules, evidence);
  }

  private static boolean ruleLocated(
      com.fasterxml.jackson.databind.JsonNode rule, ArrayNode evidence) {
    if (rule.isArray()) {
      if (rule.isEmpty()) return false;
      for (var row : rule) if (!ruleLocated(row, evidence)) return false;
      return true;
    }
    if (!rule.isObject()) return false;
    if (rule.has("source_id") && rule.path("source_revision").asInt() > 0) {
      var statements = new java.util.ArrayList<String>();
      for (String field :
          java.util.List.of(
              "text", "description", "rule", "required", "requirements", "acceptance_criteria"))
        collectStatements(rule.path(field), statements);
      if (statements.isEmpty()) return false;
      var located = new java.util.HashSet<String>();
      for (var chunk : evidence)
        if (chunk.path("source_id").equals(rule.path("source_id"))
            && chunk.path("source_revision").equals(rule.path("source_revision"))
            && (!rule.has("locator") || rule.path("locator").equals(chunk.path("locator")))) {
          String text = normalize(chunk.path("text").asText());
          for (String statement : statements)
            if (text.contains(normalize(statement))) located.add(statement);
        }
      return located.containsAll(statements);
    }
    return rule.has("rules") && ruleLocated(rule.path("rules"), evidence);
  }

  public static void validate(ObjectNode result, ObjectNode context) {
    require(result.at("/result/claims").size() <= 5, "一次最多整理5条有材料支持的事实，请减少事实条目");
    int proposed = 0;
    var created = new java.util.HashSet<String>();
    for (var proposal : result.path("proposal_drafts")) {
      if ("research_qa".equals(context.path("kind").asText())) require(false);
      if ("task.create_batch".equals(proposal.path("action_type").asText())) {
        proposed += proposal.at("/payload/tasks").size();
        for (var task : proposal.at("/payload/tasks")) {
          require(
              created.add(
                  task.path("stage_id").asText()
                      + "\u0000"
                      + normalize(task.path("title").asText())));
          for (var existing : context.path("tasks"))
            if (existing.path("stage_id").equals(task.path("stage_id"))
                && normalize(existing.path("title").asText())
                    .equals(normalize(task.path("title").asText()))) require(false);
        }
      } else if ("task.reschedule_batch".equals(proposal.path("action_type").asText()))
        proposed += proposal.at("/payload/items").size();
      else require(false);
    }
    require(proposed <= 3, "一次最多安排3项任务，请分步规划");
  }

  private static void collectStatements(
      com.fasterxml.jackson.databind.JsonNode value, java.util.List<String> statements) {
    if (value.isTextual() && !value.asText().isBlank()) statements.add(value.asText());
    else if (value.isArray()) for (var item : value) collectStatements(item, statements);
  }

  private static String normalize(String text) {
    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        .replaceAll("\\s+", " ")
        .trim()
        .toLowerCase(java.util.Locale.ROOT);
  }

  private static void require(boolean condition) {
    require(condition, "建议存在重复任务或超出当前问题的操作，请重新整理");
  }

  private static void require(boolean condition, String message) {
    if (!condition)
      throw new cn.yanji.agent.contract.AgentFailure("MODEL_OUTPUT_INVALID", message, false);
  }
}
