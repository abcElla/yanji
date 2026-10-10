package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.time.*;
import java.util.*;

@org.springframework.stereotype.Service
public class AgentService {
  final Repository db;
  final Access access;
  final StageTaskService tasks;
  final RecordService records;
  final Contract contract;

  static List<Object> mergeReportItems(Object saved, Object suggested) {
    var merged = new ArrayList<Object>(list(saved));
    var texts = new HashSet<String>();
    for (Object item : merged) texts.add(str(obj(item), "text").trim());
    for (Object item : list(suggested)) {
      if (texts.add(str(obj(item), "text").trim())) merged.add(item);
    }
    return merged;
  }

  public AgentService(
      Repository db,
      Access access,
      StageTaskService tasks,
      RecordService records,
      Contract contract) {
    this.db = db;
    this.access = access;
    this.tasks = tasks;
    this.records = records;
    this.contract = contract;
  }

  public Map<String, Object> run(String actor, String id) {
    var run = access.found(db.lock("agent_runs", id));
    require(access.runReadable(actor, run), 404, "NOT_FOUND", "对象不存在");
    return run;
  }

  public Map<String, Object> summary(Map<String, Object> r) {
    var out =
        pick(
            r,
            "id",
            "kind",
            "status",
            "step",
            "student_id",
            "created_at",
            "started_at",
            "finished_at",
            "version");
    var subject = map();
    obj(r.get("subject"))
        .forEach(
            (k, v) -> {
              if (Set.of("student_id", "stage_id", "task_id", "paper_id").contains(k)
                  && (v == null || v instanceof String)) subject.put(k, v);
            });
    var input = map();
    obj(r.get("input"))
        .forEach(
            (k, v) -> {
              if ((Set.of("question", "week_start", "report_id").contains(k) && v instanceof String)
                  || (k.equals("horizon_days") && v instanceof Number)) input.put(k, v);
            });
    out.put("subject", subject);
    out.put("input", input);
    return out;
  }

  public Map<String, Object> detail(Map<String, Object> r) {
    var out = summary(r);
    out.putAll(pick(r, "result", "error"));
    out.put(
        "proposal_ids",
        db.query("proposals", "WHERE run_id=?", r.get("id")).stream()
            .map(p -> p.get("id"))
            .toList());
    long age = Duration.between(instant(r.get("created_at")), Instant.now()).toSeconds();
    out.put(
        "queue",
        eq(r.get("status"), "queued")
            ? map(
                "waiting_seconds",
                age,
                "delayed",
                age >= 300,
                "message",
                age >= 300 ? "排队时间较长，请检查后台消费者和Agent连接" : "任务已入队，等待后台处理")
            : null);
    return out;
  }

