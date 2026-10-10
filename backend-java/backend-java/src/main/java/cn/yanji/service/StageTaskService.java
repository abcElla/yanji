package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.util.*;

@org.springframework.stereotype.Service
public class StageTaskService {
  final Repository db;
  public final Access access;
  static final String[] STAGE = {
    "id", "lab_id", "student_id", "title", "goal", "due_at", "rule_set_id", "status", "version"
  };
  static final String[] TASK = {
    "id",
    "lab_id",
    "student_id",
    "stage_id",
    "title",
    "description",
    "type",
    "due_at",
    "status",
    "priority",
    "acceptance_criteria",
    "dependency_ids",
    "origin",
    "created_by",
    "version"
  };

  public StageTaskService(Repository db, Access access) {
    this.db = db;
    this.access = access;
  }

  public List<String> dependencies(String id) {
    return db.query("task_dependencies", "WHERE task_id=?", id).stream()
        .map(r -> str(r, "depends_on_task_id"))
        .toList();
  }

  public Map<String, Object> taskData(Map<String, Object> t) {
    var r = pick(t, TASK);
    r.put("created_by", verifiedCreator(t));
    var deps = dependencies(str(t, "id"));
    r.put("dependency_ids", deps);
    r.put(
        "is_overdue",
        t.get("due_at") != null && before(t.get("due_at")) && !eq(t.get("status"), "completed"));
    var pending=deps.stream().map(id->db.get("tasks",id)).filter(Objects::nonNull)
        .filter(d->!eq(d.get("status"),"completed")).map(d->str(d,"title")).toList();
    r.put("blocking_reason", blocked(deps) ? "请先完成前置任务："+String.join("、",pending)+"；培养任务提交后还需导师验收" : null);
    r.put("can_complete_personal", personal(t));
    return r;
  }

  boolean personal(Map<String, Object> task) {
    return task.get("stage_id") == null
        && eq(verifiedCreator(task), task.get("student_id"))
        && (eq(task.get("origin"), "human") || eq(task.get("origin"), "agent_approved"));
  }

  String verifiedCreator(Map<String, Object> task) {
    var creation =
        db.query(
            "task_events",
            "WHERE task_id=? AND from_status='' AND to_status='todo'",
            task.get("id"));
    var actors =
        creation.stream().map(e -> str(e, "actor_id")).filter(Objects::nonNull).distinct().toList();
    // Resolve legacy attribution from server audit, never infer it from student_id.
    return actors.size() == 1 ? actors.get(0) : null;
  }

  public void authorizeCompletionReplay(String actor, String id, Map<String, Object> body) {
    var task = access.visible(actor, "tasks", id);
    if (eq(actor, task.get("student_id")) && eq(body.get("to_status"), "completed"))
      require(personal(task), 403, "FORBIDDEN", "指派任务须由导师验收");
  }

  boolean blocked(List<String> deps) {
    return deps.stream()
        .anyMatch(
            id -> {
              var d = db.get("tasks", id);
              return d == null || !eq(d.get("status"), "completed");
            });
  }

  public void setDependencies(Map<String, Object> task, List<String> deps) {
    task.put("dependency_ids", deps);
    db.update("tasks", task);
    db.execute("DELETE FROM task_dependencies WHERE task_id=?", task.get("id"));
    for (String id : deps)
      db.insert("task_dependencies", map("task_id", task.get("id"), "depends_on_task_id", id));
  }

  public void validateDependencies(String id, String lab, String student, List<String> deps) {
    require(
        new HashSet<>(deps).size() == deps.size() && !deps.contains(id),
        409,
        "DEPENDENCY_CYCLE",
        "依赖任务重复或引用自身");
    Map<String, List<String>> graph = new HashMap<>();
    for (var t :
        db.query("tasks", "WHERE lab_id=? AND student_id=? ORDER BY id FOR UPDATE", lab, student))
      graph.put(str(t, "id"), dependencies(str(t, "id")));
    for (String dep : deps) require(graph.containsKey(dep), 422, "VALIDATION_ERROR", "依赖任务不属于同一学生");
    graph.put(id, deps);
    visit(id, graph, new HashSet<>(), new HashSet<>());
  }

  void visit(String node, Map<String, List<String>> graph, Set<String> active, Set<String> done) {
    require(!active.contains(node), 409, "DEPENDENCY_CYCLE", "任务依赖不能成环");
    if (done.contains(node)) return;
    active.add(node);
    for (String child : graph.getOrDefault(node, List.of())) visit(child, graph, active, done);
    active.remove(node);
    done.add(node);
  }

  Map<String, Object> rule(String id, String lab) {
    if (id == null) return null;
    var r = db.get("stage_rule_sets", id);
    require(
        r != null && eq(r.get("lab_id"), lab) && before(r.get("effective_at")),
        422,
        "VALIDATION_ERROR",
        "阶段规则不存在或尚未生效");
    return r;
  }

