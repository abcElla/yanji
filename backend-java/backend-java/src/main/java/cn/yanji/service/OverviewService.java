package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.time.*;
import java.util.*;

@org.springframework.stereotype.Service
public class OverviewService {
  boolean notificationReadable(String actor, Map<String, Object> notice) {
    if (!eq(notice.get("type"), "task_due")) return true;
    var task = db.get("tasks", str(obj(notice.get("target")), "task_id"));
    return task != null && access.canStudent(actor, str(task, "student_id"), str(task, "lab_id"), false);
  }
  final Repository db;
  final Access access;
  final IdentityService identity;
  final StageTaskService tasks;
  final org.springframework.core.env.Environment env;

  public OverviewService(
      Repository db,
      Access access,
      IdentityService identity,
      StageTaskService tasks,
      org.springframework.core.env.Environment env) {
    this.db = db;
    this.access = access;
    this.identity = identity;
    this.tasks = tasks;
    this.env = env;
  }

  public Object handle(
      String method, String path, String actor, Map<String, Object> b, Map<String, Object> q) {
    if (path.equals("/students")) {
      require(
          identity.memberships(actor).stream().anyMatch(m -> eq(m.get("role"), "mentor")),
          403,
          "FORBIDDEN",
          "仅导师可查看学生列表");
      int limit = Integer.parseInt(q.getOrDefault("limit", 20).toString());
      String cursor = str(q, "cursor");
      if (cursor != null) UUID.fromString(cursor);
      var links =
          db.query("mentorships", "WHERE mentor_id=? AND active=TRUE ORDER BY id", actor).stream()
              .filter(l -> access.canStudent(actor, str(l, "student_id"), str(l, "lab_id"), true))
              .filter(l -> cursor == null || str(l, "id").compareTo(cursor) > 0)
              .toList();
      var page = links.subList(0, Math.min(limit, links.size()));
      return map(
          "students",
          page.stream()
              .map(
                  l ->
                      map(
                          "id",
                          l.get("student_id"),
                          "display_name",
                          db.get("users", str(l, "student_id")).get("display_name"),
                          "lab_id",
                          l.get("lab_id")))
              .toList(),
          "next_cursor",
          links.size() > limit ? page.get(page.size() - 1).get("id") : null);
    }
    if (path.equals("/notifications")) {
      var rows =
          db.query("notifications", "WHERE recipient_id=?", actor).stream()
              .filter(n -> notificationReadable(actor, n))
              .filter(n -> !eq(q.get("unread_only"), true) || n.get("read_at") == null)
              .toList();
      var page = page(rows, q, "items", "created_at");
      page.put(
          "items",
          list(page.get("items")).stream()
              .map(n -> pick(obj(n), "id", "type", "target", "read_at", "closed_at", "created_at"))
              .toList());
      page.put("notifications", page.get("items")); // Transitional alias for existing clients.
      return page;
    }
    if (path.startsWith("/notifications/")) {
      var n = access.found(db.lock("notifications", path.split("/")[2]));
      require(eq(n.get("recipient_id"), actor), 404, "NOT_FOUND", "通知不存在");
      require(notificationReadable(actor, n), 404, "NOT_FOUND", "通知不存在");
      if (n.get("read_at") == null) {
        n.put("read_at", now());
        db.update("notifications", n);
      }
      return pick(db.get("notifications", str(n, "id")), "id", "type", "target", "read_at", "closed_at", "created_at");
    }
    String role = str(q, "role");
    if (role == null) role = str(db.get("users", actor), "account_role");
    String student = str(q, "student_id");
    var me = identity.me(actor);
    List<Map<String, Object>> scopes;
    if (role.equals("student")) {
      require(student == null || eq(student, actor), 403, "FORBIDDEN", "学生总览只能查看本人");
      require(eq(db.get("users", actor).get("account_role"), "student"), 403, "FORBIDDEN", "无学生身份");
      scopes =
          identity.memberships(actor).stream().filter(m -> eq(m.get("role"), "student")).toList();
    } else {
      require(role.equals("mentor"), 422, "VALIDATION_ERROR", "role 只能是 student 或 mentor");
      require(
          identity.memberships(actor).stream().anyMatch(m -> eq(m.get("role"), "mentor")),
          403,
          "FORBIDDEN",
          "无导师身份");
      scopes =
          db.query("memberships", "WHERE role='student' AND active=TRUE").stream()
              .filter(
                  m ->
                      (student == null || eq(m.get("user_id"), student))
                          && access.canStudent(actor, str(m, "user_id"), str(m, "lab_id"), true))
              .toList();
      if (student != null) require(!scopes.isEmpty(), 403, "FORBIDDEN", "无权访问该学生");
    }
    var all =
        db.all("tasks").stream().filter(t -> access.inScopes(t, scopes, "student_id")).toList();
    var reports =
        db.all("weekly_reports").stream()
            .filter(r -> access.inScopes(r, scopes, "student_id"))
            .toList();
    var proposals =
        db.query("proposals", "WHERE status='pending'").stream()
            .filter(
                p ->
                    !before(p.get("expires_at"))
                        && access.inScopes(
                            db.get("agent_runs", str(p, "run_id")), scopes, "student_id")
                        && access.runReadable(actor, db.get("agent_runs", str(p, "run_id"))))
            .toList();
    var overdue = all.stream().filter(t -> eq(tasks.taskData(t).get("is_overdue"), true)).toList();
    var visible =
        all.stream()
            .sorted(
                Comparator.<Map<String, Object>, Boolean>comparing(
                        t -> eq(t.get("status"), "completed"))
                    .thenComparing(
                        t -> String.valueOf(t.getOrDefault("due_at", "9999")),
                        Comparator.nullsLast(String::compareTo))
                    .thenComparingInt(t -> num(t, "priority"))
                    .thenComparing(t -> str(t, "id")))
            .limit(20)
            .map(tasks::taskData)
            .toList();
    return map(
        "role",
        role,
        "onboarding_status",
        me.get("onboarding_status"),
        "collaboration_enabled",
        me.get("collaboration_enabled"),
        "counters",
        map(
            "students_total",
            scopes.stream().map(m -> m.get("user_id")).distinct().count(),
            "tasks_total",
            all.size(),
            "tasks_completed",
            all.stream().filter(t -> eq(t.get("status"), "completed")).count(),
            "tasks_overdue",
            overdue.size(),
            "reports_submitted",
            reports.stream()
                .filter(
                    r ->
                        Set.of("submitted", "reviewed", "changes_requested")
                            .contains(str(r, "status")))
                .count(),
            "pending_proposals",
            proposals.size()),
        "tasks",
        visible,
        "risks",
        overdue.stream()
            .limit(20)
            .map(
                t ->
                    map(
                        "type",
                        "task_overdue",
                        "task_id",
                        t.get("id"),
                        "task_title",
                        t.get("title"),
                        "student_id",
                        t.get("student_id"),
                        "due_at",
                        t.get("due_at"),
                        "reason",
                        "任务已逾期"))
            .toList(),
        "pending_proposals",
        proposals.stream()
            .limit(20)
            .map(p -> pick(p, "id", "run_id", "action_type", "target_student_id", "status"))
            .toList());
  }