  public Map<String, Object> create(String actor, Map<String, Object> b) {
    var subject = obj(b.get("subject"));
    var input = obj(b.get("input"));
    String student = str(subject, "student_id"),
        lab = access.lab(actor, student, false),
        kind = str(b, "kind");
    if (kind.equals("stage_plan")) {
      var stage = access.visible(actor, "stages", str(subject, "stage_id"));
      require(
          eq(stage.get("student_id"), student) && eq(stage.get("lab_id"), lab),
          422,
          "VALIDATION_ERROR",
          "stage_plan 需要有效 stage_id");
    }
    if (kind.equals("paper_guide")
        || (kind.equals("research_qa") && subject.get("paper_id") != null)) {
      var paper = db.get("papers", str(subject, "paper_id"));
      require(
          paper != null
              && eq(paper.get("lab_id"), lab)
              && access.source(actor, paper)
              && eq(paper.get("ingest_status"), "ready"),
          422,
          "SOURCE_NOT_READY",
          "需要可访问且已解析的文献");
    }
    if (kind.equals("weekly_report")) {
      try {
        require(
            LocalDate.parse(str(input, "week_start")).getDayOfWeek() == DayOfWeek.MONDAY,
            400,
            "INVALID_PERIOD",
            "week_start 必须是周一");
      } catch (java.time.format.DateTimeParseException | NullPointerException e) {
        throw new AppError(422, "VALIDATION_ERROR", "需要 week_start 日期");
      }
    }
    if (kind.equals("weekly_report") && input.get("report_id") != null) {
      var report = records.record(actor, "weekly_reports", str(input, "report_id"), false);
      require(eq(report.get("student_id"), student) && eq(report.get("lab_id"), lab)
          && eq(report.get("week_start").toString(), input.get("week_start"))
          && eq(report.get("status"), "draft"),
          422, "VALIDATION_ERROR", "请选择当前学生、当前周的周报草稿");
    }
    if (kind.equals("research_qa"))
      require(
          input.get("question") instanceof String && !str(input, "question").isBlank(),
          422,
          "VALIDATION_ERROR",
          "需要 question");
    if (kind.equals("risk_brief"))
      require(
          input.get("horizon_days") instanceof Number
              && num(input, "horizon_days") >= 1
              && num(input, "horizon_days") <= 30,
          422,
          "VALIDATION_ERROR",
          "horizon_days 必须为1至30");
    if (kind.equals("risk_brief") && input.get("report_id") != null) {
      var report = records.record(actor, "weekly_reports", str(input, "report_id"), false);
      access.student(actor, student, lab, true);
      require(eq(report.get("student_id"), student) && eq(report.get("lab_id"), lab)
          && Set.of("submitted", "changes_requested").contains(str(report, "status")),
          422, "VALIDATION_ERROR", "请选择当前学生已提交的周报");
    }
    var r =
        db.insert(
            "agent_runs",
            map(
                "lab_id",
                lab,
                "requested_by",
                actor,
                "student_id",
                student,
                "kind",
                kind,
                "subject",
                subject,
                "input",
                input));
    db.outbox("agent_run.requested", map("schema_version", 2, "run_id", r.get("id")));
    return map("run_id", r.get("id"), "status", "queued");
  }

  public Map<String, Object> proposal(String actor, String id) {
    var p = access.found(db.lock("proposals", id));
    var r = run(actor, str(p, "run_id"));
    require(eq(p.get("target_student_id"), r.get("student_id")), 404, "NOT_FOUND", "建议不存在");
    return p;
  }

  public Map<String, Object> proposalData(Map<String, Object> p) {
    return pick(
        p,
        "id",
        "run_id",
        "action_type",
        "target_student_id",
        "target",
        "payload",
        "reason",
        "source_refs",
        "base_versions",
        "status",
        "version",
        "expires_at",
        "execution_receipt");
  }

  public boolean canApprove(String actor, Map<String, Object> p, Map<String, Object> r) {
    return switch (str(p, "action_type")) {
      case "weekly_report.draft", "reading_note.draft" -> eq(actor, p.get("target_student_id"));
      case "task.reschedule_batch", "mentor_feedback.draft" ->
          access.canStudent(actor, str(r, "student_id"), str(r, "lab_id"), true);
      case "task.create_batch" ->
          !list(obj(p.get("payload")).get("tasks")).isEmpty()
              && (list(obj(p.get("payload")).get("tasks")).stream()
                      .anyMatch(t -> obj(t).get("stage_id") != null)
                  ? access.canStudent(actor, str(r, "student_id"), str(r, "lab_id"), true)
                  : eq(actor, r.get("student_id")));
      default -> false;
    };
  }

  public List<Object> refs(Map<String, Object> p) {
    var refs = new ArrayList<>(list(p.get("source_refs")));
    var payload = obj(p.get("payload"));
    if (eq(p.get("action_type"), "weekly_report.draft"))
      for (String key : RecordService.SECTIONS)
        for (Object item : list(payload.get(key))) refs.addAll(list(obj(item).get("source_refs")));
    if (eq(p.get("action_type"), "reading_note.draft"))
      refs.addAll(list(payload.get("source_refs")));
    return refs;
  }