  public Map<String, Object> createTask(String actor, Map<String, Object> b) {
    String student = str(b, "student_id"), lab = str(b, "lab_id"), stage = str(b, "stage_id");
    db.lock("users", student);
    if (stage != null) {
      var s = access.visible(actor, "stages", stage);
      require(
          eq(s.get("student_id"), student) && (lab == null || eq(s.get("lab_id"), lab)),
          422,
          "VALIDATION_ERROR",
          "阶段与学生不匹配");
      lab = str(s, "lab_id");
      access.student(actor, student, lab, true);
      require(eq(s.get("status"), "active"), 400, "INVALID_STATE", "阶段不可添加任务");
    } else {
      if (lab == null) lab = access.lab(actor, student, false);
      access.student(actor, student, lab, !eq(actor, student));
    }
    String id = uid();
    var deps = list(b.get("dependency_ids")).stream().map(Object::toString).toList();
    validateDependencies(id, lab, student, deps);
    var t =
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
                stage,
                "title",
                b.get("title"),
                "description",
                b.getOrDefault("description", ""),
                "type",
                b.getOrDefault("type", "other"),
                "due_at",
                due(b.get("due_at")),
                "priority",
                b.getOrDefault("priority", 2),
                "acceptance_criteria",
                b.getOrDefault("acceptance_criteria", List.of()),
                "dependency_ids",
                deps,
                "created_by",
                actor));
    setDependencies(t, deps);
    event(actor, t, "", "todo", List.of(), "创建任务");
    return taskData(t);
  }

  void event(
      String actor, Map<String, Object> t, String from, String to, Object refs, String comment) {
    db.insert(
        "task_events",
        map(
            "task_id",
            t.get("id"),
            "actor_id",
            actor,
            "from_status",
            from,
            "to_status",
            to,
            "evidence_refs",
            refs,
            "comment",
            comment));
  }

  public Object handle(
      String method, String path, String actor, Map<String, Object> b, Map<String, Object> q) {
    if (path.startsWith("/tasks") && q.get("status") != null)
      require(
          Set.of("todo", "in_progress", "blocked", "submitted", "completed")
              .contains(str(q, "status")),
          422,
          "VALIDATION_ERROR",
          "任务状态无效");
    if (q.get("stage_id") != null) access.visible(actor, "stages", str(q, "stage_id"));
    String[] parts = path.split("/");
    boolean stages = parts[1].equals("stages");
    String table = stages ? "stages" : "tasks", key = stages ? "stages" : "tasks";
    if (parts.length == 2) {
      if (method.equals("GET")) {
        var scopes = access.scopes(actor, str(q, "student_id"));
        var rows =
            db.all(table).stream()
                .filter(r -> access.inScopes(r, scopes, "student_id"))
                .filter(r -> q.get("status") == null || eq(r.get("status"), q.get("status")))
                .filter(r -> q.get("stage_id") == null || eq(r.get("stage_id"), q.get("stage_id")))
                .filter(
                    r ->
                        q.get("due_before") == null
                            || (r.get("due_at") != null
                                && !instant(r.get("due_at"))
                                    .isAfter(instant(due(q.get("due_before"))))))
                .toList();
        var page = page(rows, q, key, "created_at");
        page.put(
            key,
            list(page.get(key)).stream()
                .map(raw -> stages ? pick(obj(raw), STAGE) : taskData(obj(raw)))
                .toList());
        return page;
      }
      if (!stages) return createTask(actor, b);
      String student = str(b, "student_id"), lab = str(b, "lab_id");
      if (lab == null) lab = access.lab(actor, student, true);
      access.student(actor, student, lab, true);
      rule(str(b, "rule_set_id"), lab);
      return pick(
          db.insert(
              "stages",
              map(
                  "lab_id",
                  lab,
                  "student_id",
                  student,
                  "title",
                  b.get("title"),
                  "goal",
                  b.get("goal"),
                  "due_at",
                  due(b.get("due_at")),
                  "rule_set_id",
                  b.get("rule_set_id"))),
          STAGE);
    }
    String id = parts[2];
    var row = access.visible(actor, table, id);
    String lab = str(row, "lab_id"), student = str(row, "student_id"), status = str(row, "status");
    if (method.equals("GET")) {
      if (parts.length == 4 && parts[3].equals("events"))
        return page(db.query("task_events", "WHERE task_id=?", id), q, "events", "happened_at");
      if (!stages) {
        var out = taskData(row);
        var e =
            db.one(
                "task_events",
                "WHERE task_id=? AND to_status='submitted' ORDER BY happened_at DESC,id DESC",
                id);
        out.put("evidence_refs", e == null ? List.of() : e.get("evidence_refs"));
        return out;
      }
      var rs = rule(str(row, "rule_set_id"), lab);
      var submission =
          db.one("stage_submissions", "WHERE stage_id=? ORDER BY stage_version DESC", id);
      return map(
          "stage",
          pick(row, STAGE),
          "requirements",
          rs == null
              ? List.of()
              : obj(rs.get("rules_json")).getOrDefault("requirements", List.of()),
          "tasks",
          db.query("tasks", "WHERE stage_id=?", id).stream().map(this::taskData).toList(),
          "submission",
          submission == null
              ? null
              : pick(
                  submission,
                  "id",
                  "evidence_refs",
                  "submitted_at",
                  "review_decision",
                  "review_comment"));
    }
    if (!stages) {
      db.lock("users", student);
      db.query("tasks", "WHERE lab_id=? AND student_id=? ORDER BY id FOR UPDATE", lab, student);
      row = access.visible(actor, table, id);
    }
    db.version(row, b);
    if (method.equals("PATCH")) {
      require(
          stages ? !status.equals("accepted") : !Set.of("submitted", "completed").contains(status),
          400,
          "INVALID_STATE",
          "当前状态不可编辑");
      access.student(actor, student, lab, stages || !eq(actor, student));
      if (!stages && eq(actor, student) && row.get("stage_id") != null)
        require(
            Collections.disjoint(
                b.keySet(), Set.of("due_at", "acceptance_criteria", "dependency_ids")),
            403,
            "FORBIDDEN",
            "学生不能修改培养任务期限、标准或依赖");
      var values = new LinkedHashMap<>(b);
      values.remove("expected_version");
      require(!values.isEmpty(), 422, "VALIDATION_ERROR", "没有需要修改的字段");
      if (values.containsKey("due_at")) values.put("due_at", due(values.get("due_at")));
      if (values.containsKey("dependency_ids") && values.get("dependency_ids") != null) {
        var deps = list(values.remove("dependency_ids")).stream().map(Object::toString).toList();
        validateDependencies(id, lab, student, deps);
        setDependencies(row, deps);
      }
      row.putAll(values);
      db.bump(table, row);
      if (!stages) event(actor, row, status, status, List.of(), "编辑任务");
    } else if (stages && parts[3].equals("submit")) {
      require(eq(actor, student), 403, "FORBIDDEN", "只能提交本人阶段");
      require(status.equals("active"), 400, "INVALID_STATE", "阶段不可重复提交");
      access.evidence(actor, lab, b.get("evidence_refs"));
      row.put("status", "submitted");
      db.bump(table, row);
      db.insert(
          "stage_submissions",
          map(
              "stage_id",
              id,
              "stage_version",
              row.get("version"),
              "actor_id",
              actor,
              "evidence_refs",
              b.getOrDefault("evidence_refs", List.of())));
    } else if (stages && parts[3].equals("review")) {
      access.student(actor, student, lab, true);
      require(status.equals("submitted"), 400, "INVALID_STATE", "只有已提交阶段可审阅");
      var s =
          access.found(
              db.one(
                  "stage_submissions",
                  "WHERE stage_id=? AND stage_version=?",
                  id,
                  row.get("version")));
      s.putAll(
          map(
              "reviewed_by",
              actor,
              "review_decision",
              b.get("decision"),
              "review_comment",
              b.getOrDefault("comment", ""),
              "reviewed_at",
              now()));
      db.update("stage_submissions", s);
      row.put("status", eq(b.get("decision"), "accept") ? "accepted" : "active");
      db.bump(table, row);
    } else if (!stages && parts[3].equals("transition")) {
      String to = str(b, "to_status");
      boolean own = eq(actor, student), personal = personal(row);
      if (own) {
        require(personal || !to.equals("completed"), 403, "FORBIDDEN", "培养任务须由导师验收");
        require(
            !(status.equals("submitted") && to.equals("in_progress")),
            403,
            "FORBIDDEN",
            "只有导师可退回已提交任务");
      } else {
        access.student(actor, student, lab, true);
        require(
            status.equals("submitted") && Set.of("completed", "in_progress").contains(to),
            403,
            "FORBIDDEN",
            "导师只能验收或退回已提交任务");
      }
      Map<String, Set<String>> allowed =
          Map.of(
              "todo",
              Set.of("in_progress", "blocked"),
              "in_progress",
              Set.of("blocked", "submitted"),
              "blocked",
              Set.of("in_progress", "submitted"),
              "submitted",
              Set.of("completed", "in_progress"),
              "completed",
              Set.of());
      require(
          (own && personal && to.equals("completed") && !status.equals("completed"))
              || allowed.getOrDefault(status, Set.of()).contains(to),
          400,
          "INVALID_STATE",
          "任务状态不允许此操作");
      require(
          !Set.of("in_progress", "submitted", "completed").contains(to) || !blocked(dependencies(id)),
          400,
          "INVALID_STATE",
          "依赖任务尚未完成");
      access.evidence(actor, lab, b.get("evidence_refs"));
      row.put("status", to);
      db.bump(table, row);
      event(
          actor,
          row,
          status,
          to,
          b.getOrDefault("evidence_refs", List.of()),
          String.valueOf(b.getOrDefault("comment", "")));
      if (to.equals("completed"))
        db.outbox("task.completed", map("task_id", id, "student_id", student, "lab_id", lab));
    } else throw new AppError(404, "NOT_FOUND", "接口不存在");
    return stages ? pick(row, STAGE) : taskData(row);
  }
}
