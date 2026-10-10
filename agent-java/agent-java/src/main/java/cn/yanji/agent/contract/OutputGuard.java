package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/**
 * Structural validity, authorization boundaries and provenance; semantic support still needs
 * evaluation.
 */
public final class OutputGuard {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OutputGuard.class);
  private final ObjectMapper json;
  private final Clock clock;
  private final JsonSchema schema;
  private final boolean contentValidationEnabled;

  public OutputGuard(ObjectMapper json, Clock clock) {
    this(json, clock, true);
  }

  public OutputGuard(ObjectMapper json, Clock clock, boolean contentValidationEnabled) {
    this.json = json;
    this.clock = clock;
    this.contentValidationEnabled = contentValidationEnabled;
    try (var stream = new ClassPathResource("finalize-schema.json").getInputStream()) {
      schema =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
              .getSchema(json.readTree(stream));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load bundled contract schema", e);
    }
  }

  public ObjectNode prepare(ObjectNode raw, ObjectNode context, ArrayNode evidence, int attempt) {
    if (raw != null && raw.path("result").isObject()) {
      raw = raw.deepCopy();
      var interpreted =
          cn.yanji.agent.workflow.SemanticFeedback.accept(
              context, raw.at("/result/feedback_interpretations"));
      if (interpreted.isEmpty())
        interpreted =
            cn.yanji.agent.workflow.SemanticFeedback.accept(
                context, context.path("feedback_interpretations"));
      if (!interpreted.isEmpty())
        ((ObjectNode) raw.path("result")).set("feedback_interpretations", interpreted);
      else ((ObjectNode) raw.path("result")).remove("feedback_interpretations");
    }
    if (!contentValidationEnabled)
      return display(
          raw == null ? null : cn.yanji.agent.model.ReadableModelAnswer.normalize(json, raw),
          context,
          evidence,
          attempt);
    return prepareChecked(raw, context, evidence, attempt);
  }

  public boolean contentValidationEnabled() {
    return contentValidationEnabled;
  }

  private ObjectNode display(ObjectNode raw, ObjectNode context, ArrayNode evidence, int attempt) {
    require(raw != null && raw.path("result").isObject() && attempt > 0, "模型未返回可显示的内容");
    ObjectNode out = json.createObjectNode().put("attempt", attempt);
    ObjectNode result = out.putObject("result");
    String outcome = raw.at("/result/outcome").asText("ok");
    result.put(
        "outcome",
        Set.of("ok", "needs_input", "insufficient_evidence").contains(outcome) ? outcome : "ok");
    String summary = raw.at("/result/summary").asText();
    require(!summary.isBlank(), "模型未返回可显示的内容");
    result.put("summary", summary.substring(0, Math.min(summary.length(), 5000)));
    if (raw.at("/result/feedback_interpretations").isArray())
      result.set("feedback_interpretations", raw.at("/result/feedback_interpretations").deepCopy());
    ArrayNode claims = result.putArray("claims");
    for (JsonNode claim : raw.at("/result/claims"))
      addDisplayText(claims, claim.isTextual() ? claim.asText() : claim.path("text").asText());
    for (String field : List.of("missing_inputs", "limitations")) {
      ArrayNode values = result.putArray(field);
      for (JsonNode value : raw.at("/result/" + field))
        if (value.isTextual()) values.add(value.asText());
    }
    ArrayNode nextSteps = result.putArray("next_steps");
    JsonNode suppliedSteps = raw.at("/result/next_steps");
    if (!suppliedSteps.isArray() && "research_followup".equals(context.path("kind").asText()))
      suppliedSteps = raw.path("next_steps");
    for (JsonNode rawStep : suppliedSteps) {
      String title = rawStep.path("title").asText().trim();
      if (title.isEmpty()) continue;
      if (nextSteps.size() >= 5) {
        claims.addObject().put("text", title).putArray("source_refs");
        continue;
      }
      ObjectNode step = nextSteps.addObject();
      step.put("title", title.substring(0, Math.min(title.length(), 200)));
      String description = rawStep.path("description").asText();
      step.put("description", description.substring(0, Math.min(description.length(), 5000)));
      String type = rawStep.path("type").asText("other");
      step.put(
          "type",
          Set.of("reading", "experiment", "writing", "other").contains(type) ? type : "other");
      ArrayNode criteria = step.putArray("acceptance_criteria");
      for (JsonNode value : rawStep.path("acceptance_criteria")) {
        if (criteria.size() >= 10) break;
        if (value.isTextual() && !value.asText().isBlank()) {
          String text = value.asText();
          criteria.add(text.substring(0, Math.min(text.length(), 1000)));
        }
      }
      if (rawStep.path("step_id").isTextual() && rawStep.path("step_id").asText().length() <= 40)
        step.put("step_id", rawStep.path("step_id").asText());
      if (Set.of("existing", "new").contains(rawStep.path("task_mode").asText()))
        step.put("task_mode", rawStep.path("task_mode").asText());
      if (rawStep.path("existing_task_id").isTextual())
        step.put("existing_task_id", rawStep.path("existing_task_id").asText());
      if (rawStep.path("depends_on").isArray()) {
        var dependencies = step.putArray("depends_on");
        for (JsonNode dependency : rawStep.path("depends_on"))
          if (dependency.isTextual() && dependencies.size() < 50) dependencies.add(dependency);
      }
      if (!step.path("existing_task_id").asText().isBlank())
        for (JsonNode task : context.path("tasks"))
          if (step.path("existing_task_id").equals(task.path("id"))
              && task.path("version").isNumber())
            step.set("expected_version", task.path("version"));
    }
    ArrayNode skills = result.putArray("suggested_skills");
    if ("research_followup".equals(context.path("kind").asText())) {
      Set<String> selected = new HashSet<>();
      ArrayNode recommendations = json.createArrayNode();
      if (raw.at("/result/suggested_skills").isArray())
        recommendations.addAll((ArrayNode) raw.at("/result/suggested_skills"));
      if (raw.path("suggested_skills").isArray())
        recommendations.addAll((ArrayNode) raw.path("suggested_skills"));
      for (JsonNode recommendation : recommendations) {
        if (skills.size() >= 3) break;
        String id = recommendation.path("id").asText();
        if (selected.contains(id)) continue;
        for (JsonNode available : context.path("skill_catalog")) {
          if (!id.equals(available.path("id").asText())) continue;
          ObjectNode skill = available.deepCopy();
          String reason = recommendation.path("reason").asText();
          skill.put("reason", reason.substring(0, Math.min(reason.length(), 1000)));
          skills.add(skill);
          selected.add(id);
          break;
        }
      }
    }
    ArrayNode actions = out.putArray("proposal_drafts");
    for (JsonNode proposal : raw.path("proposal_drafts")) {
      // Showing advice never grants execution permission. Keep existing executable
      // actions only when their original contract still passes; otherwise show text.
      try {
        require(
            actions.size() < Math.min(20, context.at("/limits/max_proposals").asInt(20)),
            "超过执行建议上限");
        ObjectNode single = json.createObjectNode();
        single.set("result", raw.path("result").deepCopy());
        ((ObjectNode) single.path("result")).putArray("claims");
        single.putArray("proposal_drafts").add(proposal.deepCopy());
        actions.addAll(
            (ArrayNode) prepareChecked(single, context, evidence, attempt).path("proposal_drafts"));
      } catch (AgentFailure rejectedAction) {
        StringBuilder text = new StringBuilder();
        collectAdvice(proposal.path("reason"), text);
        collectAdvice(proposal.path("payload"), text);
        addDisplayText(claims, text.toString());
      }
    }
    replaceText(result, "summary");
    for (JsonNode claim : claims)
      if (claim instanceof ObjectNode object) replaceText(object, "text");
    for (JsonNode step : nextSteps)
      if (step instanceof ObjectNode object) {
        replaceText(object, "title");
        replaceText(object, "description");
        humanizeArray(object.path("acceptance_criteria"));
      }
    humanizeArray(result.path("missing_inputs"));
    require(schema.validate(out).isEmpty(), "模型内容超过可显示的数据格式限制");
    return out;
  }

  private void addDisplayText(ArrayNode claims, String text) {
    for (int offset = 0; offset < text.length(); offset += 5000) {
      ObjectNode claim = claims.addObject();
      claim.put("text", text.substring(offset, Math.min(text.length(), offset + 5000)));
      claim.putArray("source_refs");
    }
  }

  private void collectAdvice(JsonNode node, StringBuilder text) {
    if (node.isTextual()) {
      if (!text.isEmpty()) text.append('\n');
      text.append(node.asText());
    } else if (node.isArray()) {
      for (JsonNode child : node) collectAdvice(child, text);
    } else if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              entry -> {
                if (!Set.of(
                        "source_refs",
                        "source_id",
                        "source_revision",
                        "local_key",
                        "stage_id",
                        "task_id",
                        "depends_on_local_keys",
                        "existing_dependency_ids",
                        "type")
                    .contains(entry.getKey())) collectAdvice(entry.getValue(), text);
              });
    }
  }

  private ObjectNode prepareChecked(
      ObjectNode raw, ObjectNode context, ArrayNode evidence, int attempt) {
    require(raw != null && attempt > 0, "输出或attempt无效");
    require(fieldsWithin(raw, Set.of("result", "proposal_drafts")), "模型输出包含未知字段");
    ObjectNode out = raw.deepCopy();
    require(out.path("result").isObject(), "缺少result对象");
    text(out.at("/result/summary"), 5000);
    boolean quotedFacts = EvidenceCitations.expand(out, context, evidence);
    out.put("attempt", attempt);
    if (!out.has("proposal_drafts")) out.putArray("proposal_drafts");
    require(out.path("proposal_drafts").isArray(), "建议必须是数组");
    if ("stage_plan".equals(context.path("kind").asText())) PlanningWording.apply(out, evidence);
    require(out.path("result").isObject(), "缺少result对象");
    require(
        out.path("proposal_drafts").size()
            <= Math.min(20, context.at("/limits/max_proposals").asInt(20)),
        "建议数量超过预算");
    for (JsonNode claim : out.at("/result/claims"))
      require(
          claim.path("source_refs").isArray() && !claim.path("source_refs").isEmpty(), "事实条目缺少引用");
    if (!"ok".equals(out.at("/result/outcome").asText()))
      require(out.path("proposal_drafts").isEmpty(), "资料不足时不能生成建议");
    for (JsonNode node : out.path("proposal_drafts")) {
      require(node.isObject(), "建议必须是对象");
      ObjectNode proposal = (ObjectNode) node;
      require(
          fieldsWithin(proposal, Set.of("action_type", "payload", "reason", "source_refs")),
          "模型不得生成身份或版本字段");
      String action = proposal.path("action_type").asText();
      require(contains(context.path("allowed_actions"), action), "不允许此建议动作");
      require(proposal.path("payload").isObject(), "建议缺少payload");
      require(
          proposal.path("source_refs").isArray() && !proposal.path("source_refs").isEmpty(),
          "建议缺少证据");
      proposal.put("target_student_id", context.path("student_id").asText());
      proposal.putNull("target");
      proposal.put("expires_at", clock.instant().plus(Duration.ofHours(24)).toString());
      proposal.set("base_versions", baselines(action, proposal.path("payload"), context));
      validatePayload(action, proposal.path("payload"), context);
    }
    var violations = schema.validate(out);
    if (!violations.isEmpty()) {
      LOG.warn(
          "schema_invalid run={} constraints={}",
          context.path("run_id").asText(),
          violations.stream()
              .limit(5)
              .map(v -> v.getType() + "@" + v.getInstanceLocation())
              .toList());
    }
    require(violations.isEmpty(), "输出不符合后端JSON Schema");
    scanRefs(out, context, evidence);
    if (Set.of("stage_plan", "risk_brief", "paper_guide", "weekly_report")
        .contains(context.path("kind").asText()))
      for (JsonNode claim : out.at("/result/claims"))
        require(
            claim
                .path("text")
                .asText()
                .equals(EvidenceCitations.quotedText(claim.path("source_refs"))),
            "事实条目必须使用原文摘录，记录数量和任务状态应由授权快照独立展示");
    RepeatThresholdGuard.validate(out, evidence);
    MentorFeedbackWording.apply(out, context, evidence);
    PaperGuideWording.apply(out, context);
    PaperMethodWording.apply(out, context, evidence);
    scanRefs(out, context, evidence);
    EvidenceCitations.constrainSummary(out, context, clock);
    humanizeText(
        out,
        quotedFacts
            || Set.of("stage_plan", "risk_brief", "paper_guide", "weekly_report")
                .contains(context.path("kind").asText()));
    CoverageDisclosure.apply(out, context);
    // Program-owned scope text must still respect the public length/shape contract.
    require(schema.validate(out).isEmpty(), "覆盖说明超过输出契约限制");
    return out;
  }

  // Only user-facing wording changes. Contract keys, identifiers and citation quotes stay intact.
  private void humanizeText(ObjectNode out, boolean quotedFacts) {
    ObjectNode result = (ObjectNode) out.path("result");
    replaceText(result, "summary");
    for (String field : List.of("missing_inputs", "limitations")) humanizeArray(result.path(field));
    for (JsonNode claim : result.path("claims"))
      if (!quotedFacts && claim instanceof ObjectNode object) replaceText(object, "text");
    for (JsonNode proposal : out.path("proposal_drafts")) {
      ObjectNode object = (ObjectNode) proposal;
      replaceText(object, "reason");
      humanizePayload(object.path("payload"), quotedFacts);
    }
  }

  private void humanizePayload(JsonNode node, boolean quotedFacts) {
    if (node instanceof ObjectNode object) {
      object
          .fields()
          .forEachRemaining(
              entry -> {
                String field = entry.getKey();
                if ("source_refs".equals(field) || (quotedFacts && "done_items".equals(field)))
                  return;
                if (Set.of("text", "title", "description", "body", "reason").contains(field))
                  replaceText(object, field);
                else if (Set.of("claims", "questions").contains(field)
                    && !(quotedFacts && "claims".equals(field))) humanizeArray(entry.getValue());
                humanizePayload(entry.getValue(), quotedFacts);
              });
    } else if (node.isArray()) for (JsonNode row : node) humanizePayload(row, quotedFacts);
  }

  private void humanizeArray(JsonNode node) {
    if (node instanceof ArrayNode array)
      for (int i = 0; i < array.size(); i++)
        if (array.get(i).isTextual())
          array.set(i, json.getNodeFactory().textNode(humanWords(array.get(i).asText())));
  }

  private void replaceText(ObjectNode object, String field) {
    if (object.path(field).isTextual()) object.put(field, humanWords(object.path(field).asText()));
  }

  private String humanWords(String text) {
    return text.replace("confirmed_experiments", "已确认实验记录")
        .replace("confirmed_notes", "已确认笔记")
        .replace("confirmed_at", "确认时间")
        .replace("stage_rules", "科研指导")
        .replace("rule_risks", "需要关注的任务")
        .replace("核验", "确认")
        .replace("evidence", "参考材料")
        .replaceAll("\\s*[（(]\\s*(?:task_id|stage_id|paper_id)\\s*[:：][^）)\\n]{1,150}[）)]", "")
        .replaceAll("\\b(?:todo|ready)\\b", "待开始")
        .replaceAll("\\bin_progress\\b", "进行中")
        .replaceAll("\\bblocked\\b", "等待前置任务")
        .replaceAll("\\bcompleted\\b", "已完成");
  }

  private ArrayNode baselines(String action, JsonNode payload, ObjectNode context) {
    ArrayNode bases = json.createArrayNode();
    switch (action) {
      case "task.create_batch" -> {
        for (JsonNode task : payload.path("tasks")) {
          String id = task.path("stage_id").asText();
          require(!id.isBlank() && id.equals(context.at("/stage/id").asText()), "规划必须绑定当前阶段");
          addBase(bases, "stage", id, context);
        }
      }
      case "task.reschedule_batch" -> {
        for (JsonNode item : payload.path("items"))
          addBase(bases, "task", item.path("task_id").asText(), context);
      }
      case "weekly_report.draft" -> {
        boolean exists = false;
        for (JsonNode base : context.path("business_versions"))
          if ("weekly_report".equals(base.path("type").asText())) {
            bases.add(base.deepCopy());
            exists = true;
          }
        if (!exists) {
          require(context.path("existing_report_version").asInt(-1) == 0, "缺少周报基线");
          bases.add(
              json.createObjectNode().put("type", "weekly_report").putNull("id").put("version", 0));
        }
      }
      case "reading_note.draft" -> {
        require(
            payload.path("paper_id").asText().equals(context.at("/paper/id").asText())
                && context.at("/paper/version").asInt() > 0,
            "文献不在当前主题内");
        bases.add(
            json.createObjectNode()
                .put("type", "paper")
                .put("id", context.at("/paper/id").asText())
                .put("version", context.at("/paper/version").asInt()));
      }
      case "mentor_feedback.draft" ->
          addBase(bases, "weekly_report", payload.path("report_id").asText(), context);
      default -> reject("未知动作");
    }
    require(!bases.isEmpty(), "缺少目标对象版本基线");
    return bases;
  }

  private void addBase(ArrayNode bases, String type, String id, ObjectNode context) {
    for (JsonNode base : context.path("business_versions"))
      if (type.equals(base.path("type").asText()) && id.equals(base.path("id").asText())) {
        for (JsonNode existing : bases) if (existing.equals(base)) return;
        bases.add(base.deepCopy());
        return;
      }
    reject("目标对象不在授权业务基线内");
  }

  private void validatePayload(String action, JsonNode payload, ObjectNode context) {
    Set<String> allowedFields =
        switch (action) {
          case "task.create_batch" -> Set.of("tasks");
          case "task.reschedule_batch" -> Set.of("items");
          case "weekly_report.draft" ->
              Set.of("week_start", "done_items", "issues", "next_plan", "help_needed");
          case "reading_note.draft" ->
              Set.of("paper_id", "task_id", "claims", "questions", "source_refs");
          case "mentor_feedback.draft" -> Set.of("report_id", "report_revision", "body");
          default -> Set.of();
        };
    require(fieldsWithin(payload, allowedFields), "建议payload包含未知字段");
    switch (action) {
      case "task.create_batch" -> validatePlan(payload, context);
      case "task.reschedule_batch" -> {
        boundedArray(payload.path("items"), 1, 20);
        Set<String> ids = new HashSet<>();
        for (JsonNode row : payload.path("items")) {
          require(
              row.isObject() && fieldsWithin(row, Set.of("task_id", "new_due_at", "reason")),
              "改期条目字段无效");
          text(row.path("task_id"), 36);
          if (row.has("reason")) text(row.path("reason"), 5000);
          require(ids.add(row.path("task_id").asText()), "重复改期目标");
          dateTime(row.path("new_due_at"), false);
          JsonNode task = find(context.path("tasks"), "id", row.path("task_id").asText());
          require(
              task != null
                  && !Set.of("completed", "submitted").contains(task.path("status").asText()),
              "当前任务不可改期");
          if ("risk_brief".equals(context.path("kind").asText())) {
            boolean risky = "blocked".equals(task.path("status").asText());
            try {
              risky |=
                  OffsetDateTime.parse(task.path("due_at").asText())
                      .toInstant()
                      .isBefore(clock.instant());
            } catch (RuntimeException ignored) {
              // A missing deadline cannot prove that a task is overdue.
            }
            require(risky, "只可为当前逾期或受阻任务建议改期");
            var due = OffsetDateTime.parse(row.path("new_due_at").asText()).toInstant();
            int days = context.at("/input/horizon_days").asInt(7);
            require(
                days > 0
                    && days <= 30
                    && due.isAfter(clock.instant())
                    && !due.isAfter(clock.instant().plus(Duration.ofDays(days))),
                "建议截止时间必须在本次检查的未来时间范围内");
          }
        }
      }
      case "weekly_report.draft" -> {
        require(
            payload.path("week_start").asText().equals(context.at("/input/week_start").asText()),
            "周报周期不匹配");
        try {
          require(
              LocalDate.parse(payload.path("week_start").asText()).getDayOfWeek().getValue() == 1,
              "周报周期必须从周一开始");
        } catch (RuntimeException e) {
          reject("周报日期无效");
        }
        for (String section : List.of("done_items", "issues", "next_plan", "help_needed")) {
          boundedArray(payload.path(section), 0, 200);
          for (JsonNode row : payload.path(section)) {
            require(
                row.isObject() && fieldsWithin(row, Set.of("id", "text", "origin", "source_refs")),
                "周报条目字段无效");
            ((ObjectNode) row).put("id", UUID.randomUUID().toString()).put("origin", "agent");
            text(row.path("text"), 5000);
            require(
                row.path("source_refs").isArray() && !row.path("source_refs").isEmpty(),
                "周报条目缺少来源");
            if ("done_items".equals(section))
              require(
                  row.path("text")
                      .asText()
                      .equals(EvidenceCitations.quotedText(row.path("source_refs"))),
                  "周报已完成事项必须是所引材料摘录，不能以单条来源概括未引用记录");
          }
        }
      }
      case "reading_note.draft" -> {
        for (String key : List.of("claims", "questions")) {
          boundedArray(payload.path(key), 0, 50);
          for (JsonNode row : payload.path(key)) text(row, 5000);
        }
        require(
            payload.path("source_refs").isArray() && !payload.path("source_refs").isEmpty(),
            "笔记缺少来源");
        Set<String> quotedClaims = new HashSet<>();
        for (JsonNode ref : payload.path("source_refs"))
          quotedClaims.add(ref.path("quote").asText());
        for (JsonNode claim : payload.path("claims"))
          require(quotedClaims.contains(claim.asText()), "笔记事实必须是所引材料原文，不能添加未经支持的结论");
        if (payload.hasNonNull("task_id"))
          require(
              payload.path("task_id").asText().equals(context.at("/subject/task_id").asText()),
              "笔记任务不在主题内");
      }
      case "mentor_feedback.draft" -> {
        text(payload.path("body"), 10000);
        if (!context.at("/input/report_id").asText().isBlank()) {
          require(
              payload.path("report_id").asText().equals(context.at("/weekly_report/id").asText()),
              "反馈周报不在当前主题内");
          require(
              payload.path("report_revision").asInt()
                  == context.at("/weekly_report/current_revision").asInt(),
              "反馈修订不匹配当前周报");
        }
        require(
            payload.path("report_revision").isIntegralNumber()
                && payload.path("report_revision").asInt() > 0,
            "反馈版本无效");
      }
      default -> reject("未知动作");
    }
  }

  private void validatePlan(JsonNode payload, ObjectNode context) {
    boundedArray(payload.path("tasks"), 1, 20);
    Map<String, List<String>> edges = new HashMap<>();
    for (JsonNode task : payload.path("tasks")) {
      require(
          task.isObject()
              && fieldsWithin(
                  task,
                  Set.of(
                      "local_key",
                      "stage_id",
                      "title",
                      "description",
                      "type",
                      "due_at",
                      "acceptance_criteria",
                      "depends_on_local_keys",
                      "existing_dependency_ids")),
          "任务条目包含未知字段");
      text(task.path("local_key"), 80);
      text(task.path("title"), 200);
      if (task.has("type"))
        require(
            task.path("type").isTextual()
                && Set.of("reading", "experiment", "writing", "other")
                    .contains(task.path("type").asText()),
            "任务类型无效");
      if (task.has("description"))
        require(
            task.path("description").isTextual()
                && task.path("description").asText().length() <= 5000,
            "任务描述无效");
      dateTime(task.path("due_at"), true);
      if (task.has("acceptance_criteria")) {
        boundedArray(task.path("acceptance_criteria"), 0, 50);
        for (JsonNode value : task.path("acceptance_criteria")) text(value, 1000);
      }
      require(!edges.containsKey(task.path("local_key").asText()), "重复local_key");
      List<String> deps = new ArrayList<>();
      if (task.has("depends_on_local_keys")) {
        boundedArray(task.path("depends_on_local_keys"), 0, 20);
        for (JsonNode dep : task.path("depends_on_local_keys")) {
          text(dep, 80);
          deps.add(dep.asText());
        }
      }
      edges.put(task.path("local_key").asText(), deps);
      if (task.has("existing_dependency_ids")) {
        boundedArray(task.path("existing_dependency_ids"), 0, 100);
        for (JsonNode dep : task.path("existing_dependency_ids"))
          require(find(context.path("tasks"), "id", dep.asText()) != null, "已有依赖不在授权范围内");
      }
    }
    Set<String> visited = new HashSet<>();
    for (String key : edges.keySet()) visit(key, edges, new HashSet<>(), visited);
  }

  private void visit(
      String key, Map<String, List<String>> edges, Set<String> visiting, Set<String> visited) {
    require(edges.containsKey(key), "依赖键不存在");
    require(!visiting.contains(key), "任务依赖成环");
    if (!visited.add(key)) return;
    visiting.add(key);
    for (String dep : edges.get(key)) visit(dep, edges, visiting, visited);
    visiting.remove(key);
  }

  private void scanRefs(JsonNode node, ObjectNode context, ArrayNode evidence) {
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        if ("source_refs".equals(entry.getKey())) {
          require(entry.getValue().isArray(), "引用必须是数组");
          for (JsonNode ref : entry.getValue()) validateRef(ref, context, evidence);
        } else scanRefs(entry.getValue(), context, evidence);
      }
    } else if (node.isArray()) for (JsonNode child : node) scanRefs(child, context, evidence);
  }

  private void validateRef(JsonNode ref, ObjectNode context, ArrayNode evidence) {
    String id = ref.path("source_id").asText();
    int revision = ref.path("source_revision").asInt();
    boolean allowed = false;
    for (JsonNode source : context.path("source_versions"))
      if (id.equals(source.path("source_id").asText())
          && revision == source.path("revision").asInt()) allowed = true;
    require(allowed, "引用不在快照来源范围内");
    text(ref.path("quote"), 300);
    String kind = ref.at("/locator/kind").asText();
    require(
        switch (kind) {
          case "pdf_page" -> ref.at("/locator/page").asInt() > 0;
          case "docx_paragraph" -> ref.at("/locator/paragraph").asInt() > 0;
          case "record_field" -> !ref.at("/locator/field").asText().isBlank();
          default -> false;
        },
        "引用定位无效");
    for (JsonNode chunk : evidence) {
      if (id.equals(chunk.path("source_id").asText())
          && revision == chunk.path("source_revision").asInt()
          && Objects.equals(
              canonicalLocator(ref.path("locator")), canonicalLocator(chunk.path("locator")))
          && (!ref.hasNonNull("chunk_id") || ref.path("chunk_id").equals(chunk.path("chunk_id")))
          && chunk.path("text").asText().contains(ref.path("quote").asText())) {
        ((ObjectNode) ref).set("locator", chunk.path("locator").deepCopy());
        return;
      }
    }
    reject("引用原文或位置无法核验");
  }

  private static JsonNode canonicalLocator(JsonNode locator) {
    if (!locator.isObject()) return locator;
    ObjectNode canonical = ((ObjectNode) locator).deepCopy();
    for (String field : List.of("page", "paragraph", "field"))
      if (canonical.has(field) && canonical.path(field).isNull()) canonical.remove(field);
    return canonical;
  }

  private static boolean fieldsWithin(JsonNode node, Set<String> allowed) {
    var names = node.fieldNames();
    while (names.hasNext()) if (!allowed.contains(names.next())) return false;
    return true;
  }

  private static boolean contains(JsonNode rows, String value) {
    for (JsonNode row : rows) if (value.equals(row.asText())) return true;
    return false;
  }

  private static JsonNode find(JsonNode rows, String key, String value) {
    for (JsonNode row : rows) if (value.equals(row.path(key).asText())) return row;
    return null;
  }

  private static void boundedArray(JsonNode node, int min, int max) {
    require(node.isArray() && node.size() >= min && node.size() <= max, "数组数量或类型无效");
  }

  private static void text(JsonNode node, int max) {
    require(
        node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max, "文本类型或长度无效");
  }

  private static void dateTime(JsonNode value, boolean optional) {
    if (optional && (value.isMissingNode() || value.isNull())) return;
    try {
      OffsetDateTime.parse(value.asText());
    } catch (RuntimeException e) {
      reject("日期必须带时区");
    }
  }

  private static void require(boolean ok, String message) {
    if (!ok) reject(message);
  }

  private static void reject(String message) {
    throw new AgentFailure("MODEL_OUTPUT_INVALID", message, false);
  }
}