  public Map<String, Object> readiness() {
    db.all("labs");
    var hearts = db.all("worker_heartbeats");
    var details = map();
    var queues = map();
    String mode = env.getProperty("yanji.agent-execution-mode", "external");
    long stale = env.getProperty("yanji.worker-stale-seconds", Long.class, 60L);
    long warning = env.getProperty("yanji.queue-warning-seconds", Long.class, 300L);
    boolean ready = true;
    for (String kind : List.of("files", "agent")) {
      var active =
          hearts.stream()
              .filter(
                  h ->
                      Set.of(kind, "all").contains(str(h, "kind"))
                          && (kind.equals("files") || eq(h.get("execution_mode"), mode))
                          && Duration.between(instant(h.get("updated_at")), Instant.now())
                                  .toSeconds()
                              < stale)
              .toList();
      details.put(
          kind,
          map(
              "available",
              !active.isEmpty(),
              "instances",
              active.size(),
              "last_heartbeat_at",
              active.stream().map(h -> str(h, "updated_at")).max(String::compareTo).orElse(null)));
      var pending =
          kind.equals("files")
              ? db.query(
                  "papers",
                  "WHERE ingest_status IN ('queued','parsing','indexing') AND revoked_at IS NULL")
              : db.query("agent_runs", "WHERE status IN ('queued','running')");
      long age =
          Math.max(
              0,
              pending.stream()
                  .mapToLong(
                      r ->
                          Duration.between(instant(r.get("created_at")), Instant.now()).toSeconds())
                  .max()
                  .orElse(0));
      boolean backlog = !pending.isEmpty() && age >= warning;
      queues.put(
          kind,
          map(
              "pending_resources",
              pending.size(),
              "oldest_pending_seconds",
              age,
              "backlog_warning",
              backlog));
      ready &= !active.isEmpty() && !backlog;
    }
    return map(
        "status",
        ready ? "ready" : "degraded",
        "database",
        "ok",
        "agent_execution_mode",
        mode,
        "workers",
        details,
        "queues",
        queues);
  }
}
