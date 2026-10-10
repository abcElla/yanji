package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.time.*;
import java.util.*;

@org.springframework.stereotype.Service
public class RecordService {
  final Repository db;
  final Access access;
  static final String[] SECTIONS = {"done_items", "issues", "next_plan", "help_needed"};

  public RecordService(Repository db, Access access) {
    this.db = db;
    this.access = access;
  }

  public String studentLab(String actor) {
    return access.lab(actor, actor, false);
  }

  public Map<String, Object> record(String actor, String table, String id, boolean editing) {
    var r = access.found(db.lock(table, id));
    String owner = str(r, table.equals("weekly_reports") ? "student_id" : "owner_id");
    if (!eq(actor, owner)) {
      require(
          !editing
              && !eq(r.get("status"), "draft")
              && (table.equals("weekly_reports") || eq(r.get("status"), "confirmed")),
          404,
          "NOT_FOUND",
          "对象不存在");
      require(
          !table.equals("experiments") || Set.of("mentors", "lab").contains(str(r, "visibility")),
          404,
          "NOT_FOUND",
          "对象不存在");
      require(access.canStudent(actor, owner, str(r, "lab_id"), true), 404, "NOT_FOUND", "对象不存在");
    }
    if (table.equals("experiments"))
      require(access.canStudent(actor, owner, str(r, "lab_id"), false), 404, "NOT_FOUND", "对象不存在");
    if (table.equals("reading_notes"))
      require(access.noteReadable(actor, r), 404, "NOT_FOUND", "对象不存在");
    return r;
  }

  public Map<String, Object> revision(String table, Map<String, Object> r, Integer revision) {
    String fk =
        switch (table) {
          case "experiments" -> "experiment_id";
          case "reading_notes" -> "note_id";
          default -> "report_id";
        };
    String rt =
        switch (table) {
          case "experiments" -> "experiment_revisions";
          case "reading_notes" -> "reading_note_revisions";
          default -> "weekly_report_revisions";
        };
    int n = revision == null ? num(r, "current_revision") : revision;
    require(n >= 1 && n <= num(r, "current_revision"), 404, "NOT_FOUND", "修订不存在");
    return access.found(db.one(rt, "WHERE " + fk + "=? AND revision=?", r.get("id"), n));
  }

  Map<String, Object> experimentContent(Map<String, Object> rev) {
    return map(
        "purpose",
        rev.get("purpose"),
        "parameters",
        rev.get("parameters_json"),
        "steps",
        rev.get("steps_json"),
        "results",
        rev.getOrDefault("results_text", ""),
        "reflection",
        rev.getOrDefault("reflection", ""),
        "file_ids",
        rev.get("file_ids"));
  }

  Map<String, Object> experimentSourceContent(Map<String, Object> revision) {
    var content = experimentContent(revision);
    content.put("parameters", readableValue(content.get("parameters")));
    content.put("steps", readableValue(content.get("steps")));
    content.remove("file_ids");
    return content;
  }

  private String readableValue(Object value) {
    if (value instanceof Map<?, ?> fields)
      return fields.entrySet().stream().map(e -> e.getKey() + "：" + readableValue(e.getValue()))
          .collect(java.util.stream.Collectors.joining("\n"));
    if (value instanceof List<?> lines)
      return lines.stream().map(this::readableValue).collect(java.util.stream.Collectors.joining("\n"));
    return value == null ? "" : value.toString();
  }

  boolean experimentSourceMatches(Map<String, Object> revision, String saved) {
    return json(experimentSourceContent(revision)).equals(saved)
        || json(experimentContent(revision)).equals(saved);
  }

