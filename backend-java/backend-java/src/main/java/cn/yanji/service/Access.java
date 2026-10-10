package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.util.*;

@org.springframework.stereotype.Service
public class Access {
  final Repository db;

  public Access(Repository db) {
    this.db = db;
  }

  public Map<String, Object> found(Map<String, Object> row) {
    if (row == null) throw new AppError(404, "NOT_FOUND", "对象不存在");
    return row;
  }

  public boolean member(String actor, String lab, String role) {
    Map<String, Object> l = db.get("labs", lab);
    return l != null
        && eq(l.get("status"), "active")
        && db
            .query("memberships", "WHERE user_id=? AND lab_id=? AND active=TRUE", actor, lab)
            .stream()
            .anyMatch(m -> role == null || eq(m.get("role"), role));
  }

  public boolean canStudent(String actor, String student, String lab, boolean mentorOnly) {
    return member(student, lab, "student")
        && ((!mentorOnly && eq(actor, student))
            || (member(actor, lab, "mentor")
                && db.one(
                        "mentorships",
                        "WHERE lab_id=? AND student_id=? AND mentor_id=? AND active=TRUE",
                        lab,
                        student,
                        actor)
                    != null));
  }

  public void student(String actor, String student, String lab, boolean mentorOnly) {
    require(canStudent(actor, student, lab, mentorOnly), 403, "FORBIDDEN", "无权访问该学生");
  }

  public String lab(String actor, String student, boolean mentorOnly) {
    for (Map<String, Object> m :
        db.query("memberships", "WHERE user_id=? AND role='student' AND active=TRUE", student))
      if (canStudent(actor, student, str(m, "lab_id"), mentorOnly)) return str(m, "lab_id");
    throw new AppError(403, "FORBIDDEN", "无权访问该学生");
  }

  public Map<String, Object> visible(String actor, String table, String id) {
    Map<String, Object> r = found(db.lock(table, id));
    require(
        canStudent(actor, str(r, "student_id"), str(r, "lab_id"), false),
        404,
        "NOT_FOUND",
        "对象不存在");
    return r;
  }

  public boolean source(String actor, Map<String, Object> row) {
    return source(actor, row, new HashSet<>());
  }

  private boolean basicSource(String actor, Map<String, Object> row) {
    if (row == null || row.get("revoked_at") != null || !member(actor, str(row, "lab_id"), null))
      return false;
    return eq(actor, row.get("owner_id"))
        || eq(row.get("visibility"), "lab")
        || (eq(row.get("visibility"), "mentors")
            && canStudent(actor, str(row, "owner_id"), str(row, "lab_id"), true));
  }

  private boolean source(String actor, Map<String, Object> row, Set<String> active) {
    if (!basicSource(actor, row)) return false;
    if (row.get("kind") == null) return true; // Paper rows themselves carry visibility.
    String id = str(row, "id");
    if (!active.add(id)) return false;
    try {
      String kind = str(row, "kind");
      if (kind.equals("weekly_report")) {
        var report = db.get("weekly_reports", str(row, "entity_id"));
        if (report == null || !eq(report.get("student_id"), row.get("owner_id"))
            || !eq(report.get("lab_id"), row.get("lab_id"))
            || !canStudent(actor, str(report, "student_id"), str(report, "lab_id"), false)) return false;
        var revision = db.one("weekly_report_revisions", "WHERE report_id=? AND revision=?",
            report.get("id"), report.get("current_revision"));
        var saved = db.one("source_revisions", "WHERE source_id=? AND revision=?", row.get("id"), row.get("active_revision"));
        return revision != null && revision.get("submitted_at") != null
            && saved != null && eq(obj(parse(str(saved, "text_snapshot"))).get("report_revision"), report.get("current_revision"))
            && !eq(report.get("status"), "draft");
      }
      if (kind.equals("paper")) return basicSource(actor, db.get("papers", str(row, "entity_id")));
      if (kind.equals("thesis_feedback")) {
        var feedback = db.get("thesis_feedback", str(row, "entity_id"));
        var revision =
            feedback == null ? null : db.get("thesis_revisions", str(feedback, "revision_id"));
        var thesis = revision == null ? null : db.get("theses", str(revision, "thesis_id"));
        return thesis != null
            && eq(thesis.get("student_id"), row.get("owner_id"))
            && eq(thesis.get("lab_id"), row.get("lab_id"))
            && canStudent(actor, str(thesis, "student_id"), str(thesis, "lab_id"), false)
            && basicSource(actor, db.get("papers", str(revision, "paper_id")));
      }
      if (kind.equals("experiment")) {
        var record = db.get("experiments", str(row, "entity_id"));
        return record != null
            && basicSource(actor, record)
            && (eq(actor, record.get("owner_id")) || eq(record.get("status"), "confirmed"));
      }
      if (Set.of("note", "reading_note").contains(kind)) {
        var note = db.get("reading_notes", str(row, "entity_id"));
        return noteReadable(actor, note, active);
      }
      return true;
    } finally {
      active.remove(id);
    }
  }

  public boolean noteReadable(String actor, Map<String, Object> note) {
    return noteReadable(actor, note, new HashSet<>());
  }

  private boolean noteReadable(String actor, Map<String, Object> note, Set<String> active) {
    if (note == null || !canStudent(actor, str(note, "owner_id"), str(note, "lab_id"), false))
      return false;
    if (!eq(actor, note.get("owner_id")) && !eq(note.get("status"), "confirmed")) return false;
    if (!basicSource(actor, db.get("papers", str(note, "paper_id")))) return false;
    var revision =
        db.one(
            "reading_note_revisions",
            "WHERE note_id=? AND revision=?",
            note.get("id"),
            note.get("current_revision"));
    return revision != null && referencesReadable(actor, revision.get("source_refs"), active);
  }