  public void validateProposal(String actor, Map<String, Object> p, Map<String, Object> r) {
    var references = refs(p);
    access.evidence(actor, str(r, "lab_id"), references);
    for (Object raw : references) {
      var ref = obj(raw);
      var s = access.found(db.get("sources", str(ref, "source_id")));
      require(
          eq(s.get("active_revision"), ref.get("source_revision")), 409, "SOURCE_STALE", "来源已有新版本");
    }
    var bases = list(p.get("base_versions"));
    require(!bases.isEmpty(), 422, "VALIDATION_ERROR", "建议缺少 base_versions");
    Map<String, String> table =
        Map.of(
            "task",
            "tasks",
            "stage",
            "stages",
            "weekly_report",
            "weekly_reports",
            "paper",
            "papers",
            "reading_note",
            "reading_notes",
            "mentor_review_draft",
            "mentor_review_drafts");
    Set<String> checked = new HashSet<>();
    var payload = obj(p.get("payload"));
    for (Object raw : bases) {
      var b = obj(raw);
      String type = str(b, "type"), id = str(b, "id");
      require(
          table.containsKey(type) && b.get("version") instanceof Number,
          422,
          "VALIDATION_ERROR",
          "版本基线格式无效");
      if (id == null) {
        require(
            type.equals("weekly_report")
                && num(b, "version") == 0
                && eq(p.get("action_type"), "weekly_report.draft"),
            422,
            "VALIDATION_ERROR",
            "无效的空版本基线");
        require(obj(r.get("input")).get("report_id") == null,
            422, "VALIDATION_ERROR", "更新周报需要指定草稿版本");
        checked.add(type + ":null");
        continue;
      }
      var o = db.lock(table.get(type), id);
      require(
          o != null && eq(o.get("version"), b.get("version")), 409, "VERSION_CONFLICT", "业务对象已变化");
      require(eq(o.get("lab_id"), r.get("lab_id")), 403, "FORBIDDEN", "版本基线不在当前实验室");
      Object owner = o.getOrDefault("student_id", o.get("owner_id"));
      require(
          owner == null
              || eq(owner, r.get("student_id"))
              || (type.equals("paper")
                  && eq(o.get("visibility"), "lab")
                  && o.get("revoked_at") == null),
          403,
          "FORBIDDEN",
          "版本基线不在目标学生范围");
      checked.add(type + ":" + id);
    }
    Set<String> required = new HashSet<>();
    switch (str(p, "action_type")) {
      case "task.create_batch" -> {
        for (Object raw : list(payload.get("tasks")))
          if (obj(raw).get("stage_id") != null) required.add("stage:" + str(obj(raw), "stage_id"));
      }
      case "task.reschedule_batch" -> {
        for (Object raw : list(payload.get("items")))
          required.add("task:" + str(obj(raw), "task_id"));
      }
      case "weekly_report.draft" -> {
        String reportId = str(obj(r.get("input")), "report_id");
        require(eq(payload.get("week_start"), obj(r.get("input")).get("week_start")),
            422, "VALIDATION_ERROR", "周报日期与本次分析不一致");
        required.add("weekly_report:" + (reportId == null ? "null" : reportId));
      }
      case "reading_note.draft" -> required.add("paper:" + str(payload, "paper_id"));
      case "mentor_feedback.draft" -> required.add("weekly_report:" + str(payload, "report_id"));
      default -> throw new AppError(422, "VALIDATION_ERROR", "不支持的建议动作");
    }
    require(checked.containsAll(required), 422, "VALIDATION_ERROR", "建议缺少目标对象的版本基线");
  }

