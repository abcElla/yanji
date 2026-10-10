package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.util.*;

@org.springframework.stereotype.Service
public class ThesisService {
  final Repository db;
  final Access access;
  final FileSourceService files;
  final RecordService records;

  public ThesisService(
      Repository db, Access access, FileSourceService files, RecordService records) {
    this.db = db;
    this.access = access;
    this.files = files;
    this.records = records;
  }

  public Map<String, Object> thesis(String actor, String id) {
    return access.visible(actor, "theses", id);
  }

  public void authorizeWrite(String actor, String path, Map<String, Object> body) {
    String[] p = path.split("/");
    if (p.length == 2) {
      require(
          access.member(actor, str(body, "lab_id"), "student"),
          403,
          "FORBIDDEN",
          "仅学生可在自己的活动实验室创建论文");
      return;
    }
    var t = thesis(actor, p[2]);
    if (p.length == 6 && p[5].equals("feedback")) {
      revision(t, p[4]);
      require(
          access.canStudent(actor, str(t, "student_id"), str(t, "lab_id"), true),
          403,
          "FORBIDDEN",
          "仅绑定导师可发布修改意见");
    } else {
      require(eq(actor, t.get("student_id")), 403, "FORBIDDEN", "仅学生本人可发布稿件");
    }
  }

  Map<String, Object> revision(Map<String, Object> thesis, String id) {
    var r = access.found(db.get("thesis_revisions", id));
    require(eq(r.get("thesis_id"), thesis.get("id")), 404, "NOT_FOUND", "稿件不存在");
    return r;
  }

  Map<String, Object> feedbackData(Map<String, Object> f) {
    var out = pick(f, "id", "mentor_id", "content", "decision", "created_at");
    out.put("mentor_name", db.get("users", str(f, "mentor_id")).get("display_name"));
    return out;
  }

  Map<String, Object> revisionData(Map<String, Object> r) {
    var out =
        pick(
            r,
            "id",
            "thesis_id",
            "revision_number",
            "file_id",
            "paper_id",
            "change_summary",
            "created_at");
    var paper = db.get("papers", str(r, "paper_id"));
    out.put("source_id", paper.get("source_id"));
    out.put("ingest_status", paper.get("ingest_status"));
    out.put(
        "feedback",
        db
            .query(
                "thesis_feedback",
                "WHERE revision_id=? ORDER BY created_at ASC,id ASC",
                r.get("id"))
            .stream()
            .map(this::feedbackData)
            .toList());
    return out;
  }

  public Map<String, Object> download(String actor, String thesisId, String revisionId) {
    var t = thesis(actor, thesisId);
    var r = revision(t, revisionId);
    return access.found(db.get("files", str(r, "file_id")));
  }

  public Object handle(
      String method, String path, String actor, Map<String, Object> b, Map<String, Object> q) {
    String[] p = path.split("/");
    if (p.length == 2) {
      if (method.equals("GET")) {
        var scopes = access.scopes(actor, str(q, "student_id"));
        return map(
            "items",
            db.query("theses", "ORDER BY created_at DESC,id DESC").stream()
                .filter(t -> access.inScopes(t, scopes, "student_id"))
                .toList());
      }
      authorizeWrite(actor, path, b);
      require(!str(b, "title").isBlank(), 422, "VALIDATION_ERROR", "论文标题不能为空");
      return db.insert(
          "theses",
          map("student_id", actor, "lab_id", b.get("lab_id"), "title", str(b, "title").trim()));
    }
    var t = thesis(actor, p[2]);
    if (p.length == 3) {
      var out = new LinkedHashMap<>(t);
      out.put(
          "revisions",
          db
              .query(
                  "thesis_revisions",
                  "WHERE thesis_id=? ORDER BY revision_number DESC",
                  t.get("id"))
              .stream()
              .map(this::revisionData)
              .toList());
      return out;
    }
    authorizeWrite(actor, path, b);
    if (p.length == 4) {
      var f = files.file(actor, str(b, "file_id"));
      require(
          eq(f.get("owner_id"), actor)
              && eq(f.get("lab_id"), t.get("lab_id"))
              && eq(f.get("purpose"), "paper")
              && eq(f.get("upload_status"), "uploaded"),
          403,
          "FORBIDDEN",
          "只能发布本人在同一实验室上传的论文文件");
      db.lock("files", str(f, "id"));
      var existing = db.one("thesis_revisions", "WHERE file_id=?", f.get("id"));
      if (existing != null) {
        require(
            eq(existing.get("thesis_id"), t.get("id")),
            409,
            "FILE_ALREADY_PUBLISHED",
            "该文件已属于其他论文");
        require(
            eq(existing.get("change_summary"), str(b, "change_summary").trim()),
            409,
            "IDEMPOTENCY_CONFLICT",
            "该文件已发布，请上传新的修改稿");
        return revisionData(existing);
      }
      db.version(t, b);
      require(
          db.one("papers", "WHERE file_id=? AND revoked_at IS NULL", f.get("id")) == null,
          409,
          "PAPER_EXISTS",
          "文件已有文献记录，请上传论文稿件文件");
      int number = num(t, "version");
      String suffix = "（第" + number + "稿）";
      String title = str(t, "title");
      String paperTitle =
          title.substring(0, Math.min(title.length(), 500 - suffix.length())) + suffix;
      var paper =
          db.insert(
              "papers",
              map(
                  "lab_id",
                  t.get("lab_id"),
                  "owner_id",
                  actor,
                  "title",
                  paperTitle,
                  "file_id",
                  f.get("id"),
                  "visibility",
                  "mentors"));
      db.outbox(
          "paper.ingest_requested",
          map("paper_id", paper.get("id"), "file_id", f.get("id"), "revision", 1));
      var r =
          db.insert(
              "thesis_revisions",
              map(
                  "thesis_id",
                  t.get("id"),
                  "revision_number",
                  number,
                  "file_id",
                  f.get("id"),
                  "paper_id",
                  paper.get("id"),
                  "change_summary",
                  str(b, "change_summary").trim()));
      db.bump("theses", t);
      return revisionData(r);
    }
    var r = revision(t, p[4]);
    require(!str(b, "content").isBlank(), 422, "VALIDATION_ERROR", "修改意见不能为空");
    var f =
        db.insert(
            "thesis_feedback",
            map(
                "revision_id",
                r.get("id"),
                "mentor_id",
                actor,
                "content",
                str(b, "content").trim(),
                "decision",
                b.get("decision")));
    var source =
        db.insert(
            "sources",
            map(
                "lab_id",
                t.get("lab_id"),
                "owner_id",
                t.get("student_id"),
                "kind",
                "thesis_feedback",
                "entity_id",
                f.get("id"),
                "visibility",
                "mentors",
                "active_revision",
                1));
    // Use persisted timestamp precision so live source validation agrees with the immutable
    // snapshot.
    f = db.get("thesis_feedback", str(f, "id"));
    String snapshot = json(pick(f, "content", "decision", "mentor_id", "created_at"));
    var sr =
        db.insert(
            "source_revisions",
            map(
                "source_id",
                source.get("id"),
                "revision",
                1,
                "content_hash",
                hash(snapshot),
                "locator_scheme",
                "record_field",
                "text_snapshot",
                snapshot));
    records.chunks(sr);
    db.outbox(
        "thesis_feedback.created",
        map("feedback_id", f.get("id"), "source_id", source.get("id"), "revision", 1));
    return feedbackData(f);
  }
}
