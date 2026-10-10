package cn.yanji.agent.workflow;

import cn.yanji.agent.contract.AgentFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.List;
import java.util.Set;

/** Deterministic business boundaries run before spending tokens. */
public final class BusinessPolicy {
  public static final Set<String> KINDS =
      Set.of(
          "stage_plan",
          "paper_guide",
          "weekly_report",
          "research_qa",
          "risk_brief",
          "research_followup");
  private final ObjectMapper json;
  private final Clock clock;

  public BusinessPolicy(ObjectMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  public ObjectNode preflight(ObjectNode context) {
    return preflight(context, false);
  }

  public ObjectNode preflight(ObjectNode context, boolean adviceMode) {
    if (context.path("schema_version").asInt() != 2
        || !KINDS.contains(context.path("kind").asText()))
      throw new AgentFailure("CONTEXT_VERSION_UNSUPPORTED", "上下文版本或任务类型暂不支持", false);
    String kind = context.path("kind").asText();
    if (!adviceMode
        && kind.equals("stage_plan")
        && (!context.path("stage").isObject()
            || context.path("stage_rules").isMissingNode()
            || context.path("stage_rules").isNull()
            || context.path("stage_rules").isEmpty()))
      return fallback("needs_input", "阶段信息或培养规则不足，暂不能生成任务建议。", "当前阶段目标和导师确认的阶段规则");
    if (kind.equals("paper_guide") && !context.path("paper").isObject())
      return fallback("needs_input", "缺少当前文献上下文。", "已解析且授权的文献");
    if (kind.equals("research_qa") && context.at("/input/question").asText().isBlank())
      return fallback("needs_input", "请补充科研问题。", "问题内容");
    if (kind.equals("weekly_report")) {
      var result = weekly(context);
      if (adviceMode
          && result != null
          && result.at("/result/outcome").asText().equals("insufficient_evidence")) {
        context.put("advice_only", true);
        return null;
      }
      return result;
    }
    if (kind.equals("risk_brief")) {
      if (!context.at("/input/report_id").asText().isBlank()) {
        JsonNode report = context.path("weekly_report");
        if (!report.path("id").asText().equals(context.at("/input/report_id").asText())
            || !Set.of("submitted", "changes_requested").contains(report.path("status").asText())
            || report.path("source_id").asText().isBlank())
          return fallback("needs_input", "当前周报尚未提交或已发生变化，请重新打开后分析。", "已提交的当前周报");
        return null;
      }
      if (adviceMode) {
        context.put("advice_only", true);
        return null;
      }
      return noRisk(context);
    }
    return null;
  }

  private ObjectNode noRisk(ObjectNode context) {
    JsonNode tasks = context.path("tasks");
    if (!tasks.isArray()) return fallback("needs_input", "缺少有效任务快照，暂无法检查阻塞或逾期。", "当前授权任务列表");
    // A positive finding must keep its existing evidence-backed explanation path.
    if (!risks(context).isEmpty()) return null;
    boolean undated = false;
    for (JsonNode task : tasks) {
      if (!task.isObject()
          || !Set.of("todo", "in_progress", "blocked", "submitted", "completed")
              .contains(task.path("status").asText()))
        return fallback("needs_input", "任务状态缺失或暂不支持，不能确认是否存在阻塞。", "有效任务状态");
      if (Set.of("completed", "submitted").contains(task.path("status").asText())) continue;
      if (!task.has("due_at"))
        return fallback("needs_input", "部分任务缺少截止时间字段，暂无法完整判断逾期。", "任务截止时间，未设置时明确为空");
      if (task.path("due_at").isNull()) {
        undated = true;
        continue;
      }
      try {
        OffsetDateTime.parse(task.path("due_at").asText());
      } catch (RuntimeException e) {
        return fallback("needs_input", "部分任务截止时间格式无效或缺少时区，暂无法完整判断逾期。", "含时区的有效任务截止时间");
      }
    }
    ObjectNode draft = json.createObjectNode();
    ObjectNode result =
        draft
            .putObject("result")
            .put("outcome", "ok")
            .put("summary", "当前未检测到阻塞或逾期。检查范围为本次授权任务快照，共" + tasks.size() + "项任务；这不代表未来不会出现风险。");
    result.putArray("claims");
    result.putArray("missing_inputs");
    ArrayNode limitations = result.putArray("limitations");
    limitations.add("仅检查当前授权任务的阻塞状态和截止时间，不评价科研结论或预测研究成败。");
    if (undated) limitations.add("未设置截止时间的任务不进行逾期判断。");
    draft.putArray("proposal_drafts");
    return draft;
  }

  private ObjectNode weekly(ObjectNode context) {
    LocalDate week;
    try {
      week = LocalDate.parse(context.at("/input/week_start").asText());
      if (week.getDayOfWeek() != DayOfWeek.MONDAY) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      return fallback("needs_input", "周报需要有效的周一日期。", "week_start，格式YYYY-MM-DD且为周一");
    }
    Instant start = week.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    Instant end = week.plusWeeks(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    int count = 0;
    for (String key : List.of("confirmed_experiments", "confirmed_notes")) {
      ArrayNode selected = json.createArrayNode();
      for (JsonNode row : context.path(key)) {
        Instant confirmed;
        try {
          confirmed = OffsetDateTime.parse(row.path("confirmed_at").asText()).toInstant();
        } catch (RuntimeException e) {
          return fallback(
              "needs_input",
              "现有记录没有可核验的确认时间，不能归入本周。",
              "后端提供confirmed_at及来源source_id/source_revision");
        }
        if (row.path("source_id").asText().isBlank() || row.path("source_revision").asInt() < 1)
          return fallback("needs_input", "本周记录缺少可定位来源。", "已确认记录的来源ID及版本");
        if (!confirmed.isBefore(start) && confirmed.isBefore(end)) {
          selected.add(row);
          count++;
        }
      }
      context.set(key, selected);
    }
    if (count == 0)
      return fallback(
          "insufficient_evidence", "还没有找到本周已确认的实验记录或阅读笔记，暂时无法整理本周进展。", "本周已确认的实验记录或阅读笔记");
    return null;
  }

  public ArrayNode risks(ObjectNode context) {
    ArrayNode rules = json.createArrayNode();
    if (!context.path("kind").asText().equals("risk_brief")) return rules;
    for (JsonNode task : context.path("tasks")) {
      if (Set.of("completed", "submitted").contains(task.path("status").asText())) continue;
      if (task.path("status").asText().equals("blocked"))
        rules.add(
            json.createObjectNode()
                .put("rule", "TASK_BLOCKED")
                .put("task_id", task.path("id").asText()));
      try {
        if (OffsetDateTime.parse(task.path("due_at").asText())
            .toInstant()
            .isBefore(clock.instant()))
          rules.add(
              json.createObjectNode()
                  .put("rule", "TASK_OVERDUE")
                  .put("task_id", task.path("id").asText())
                  .put("due_at", task.path("due_at").asText()));
      } catch (RuntimeException ignored) {
        /* Missing or legacy naive dates cannot prove overdue. */
      }
    }
    for (JsonNode rule : rules) {
      ObjectNode observation = (ObjectNode) rule;
      observation.put("observed_at", clock.instant().toString());
      for (JsonNode task : context.path("tasks")) {
        if (task.path("id").asText().equals(rule.path("task_id").asText())) {
          observation.set("task_version", task.path("version"));
          observation.set("status", task.path("status"));
          observation.put("task_title", task.path("title").asText("未命名任务"));
          break;
        }
      }
    }
    return rules;
  }

  /** Rules describe pinned task state; they do not invent source-backed scientific claims. */
  public void retainRuleObservations(ObjectNode draft, ArrayNode risks) {
    if (risks.isEmpty()
        || !draft.path("result").isObject()
        || !draft.at("/result/summary").isTextual()) return;
    ObjectNode result = (ObjectNode) draft.path("result");
    // Do not repair invalid model output before the output guard can reject it.
    if (result.path("summary").asText().length() > 5000) return;
    StringBuilder summary = new StringBuilder("当前有" + risks.size() + "项任务风险需要关注：\n");
    int shown = 0;
    for (JsonNode risk : risks) {
      String observation =
          "• "
              + risk.path("task_title").asText("未命名任务")
              + "："
              + ("TASK_BLOCKED".equals(risk.path("rule").asText())
                  ? "遇到困难，需要确认处理办法"
                  : "已逾期，需要确认新的安排")
              + "。\n";
      if (summary.length() + observation.length() > 3000) break;
      summary.append(observation);
      shown++;
    }
    if (shown < risks.size()) summary.append("摘要展示前" + shown + "项，请在任务列表核对其余规则项。\n");
    summary.append("以上来自当前任务状态。具体原因还需要结合学生记录确认。\n");
    String explanation = result.path("summary").asText();
    if (summary.length() + explanation.length() <= 5000) summary.append(explanation);
    else {
      // Preserve the entire explanation, including qualifications, instead of cutting a sentence.
      if (result.has("limitations") && !result.path("limitations").isArray()) return;
      result.withArray("limitations").add("模型原摘要（完整保留）：" + explanation);
      summary.append("原摘要较长，完整解释及限定条件见limitations。");
    }
    result.put("summary", summary.toString());
  }

  public boolean evidenceInBusinessScope(JsonNode chunk, ObjectNode context) {
    String kind = context.path("kind").asText();
    String source = chunk.path("source_id").asText();
    boolean inSnapshot = false;
    for (JsonNode allowed : context.path("source_versions"))
      if (source.equals(allowed.path("source_id").asText())
          && chunk.path("source_revision").isIntegralNumber()
          && chunk.path("source_revision").asInt() == allowed.path("revision").asInt())
        inSnapshot = true;
    if (!inSnapshot
        || !chunk.path("locator").isObject()
        || !chunk.path("text").isTextual()
        || chunk.path("text").asText().isBlank()) return false;
    if (kind.equals("paper_guide")) return source.equals(context.at("/paper/source_id").asText());
    if (kind.equals("risk_brief")) {
      if (!context.at("/input/report_id").asText().isBlank())
        return source.equals(context.at("/weekly_report/source_id").asText())
            && chunk.path("source_revision").asInt()
                == context.at("/weekly_report/source_revision").asInt();
      var riskyTasks = new java.util.HashSet<String>();
      for (JsonNode risk : risks(context)) riskyTasks.add(risk.path("task_id").asText());
      for (String group : List.of("confirmed_experiments", "confirmed_notes"))
        for (JsonNode row : context.path(group))
          if (riskyTasks.contains(row.path("task_id").asText())
              && source.equals(row.path("source_id").asText())
              && chunk.path("source_revision").asInt() == row.path("source_revision").asInt())
            return true;
      return false;
    }
    if (kind.equals("weekly_report")) {
      for (String key : List.of("confirmed_experiments", "confirmed_notes"))
        for (JsonNode row : context.path(key))
          if (source.equals(row.path("source_id").asText())
              && chunk.path("source_revision").asInt() == row.path("source_revision").asInt())
            return true;
      return false;
    }
    return true;
  }

  /** Task dependencies are authorized business state, independent of literature retrieval. */
  public ObjectNode taskRiskSummary(ObjectNode context) {
    StringBuilder summary = new StringBuilder("请按前置任务的验收顺序继续推进。\n");
    for (JsonNode task : context.path("tasks")) {
      if (!"blocked".equals(task.path("status").asText())) continue;
      var pending = new java.util.ArrayList<String>();
      boolean unknown = false;
      for (JsonNode dependency : task.path("dependency_ids")) {
        JsonNode found = null;
        for (JsonNode candidate : context.path("tasks"))
          if (candidate.path("id").asText().equals(dependency.asText())) {
            found = candidate;
            break;
          }
        if (found == null) unknown = true;
        else if (!"completed".equals(found.path("status").asText()))
          pending.add(found.path("title").asText("未命名任务"));
      }
      String line =
          task.path("title").asText("未命名任务")
              + "："
              + (pending.isEmpty()
                  ? "请先确认阻塞原因。"
                  : "请先完成并验收前置任务“" + String.join("”“", pending) + "”。")
              + (unknown ? "部分前置任务不在当前可查看范围内，请向导师确认。" : "")
              + "\n";
      if (summary.length() + line.length() > 2500) break;
      summary.append(line);
    }
    summary.append(
        "培养任务提交后仍需导师验收；完成相近的个人任务不会替代前置任务验收。\n" + "目前缺少支持原因分析的记录，无法判断科研问题的原因，也不自动调整截止日期。");
    ObjectNode draft = fallback("ok", summary.toString(), "与风险任务相关的进展记录或阻塞原因说明");
    ((ObjectNode) draft.path("result"))
        .withArray("limitations")
        .removeAll()
        .add("只解释当前授权任务状态和依赖；尚无可引用材料支持科研原因分析或改期建议。");
    return draft;
  }

  public ObjectNode fallback(String outcome, String summary, String missing) {
    ObjectNode draft = json.createObjectNode();
    ObjectNode result = draft.putObject("result").put("outcome", outcome).put("summary", summary);
    result.putArray("claims");
    result.putArray("missing_inputs").add(missing);
    result.putArray("limitations").add("未生成可执行建议；需要补充资料后重新发起运行。");
    draft.putArray("proposal_drafts");
    return draft;
  }
}