  Map<String, Object> approve(String actor, Map<String, Object> p, Map<String, Object> r) {
    require(canApprove(actor, p, r), 403, "FORBIDDEN", "无权采纳此建议");
    require(
        eq(p.get("status"), "pending")
            && !before(p.get("expires_at"))
            && eq(r.get("status"), "succeeded"),
        409,
        "INVALID_STATE",
        "建议不可采纳");
    validateProposal(actor, p, r);
    var payload = obj(p.get("payload"));
    String student = str(r, "student_id"), lab = str(r, "lab_id");
    Map<String, Object> receipt;
    switch (str(p, "action_type")) {
      case "task.create_batch" -> {
        var items = list(payload.get("tasks"));
        require(
            items.size() >= 1 && items.size() <= 20, 422, "VALIDATION_ERROR", "tasks 必须包含1至20项");
        var keys = new LinkedHashMap<String, String>();
        for (Object raw : items) {
          var t = obj(raw);
          String key = str(t, "local_key");
          require(
              key != null && !key.isBlank() && key.length() <= 80 && !keys.containsKey(key),
              422,
              "VALIDATION_ERROR",
              "local_key 缺失或重复");
          keys.put(key, uid());
        }
        Map<String, List<String>> graph = new HashMap<>();
        for (Object raw : items) {
          var t = obj(raw);
          var body = new LinkedHashMap<>(t);
          for (String key :
              List.of("local_key", "depends_on_local_keys", "existing_dependency_ids"))
            body.remove(key);
          body.put("student_id", student);
          body.put("lab_id", lab);
          body.put("dependency_ids", List.of());
          contract.validateNamed(body, "TaskCreate");
          if (body.get("stage_id") != null) {
            var s = access.visible(actor, "stages", str(body, "stage_id"));
            require(
                eq(s.get("student_id"), student)
                    && eq(s.get("lab_id"), lab)
                    && eq(s.get("status"), "active"),
                422,
                "VALIDATION_ERROR",
                "阶段不在目标范围");
            access.student(actor, student, lab, true);
          } else require(eq(actor, student), 403, "FORBIDDEN", "导师不能代学生采纳个人任务");
          String id = keys.get(str(t, "local_key"));
          var task =
              db.insert(
                  "tasks",
                  map(
                      "id",
                      id,
                      "lab_id",
                      lab,
                      "student_id",
                      student,
                      "stage_id",
                      body.get("stage_id"),
                      "title",
                      body.get("title"),
                      "description",
                      body.getOrDefault("description", ""),
                      "type",
                      body.getOrDefault("type", "other"),
                      "due_at",
                      due(body.get("due_at")),
                      "priority",
                      2,
                      "acceptance_criteria",
                      body.getOrDefault("acceptance_criteria", List.of()),
                      "dependency_ids",
                      List.of(),
                      "origin",
                      "agent_approved",
                      "created_by",
                      actor));
          var deps = new ArrayList<String>();
          for (Object local : list(t.get("depends_on_local_keys"))) {
            require(keys.containsKey(local.toString()), 422, "VALIDATION_ERROR", "任务依赖无效");
            deps.add(keys.get(local.toString()));
          }
          deps.addAll(
              list(t.get("existing_dependency_ids")).stream().map(Object::toString).toList());
          graph.put(id, deps);
        }
        for (var e : graph.entrySet()) {
          tasks.validateDependencies(e.getKey(), lab, student, e.getValue());
          tasks.setDependencies(db.get("tasks", e.getKey()), e.getValue());
          tasks.event(actor, db.get("tasks", e.getKey()), "", "todo", List.of(), "Agent审批创建任务");
        }
        receipt =
            map(
                "resource_type",
                "task",
                "resource_ids",
                new ArrayList<>(keys.values()),
                "local_key_ids",
                keys,
                "status",
                "todo");
      }
      case "task.reschedule_batch" -> {
        var items = list(payload.get("items"));
        require(
            items.size() >= 1 && items.size() <= 20, 422, "VALIDATION_ERROR", "items 必须包含1至20项");
        var ids = new ArrayList<String>();
        for (Object raw : items) {
          var item = obj(raw);
          var t = access.visible(actor, "tasks", str(item, "task_id"));
          require(
              eq(t.get("student_id"), student)
                  && eq(t.get("lab_id"), lab)
                  && !ids.contains(str(t, "id")),
              422,
              "VALIDATION_ERROR",
              "任务不在范围或重复");
          require(!eq(t.get("status"), "completed"), 409, "INVALID_STATE", "已完成任务不能改期");
          String due = due(item.get("new_due_at"));
          require(due != null, 422, "VALIDATION_ERROR", "new_due_at 必填");
          t.put("due_at", due);
          db.bump("tasks", t);
          tasks.event(
              actor,
              t,
              str(t, "status"),
              str(t, "status"),
              List.of(),
              String.valueOf(item.getOrDefault("reason", "AI建议调整期限")));
          ids.add(str(t, "id"));
        }
        receipt = map("resource_type", "task", "resource_ids", ids, "status", "rescheduled");
      }
      case "weekly_report.draft" -> {
        contract.validateNamed(payload, "ReportCreate");
        String reportId = str(obj(r.get("input")), "report_id");
        var existing = reportId == null ? null : db.get("weekly_reports", reportId);
        Map<String, Object> d;
        if (existing == null) d = records.create(actor, "weekly_reports", payload);
        else {
          require(eq(existing.get("status"), "draft"), 409, "INVALID_STATE", "周报已提交");
          var body = new LinkedHashMap<>(payload);
          body.remove("week_start");
          // Adopting suggestions adds content; it must never erase student-written sections.
          for (String section : List.of("done_items", "issues", "next_plan", "help_needed")) {
            body.put(section, mergeReportItems(existing.get(section), payload.get(section)));
          }
          body.put("expected_version", existing.get("version"));
          d =
              obj(
                  records.handle(
                      "PATCH", "/weekly-reports/" + str(existing, "id"), actor, body, map()));
        }
        receipt =
            map(
                "resource_type",
                "weekly_report",
                "resource_ids",
                List.of(d.get("id")),
                "status",
                "draft");
      }
      case "reading_note.draft" -> {
        contract.validateNamed(payload, "NoteCreate");
        var d = records.create(actor, "reading_notes", payload);
        receipt =
            map(
                "resource_type",
                "reading_note",
                "resource_ids",
                List.of(d.get("id")),
                "status",
                "draft");
      }
      case "mentor_feedback.draft" -> {
        var report = access.visible(actor, "weekly_reports", str(payload, "report_id"));
        require(
            eq(report.get("student_id"), student)
                && eq(report.get("current_revision"), payload.get("report_revision"))
                && Set.of("submitted", "changes_requested").contains(str(report, "status"))
                && payload.get("body") instanceof String
                && !str(payload, "body").isBlank(),
            422,
            "VALIDATION_ERROR",
            "导师反馈目标或内容无效");
        var d =
            db.one(
                "mentor_review_drafts",
                "WHERE report_id=? AND report_revision=? AND mentor_id=?",
                report.get("id"),
                payload.get("report_revision"),
                actor);
        if (d == null)
          d =
              db.insert(
                  "mentor_review_drafts",
                  map(
                      "lab_id",
                      lab,
                      "report_id",
                      report.get("id"),
                      "report_revision",
                      payload.get("report_revision"),
                      "mentor_id",
                      actor,
                      "body",
                      payload.get("body")));
        else {
          d.put("body", payload.get("body"));
          db.bump("mentor_review_drafts", d);
        }
        receipt =
            map(
                "resource_type",
                "mentor_review_draft",
                "resource_ids",
                List.of(d.get("id")),
                "status",
                "draft");
      }
      default -> throw new AppError(422, "VALIDATION_ERROR", "不支持的建议动作");
    }
    p.put("status", "applied");
    p.put("execution_receipt", receipt);
    db.bump("proposals", p);
    db.insert(
        "proposal_executions",
        map("proposal_id", p.get("id"), "actor_id", actor, "receipt_json", receipt));
    db.outbox(
        "proposal.applied",
        map("proposal_id", p.get("id"), "run_id", r.get("id"), "actor_id", actor));
    return map("proposal", proposalData(p), "execution_receipt", receipt);
  }