  public boolean referencesReadable(String actor, Object value) {
    return referencesReadable(actor, value, new HashSet<>());
  }

  private boolean referencesReadable(String actor, Object value, Set<String> active) {
    if (value instanceof Map<?, ?>) {
      var node = obj(value);
      if (node.get("source_id") != null
          && !source(actor, db.get("sources", str(node, "source_id")), active)) return false;
      for (Object child : node.values())
        if (!referencesReadable(actor, child, active)) return false;
    } else if (value instanceof List<?> children) {
      for (Object child : children) if (!referencesReadable(actor, child, active)) return false;
    }
    return true;
  }

  public boolean runReadable(String actor, Map<String, Object> run) {
    // No publication API exists for raw analyses. Binding alone must never publish derived text.
    if (run == null
        || !eq(run.get("requested_by"), actor)
        || !canStudent(actor, str(run, "student_id"), str(run, "lab_id"), false)) return false;
    var requester = db.get("users", actor);
    if (requester == null || !eq(requester.get("status"), "active")) return false;
    var snapshot = db.get("context_snapshots", str(run, "snapshot_id"));
    if (snapshot != null
        && (!referencesReadable(actor, snapshot.get("source_versions"))
            || !referencesReadable(actor, snapshot.get("authorized_scope")))) return false;
    String paperId = str(obj(run.get("subject")), "paper_id");
    if (paperId != null && !source(actor, db.get("papers", paperId))) return false;
    if (!referencesReadable(actor, run.get("result"))) return false;
    for (var proposal : db.query("proposals", "WHERE run_id=?", run.get("id")))
      if (!referencesReadable(actor, proposal)) return false;
    return true;
  }

  public List<Map<String, Object>> scopes(String actor, String student) {
    List<Map<String, Object>> out =
        db.all("memberships").stream()
            .filter(
                m ->
                    eq(m.get("role"), "student")
                        && eq(m.get("active"), true)
                        && (student == null || eq(m.get("user_id"), student))
                        && canStudent(actor, str(m, "user_id"), str(m, "lab_id"), false))
            .toList();
    require(!out.isEmpty(), 403, "FORBIDDEN", "无可访问的学生范围");
    return out;
  }

  public boolean inScopes(Map<String, Object> row, List<Map<String, Object>> scopes, String owner) {
    return scopes.stream()
        .anyMatch(
            s -> eq(s.get("lab_id"), row.get("lab_id")) && eq(s.get("user_id"), row.get(owner)));
  }

  public void evidence(String actor, String lab, Object references) {
    for (Object raw : list(references)) {
      Map<String, Object> ref = obj(raw), s = db.get("sources", str(ref, "source_id"));
      require(source(actor, s) && eq(s.get("lab_id"), lab), 404, "NOT_FOUND", "来源不存在");
      Map<String, Object> rev =
          db.one(
              "source_revisions",
              "WHERE source_id=? AND revision=?",
              s.get("id"),
              ref.get("source_revision"));
      Map<String, Object> locator = obj(ref.get("locator"));
      require(
          rev != null
              && eq(rev.get("locator_scheme"), locator.get("kind"))
              && rev.get("text_snapshot") != null,
          422,
          "SOURCE_NOT_READY",
          "引用来源尚未就绪");
      String text = sourceExcerpt(rev, locator, null);
      require(
          text.contains(String.valueOf(ref.get("quote"))), 422, "VALIDATION_ERROR", "引用片段与来源内容不匹配");
    }
  }

  public String sourceExcerpt(
      Map<String, Object> revision, Map<String, Object> locator, String chunkId) {
    require(
        eq(locator.get("kind"), revision.get("locator_scheme")),
        422,
        "VALIDATION_ERROR",
        "locator 与来源类型不符");
    var chunks =
        db
            .query(
                "chunks",
                "WHERE source_id=? AND source_revision=?",
                revision.get("source_id"),
                revision.get("revision"))
            .stream()
            .filter(c -> sameLocator(obj(c.get("locator")), locator))
            .filter(c -> chunkId == null || eq(c.get("chunk_id"), chunkId))
            .sorted(
                Comparator.<Map<String, Object>>comparingInt(c -> chunkOrder(str(c, "chunk_id")))
                    .thenComparing(c -> str(c, "chunk_id")))
            .toList();
    if (!chunks.isEmpty())
      return chunks.stream()
          .map(c -> str(c, "text"))
          .collect(java.util.stream.Collectors.joining());
    require(
        chunkId == null && eq(locator.get("kind"), "record_field"), 404, "NOT_FOUND", "位置或分块不存在");
    return recordExcerpt(str(revision, "text_snapshot"), locator);
  }

  static boolean sameLocator(Map<String, Object> left, Map<String, Object> right) {
    var a = new LinkedHashMap<>(left);
    var b = new LinkedHashMap<>(right);
    a.values().removeIf(Objects::isNull);
    b.values().removeIf(Objects::isNull);
    return a.equals(b);
  }

  static int chunkOrder(String id) {
    try {
      return Integer.parseInt(id.substring(id.lastIndexOf(':') + 1));
    } catch (RuntimeException ignored) {
      return 0;
    }
  }

  public String recordExcerpt(String text, Map<String, Object> locator) {
    Map<String, Object> fields;
    try {
      fields = obj(parse(text));
    } catch (Exception e) {
      return text;
    }
    require(
        eq(locator.get("kind"), "record_field") && fields.containsKey(str(locator, "field")),
        404,
        "NOT_FOUND",
        "来源字段不存在");
    Object v = fields.get(str(locator, "field"));
    return v instanceof String s ? s : json(v);
  }
}