  public Map<String, Object> data(String table, Map<String, Object> r, Integer n, boolean reviews) {
    var out = new LinkedHashMap<>(r);
    var rev = revision(table, r, n);
    out.put("revision", rev.get("revision"));
    if (table.equals("experiments")) {
      out.putAll(experimentContent(rev));
      out.put("confirmed_at", rev.get("confirmed_at"));
      out.put("source_id", null);
      out.put("source_revision", null);
      if (eq(r.get("status"), "confirmed")) {
        var s =
            db.one(
                "sources",
                "WHERE kind='experiment' AND entity_id=? AND revoked_at IS NULL",
                r.get("id"));
        if (s != null)
          for (Object number : List.of(s.get("active_revision"), rev.get("revision"))) {
            var saved =
                db.one("source_revisions", "WHERE source_id=? AND revision=?", s.get("id"), number);
            if (saved != null
                && experimentSourceMatches(rev, json(parse(str(saved, "text_snapshot"))))) {
              out.put("source_id", s.get("id"));
              out.put("source_revision", number);
              break;
            }
          }
      }
    } else if (table.equals("reading_notes")) {
      out.putAll(pick(rev, "claims", "questions", "source_refs", "confirmed_at"));
    } else {
      out.putAll(obj(rev.get("content")));
      out.put("submitted_at", rev.get("submitted_at"));
      if (reviews)
        out.put(
            "reviews",
            db
                .query("mentor_reviews", "WHERE report_id=? ORDER BY created_at,id", r.get("id"))
                .stream()
                .filter(v -> n == null || eq(v.get("report_revision"), n))
                .toList());
    }
    return out;
  }

  void task(String actor, String lab, Object id) {
    if (id == null) return;
    var t = db.get("tasks", id.toString());
    require(
        t != null && eq(t.get("lab_id"), lab) && eq(t.get("student_id"), actor),
        422,
        "VALIDATION_ERROR",
        "任务不在本人范围");
  }

  void files(String actor, String lab, Object ids) {
    var all = list(ids);
    require(new HashSet<>(all).size() == all.size(), 422, "VALIDATION_ERROR", "file_ids 不能重复");
    for (Object id : all) {
      var f = db.get("files", id.toString());
      require(
          f != null
              && eq(f.get("lab_id"), lab)
              && eq(f.get("owner_id"), actor)
              && eq(f.get("purpose"), "experiment")
              && eq(f.get("upload_status"), "uploaded"),
          422,
          "VALIDATION_ERROR",
          "实验附件无效");
    }
  }

  public Map<String, Object> reportContent(String actor, String lab, Map<String, Object> b) {
    var result = map();
    for (String section : SECTIONS) {
      var items = list(b.get(section));
      for (Object item : items) access.evidence(actor, lab, obj(item).get("source_refs"));
      result.put(section, items);
    }
    return result;
  }

  void append(String table, Map<String, Object> r, Map<String, Object> b) {
    int n = num(r, "current_revision");
    if (table.equals("experiments"))
      db.insert(
          "experiment_revisions",
          map(
              "experiment_id",
              r.get("id"),
              "revision",
              n,
              "purpose",
              b.get("purpose"),
              "parameters_json",
              b.getOrDefault("parameters", map()),
              "steps_json",
              b.getOrDefault("steps", List.of()),
              "results_text",
              b.getOrDefault("results", ""),
              "reflection",
              b.getOrDefault("reflection", ""),
              "file_ids",
              b.getOrDefault("file_ids", List.of())));
    else if (table.equals("reading_notes"))
      db.insert(
          "reading_note_revisions",
          map(
              "note_id",
              r.get("id"),
              "revision",
              n,
              "claims",
              b.getOrDefault("claims", List.of()),
              "questions",
              b.getOrDefault("questions", List.of()),
              "source_refs",
              b.getOrDefault("source_refs", List.of())));
    else
      db.insert(
          "weekly_report_revisions",
          map("report_id", r.get("id"), "revision", n, "content", pick(b, SECTIONS)));
  }

  public Map<String, Object> create(String actor, String table, Map<String, Object> b) {
    String lab = studentLab(actor);
    Map<String, Object> content = b;
    if (table.equals("experiments")) {
      task(actor, lab, b.get("task_id"));
      files(actor, lab, b.get("file_ids"));
    }
    if (table.equals("reading_notes")) {
      var p = db.get("papers", str(b, "paper_id"));
      require(
          p != null
              && eq(p.get("lab_id"), lab)
              && access.source(actor, p)
              && eq(p.get("ingest_status"), "ready"),
          422,
          "SOURCE_NOT_READY",
          "文献不可访问或尚未解析");
      task(actor, lab, b.get("task_id"));
      access.evidence(actor, lab, b.get("source_refs"));
    }
    if (table.equals("weekly_reports")) {
      db.lock("users", actor);
      require(
          LocalDate.parse(str(b, "week_start")).getDayOfWeek() == DayOfWeek.MONDAY,
          400,
          "INVALID_PERIOD",
          "week_start 必须是周一");
      content = reportContent(actor, lab, b);
    }
    Map<String, Object> values =
        map("lab_id", lab, table.equals("weekly_reports") ? "student_id" : "owner_id", actor);
    if (table.equals("experiments"))
      values.putAll(
          map("task_id", b.get("task_id"), "visibility", b.getOrDefault("visibility", "private")));
    if (table.equals("reading_notes")) values.putAll(pick(b, "paper_id", "task_id"));
    if (table.equals("weekly_reports")) {
      values.put("week_start", b.get("week_start"));
      values.putAll(content);
    }
    var r = db.insert(table, values);
    append(table, r, content);
    return data(table, r, null, false);
  }