  public Object handle(
      String method, String path, String actor, Map<String, Object> b, Map<String, Object> q) {
    if (path.startsWith("/proposals") && q.get("status") != null)
      require(
          Set.of("pending", "applied", "rejected", "expired").contains(str(q, "status")),
          422,
          "VALIDATION_ERROR",
          "建议状态无效");
    String[] parts = path.split("/");
    boolean runs = parts[1].equals("agent-runs");
    if (parts.length == 2) {
      if (method.equals("POST")) return create(actor, b);
      var scopes = access.scopes(actor, str(q, "student_id"));
      if (runs) {
        var rows =
            db.all("agent_runs").stream()
                .filter(r -> access.inScopes(r, scopes, "student_id"))
                .filter(r -> access.runReadable(actor, r))
                .filter(r -> q.get("kind") == null || eq(q.get("kind"), r.get("kind")))
                .toList();
        var page = page(rows, q, "runs", "created_at");
        page.put("runs", list(page.get("runs")).stream().map(raw -> summary(obj(raw))).toList());
        return page;
      }
      if (q.get("run_id") != null) run(actor, str(q, "run_id"));
      return map(
          "proposals",
          db.all("proposals").stream()
              .filter(
                  p -> {
                    var r = db.get("agent_runs", str(p, "run_id"));
                    return access.inScopes(r, scopes, "student_id") && access.runReadable(actor, r);
                  })
              .filter(p -> q.get("run_id") == null || eq(q.get("run_id"), p.get("run_id")))
              .filter(p -> q.get("status") == null || eq(q.get("status"), p.get("status")))
              .map(this::proposalData)
              .toList());
    }
    String id = parts[2];
    if (runs) {
      var r = run(actor, id);
      if (method.equals("GET")) {
        if (parts.length == 3) return detail(r);
        int after = Integer.parseInt(q.getOrDefault("after_seq", 0).toString()),
            limit = Integer.parseInt(q.getOrDefault("limit", 20).toString());
        var events =
            db.query(
                "agent_run_events",
                "WHERE run_id=? AND seq>? ORDER BY seq LIMIT ?",
                id,
                after,
                limit);
        return map(
            "events",
            events.stream()
                .map(e -> pick(e, "seq", "step", "status", "message", "created_at"))
                .toList(),
            "next_seq",
            events.isEmpty() ? after : events.get(events.size() - 1).get("seq"));
      }
      require(eq(r.get("requested_by"), actor), 403, "FORBIDDEN", "只有发起者可执行此操作");
      if (parts[3].equals("retry")) {
        require(
            Set.of("failed", "cancelled").contains(str(r, "status")),
            409,
            "INVALID_STATE",
            "只有失败或取消的任务可重试");
        var created = create(actor, pick(r, "kind", "subject", "input"));
        created.put("retry_of", id);
        return created;
      }
      db.version(r, b);
      require(
          Set.of("queued", "running").contains(str(r, "status")), 409, "INVALID_STATE", "任务已结束");
      r.putAll(
          map(
              "status",
              "cancelled",
              "finished_at",
              now(),
              "lease_expiry",
              null,
              "lease_token_hash",
              null));
      db.bump("agent_runs", r);
      db.outbox("agent_run.cancelled", map("run_id", id));
      return detail(r);
    }
    var p = proposal(actor, id);
    var r = run(actor, str(p, "run_id"));
    if (method.equals("GET"))
      return map(
          "proposal",
          proposalData(p),
          "can_approve",
          eq(p.get("status"), "pending")
              && !before(p.get("expires_at"))
              && canApprove(actor, p, r));
    db.version(p, b);
    if (parts[3].equals("approve")) return approve(actor, p, r);
    require(canApprove(actor, p, r), 403, "FORBIDDEN", "无权处理此建议");
    require(
        eq(p.get("status"), "pending") && !before(p.get("expires_at")),
        409,
        "INVALID_STATE",
        "建议已处理或过期");
    p.put("status", "rejected");
    db.bump("proposals", p);
    db.outbox(
        "proposal.rejected", map("proposal_id", id, "reason", b.get("reason"), "actor_id", actor));
    return proposalData(p);
  }
}
