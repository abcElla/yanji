package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.mapper.Repository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Reminders follow live task facts and authorization; scans never change task status. */
@org.springframework.stereotype.Service
public class TaskNotifications {
  final Repository db;
  final Access access;

  public TaskNotifications(Repository db, Access access) {
    this.db = db;
    this.access = access;
  }

  public void refresh() {
    for (var candidate : db.query("tasks", "WHERE due_at IS NOT NULL AND due_at<=? AND status<>'completed'", java.sql.Timestamp.from(Instant.now()))) {
      var task = db.lock("tasks", str(candidate, "id"));
      if (!dueNow(task)) continue;
      var recipients = new LinkedHashSet<String>();
      String student = str(task, "student_id"), lab = str(task, "lab_id");
      recipients.add(student);
      for (var link : db.query("mentorships", "WHERE student_id=? AND lab_id=? AND active=TRUE", student, lab))
        recipients.add(str(link, "mentor_id"));
      for (String recipient : recipients) {
        if (!access.canStudent(recipient, student, lab, false)) continue;
        String key = "task_due:" + task.get("id") + ":" + task.get("due_at");
        String id = UUID.nameUUIDFromBytes((recipient + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
        var existing = db.get("notifications", id);
        if (existing == null)
          db.insert("notifications", map("id", id, "recipient_id", recipient, "business_key", key,
              "type", "task_due", "target", map("task_id", task.get("id"), "title", task.get("title"),
                  "due_at", task.get("due_at"), "message", "任务已过截止时间，请核对进展并与导师确认后续安排。")));
        else if (existing.get("closed_at") != null) {
          existing.put("closed_at", null);
          existing.put("read_at", null);
          existing.put("target", map("task_id", task.get("id"), "title", task.get("title"),
              "due_at", task.get("due_at"), "message", "任务已过截止时间，请核对进展并与导师确认后续安排。"));
          db.update("notifications", existing);
        }
      }
    }
    for (var candidate : db.query("notifications", "WHERE type='task_due' AND closed_at IS NULL")) {
      var notice = db.lock("notifications", str(candidate, "id"));
      var target = obj(notice.get("target"));
      var task = db.get("tasks", str(target, "task_id"));
      if (!dueNow(task) || !eq(target.get("due_at"), task.get("due_at"))
          || !access.canStudent(str(notice, "recipient_id"), str(task, "student_id"), str(task, "lab_id"), false)) {
        notice.put("closed_at", now());
        db.update("notifications", notice);
      }
    }
  }

  boolean dueNow(Map<String, Object> task) {
    return task != null && task.get("due_at") != null && before(task.get("due_at"))
        && Set.of("todo", "in_progress", "blocked", "submitted").contains(str(task, "status"));
  }
}