  Map<String, Object> reportSourceContent(Map<String, Object> report) {
    var content = obj(revision("weekly_reports", report, null).get("content"));
    var readable = map("report_revision", report.get("current_revision"));
    for (String section : SECTIONS)
      readable.put(section, String.join("\n", list(content.get(section)).stream().map(raw -> str(obj(raw), "text")).toList()));
    return readable;
  }

  Map<String, Object> noteSourceContent(Map<String, Object> revision) {
    return map("claims", String.join("\n", list(revision.get("claims")).stream().map(Object::toString).toList()),
        "questions", String.join("\n", list(revision.get("questions")).stream().map(Object::toString).toList()));
  }

  public Map<String, Object> publishedReportSource(Map<String, Object> report) {
    var rev = revision("weekly_reports", report, null);
    require(rev.get("submitted_at") != null, 409, "CONTEXT_STALE", "周报当前修订尚未提交");
    var source = db.one("sources", "WHERE kind='weekly_report' AND entity_id=?", report.get("id"));
    if (source == null)
      source = db.insert("sources", map("lab_id", report.get("lab_id"), "owner_id", report.get("student_id"),
          "kind", "weekly_report", "entity_id", report.get("id"), "visibility", "mentors",
          "active_revision", 1));
    String snapshot = json(reportSourceContent(report));
    var prior = db.one("source_revisions", "WHERE source_id=? AND revision=?", source.get("id"), source.get("active_revision"));
    if (prior == null || !snapshot.equals(str(prior, "text_snapshot"))) {
      if (prior != null) source.put("active_revision", num(source, "active_revision") + 1);
      source.put("revoked_at", null);
      db.update("sources", source);
      var saved = db.insert("source_revisions", map("source_id", source.get("id"), "revision", source.get("active_revision"),
          "content_hash", hash(snapshot), "locator_scheme", "record_field", "text_snapshot", snapshot));
      chunks(saved);
    }
    return source;
  }

  public void chunks(Map<String, Object> sourceRev) {
    var fields = obj(parse(str(sourceRev, "text_snapshot")));
    int seq = 0;
    for (var entry : fields.entrySet()) {
      String text = entry.getValue() instanceof String s ? s : json(entry.getValue());
      if (text.isBlank()) continue;
      for (int offset = 0; offset < text.length() && seq < 1000; offset += 2000) {
        String chunk = text.substring(offset, Math.min(offset + 2000, text.length()));
        db.insert(
            "chunks",
            map(
                "source_id",
                sourceRev.get("source_id"),
                "source_revision",
                sourceRev.get("revision"),
                "chunk_id",
                "field:" + entry.getKey() + ":" + (seq++),
                "text",
                chunk,
                "locator",
                map("kind", "record_field", "field", entry.getKey())));
      }
    }
  }

  public Object handle(
      String method, String path, String actor, Map<String, Object> b, Map<String, Object> q) {
    if (path.startsWith("/weekly-reports") && q.get("status") != null)
      require(
          Set.of("draft", "submitted", "reviewed", "changes_requested").contains(str(q, "status")),
          422,
          "VALIDATION_ERROR",
          "周报状态无效");
    String[] p = path.split("/");
    String table =
        switch (p[1]) {
          case "experiments" -> "experiments";
          case "notes" -> "reading_notes";
          case "weekly-reports" -> "weekly_reports";
          default -> "mentor_review_drafts";
        };
    Integer requested =
        q.get("revision") == null ? null : Integer.valueOf(q.get("revision").toString());
    if (table.equals("mentor_review_drafts")) {
      var r = access.found(db.lock(table, p[2]));
      require(eq(r.get("mentor_id"), actor), 404, "NOT_FOUND", "草稿不存在");
      var report = access.found(db.get("weekly_reports", str(r, "report_id")));
      require(
          access.canStudent(actor, str(report, "student_id"), str(r, "lab_id"), true),
          404,
          "NOT_FOUND",
          "草稿不存在");
      if (method.equals("PATCH")) {
        db.version(r, b);
        require(
            eq(report.get("current_revision"), r.get("report_revision"))
                && Set.of("submitted", "changes_requested").contains(str(report, "status")),
            409,
            "VERSION_CONFLICT",
            "周报版本已变化");
        r.put("body", b.get("body"));
        db.bump(table, r);
      }
      return r;
    }
    if (p.length == 2) {
      if (method.equals("POST")) return create(actor, table, b);
      if (table.equals("reading_notes")) {
        var paper = access.found(db.get("papers", str(q, "paper_id")));
        require(access.source(actor, paper), 404, "NOT_FOUND", "文献不存在");
        if (q.get("student_id") != null)
          require(
              access.canStudent(actor, str(q, "student_id"), str(paper, "lab_id"), false),
              404,
              "NOT_FOUND",
              "笔记范围不存在");
      }
      var scopes = access.scopes(actor, str(q, "student_id"));
      String owner = table.equals("weekly_reports") ? "student_id" : "owner_id";
      if (q.get("task_id") != null) {
        var t = access.found(db.get("tasks", str(q, "task_id")));
        require(access.inScopes(t, scopes, "student_id"), 404, "NOT_FOUND", "任务不存在");
      }
      var rows =
          db.all(table).stream()
              .filter(r -> access.inScopes(r, scopes, owner))
              .filter(r -> !table.equals("reading_notes") || access.noteReadable(actor, r))
              .filter(
                  r -> !table.equals("reading_notes") || eq(r.get("paper_id"), q.get("paper_id")))
              .filter(
                  r ->
                      eq(r.get(owner), actor)
                          || (!eq(r.get("status"), "draft")
                              && (!table.equals("experiments")
                                  || Set.of("mentors", "lab").contains(str(r, "visibility")))))
              .filter(r -> q.get("task_id") == null || eq(r.get("task_id"), q.get("task_id")))
              .filter(
                  r -> q.get("week_start") == null || eq(r.get("week_start"), q.get("week_start")))
              .filter(r -> q.get("status") == null || eq(r.get("status"), q.get("status")))
              .toList();
      String key =
          switch (table) {
            case "experiments" -> "experiments";
            case "reading_notes" -> "items";
            default -> "reports";
          };
      var page = page(rows, q, key, "created_at");
      page.put(
          key,
          list(page.get(key)).stream()
              .map(
                  raw -> {
                    var d = data(table, obj(raw), null, false);
                    return table.equals("experiments")
                        ? pick(
                            d,
                            "id",
                            "task_id",
                            "status",
                            "visibility",
                            "current_revision",
                            "version",
                            "created_at",
                            "purpose",
                            "confirmed_at")
                        : d;
                  })
              .toList());
      return page;
    }
    boolean reviewing = p.length == 4 && p[3].equals("reviews");
    var r = record(actor, table, p[2], !method.equals("GET") && !reviewing);
    if (method.equals("GET")) {
      var rev = revision(table, r, requested);
      if (table.equals("reading_notes"))
        require(
            access.referencesReadable(actor, rev.get("source_refs")), 404, "NOT_FOUND", "对象不存在");
      if (table.equals("weekly_reports") && !eq(actor, r.get("student_id")))
        require(rev.get("submitted_at") != null, 404, "NOT_FOUND", "周报修订尚未发布");
      var detail = data(table, r, requested, true);
      if (table.equals("weekly_reports") && !eq(actor, r.get("student_id")))
        detail.put("review_draft", db.one("mentor_review_drafts",
            "WHERE report_id=? AND report_revision=? AND mentor_id=?", r.get("id"), rev.get("revision"), actor));
      return detail;
    }
    db.version(r, b);
    String lab = str(r, "lab_id");
    if (method.equals("PATCH")) {
      Map<String, Object> content = b;
      if (table.equals("weekly_reports")) {
        require(
            Set.of("draft", "changes_requested").contains(str(r, "status")),
            409,
            "INVALID_STATE",
            "当前周报不可编辑");
        content = reportContent(actor, lab, b);
        r.putAll(content);
      }
      if (table.equals("experiments")) files(actor, lab, b.get("file_ids"));
      if (table.equals("reading_notes")) access.evidence(actor, lab, b.get("source_refs"));
      r.put("current_revision", num(r, "current_revision") + 1);
      r.put("status", "draft");
      db.bump(table, r);
      append(table, r, content);
    } else if (reviewing) {
      access.student(actor, str(r, "student_id"), lab, true);
      require(
          eq(r.get("status"), "submitted")
              && eq(r.get("current_revision"), b.get("report_revision")),
          409,
          "INVALID_STATE",
          "周报版本或状态不可审阅");
      var rev = revision(table, r, null);
      require(rev.get("submitted_at") != null, 409, "INVALID_STATE", "周报修订尚未提交");
      var review =
          db.insert(
              "mentor_reviews",
              map(
                  "report_id",
                  r.get("id"),
                  "report_revision",
                  b.get("report_revision"),
                  "mentor_id",
                  actor,
                  "decision",
                  b.get("decision"),
                  "body",
                  b.get("body")));
      r.put("status", b.get("decision"));
      db.bump(table, r);
      return map("review", review, "report", data(table, r, null, true));
    } else if (table.equals("weekly_reports")) {
      require(eq(r.get("status"), "draft"), 409, "INVALID_STATE", "当前周报不可提交");
      reportContent(actor, lab, r);
      var rev = revision(table, r, null);
      rev.put("submitted_at", now());
      db.update("weekly_report_revisions", rev);
      r.put("status", "submitted");
      db.bump(table, r);
      db.outbox(
          "weekly_report.submitted",
          map("report_id", r.get("id"), "revision", r.get("current_revision")));
    } else {
      require(eq(r.get("status"), "draft"), 409, "INVALID_STATE", "记录已经确认");
      var rev = revision(table, r, null);
      if (table.equals("reading_notes")) access.evidence(actor, lab, rev.get("source_refs"));
      if (table.equals("experiments")) {
        require(
            !Optional.ofNullable(str(rev, "purpose")).orElse("").isBlank(),
            422,
            "EXPERIMENT_PURPOSE_REQUIRED",
            "实验目的不能为空");
        require(
            !Optional.ofNullable(str(rev, "results_text")).orElse("").isBlank(),
            422,
            "EXPERIMENT_RESULTS_REQUIRED",
            "确认实验前请填写实验结果");
        files(actor, lab, rev.get("file_ids"));
        if (r.get("task_id") != null) {
          var task = access.found(db.get("tasks", str(r, "task_id")));
          require(
              eq(task.get("student_id"), actor) && eq(task.get("lab_id"), lab),
              404,
              "NOT_FOUND",
              "关联任务不存在");
        }
      }
      rev.put("confirmed_at", now());
      db.update(
          table.equals("experiments") ? "experiment_revisions" : "reading_note_revisions", rev);
      r.put("status", "confirmed");
      db.bump(table, r);
      String kind = table.equals("experiments") ? "experiment" : "note";
      var s = db.one("sources", "WHERE kind=? AND entity_id=?", kind, r.get("id"));
      if (s == null)
        s =
            db.insert(
                "sources",
                map(
                    "lab_id",
                    lab,
                    "owner_id",
                    actor,
                    "kind",
                    kind,
                    "entity_id",
                    r.get("id"),
                    "visibility",
                    table.equals("experiments") ? r.get("visibility") : "mentors",
                    "active_revision",
                    r.get("current_revision")));
      else {
        s.put("active_revision", r.get("current_revision"));
        s.put("revoked_at", null);
        if (table.equals("experiments")) s.put("visibility", r.get("visibility"));
        db.update("sources", s);
      }
      String snapshot =
          json(
              table.equals("experiments")
                  ? experimentSourceContent(rev)
                  : noteSourceContent(rev));
      var sr =
          db.one(
              "source_revisions",
              "WHERE source_id=? AND revision=?",
              s.get("id"),
              r.get("current_revision"));
      if (sr == null) {
        sr =
            db.insert(
                "source_revisions",
                map(
                    "source_id",
                    s.get("id"),
                    "revision",
                    r.get("current_revision"),
                    "content_hash",
                    hash(snapshot),
                    "locator_scheme",
                    "record_field",
                    "text_snapshot",
                    snapshot));
        chunks(sr);
      }
      db.outbox(
          kind + ".confirmed",
          map(
              kind + "_id",
              r.get("id"),
              "source_id",
              s.get("id"),
              "revision",
              r.get("current_revision")));
      var out = data(table, r, null, false);
      out.put("source_id", s.get("id"));
      if (table.equals("experiments")) out.put("source_revision", sr.get("revision"));
      return out;
    }
    return data(table, r, null, false);
  }
}
