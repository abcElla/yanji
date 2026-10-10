package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@org.springframework.stereotype.Service
public class FileSourceService {
  final Repository db;
  final Access access;
  public final StorageService storage;
  static final String[] FILE = {
    "id",
    "lab_id",
    "owner_id",
    "client_upload_id",
    "purpose",
    "sha256",
    "size",
    "mime",
    "upload_status",
    "scan_status",
    "created_at"
  };

  public FileSourceService(Repository db, Access access, StorageService storage) {
    this.db = db;
    this.access = access;
    this.storage = storage;
  }

  public Map<String, Object> file(String actor, String id) {
    var f = access.found(db.get("files", id));
    require(access.member(actor, str(f, "lab_id"), null), 404, "NOT_FOUND", "文件不存在");
    require(
        eq(f.get("owner_id"), actor)
            || db.query("papers", "WHERE file_id=? AND revoked_at IS NULL", id).stream()
                .anyMatch(p -> access.source(actor, p)),
        404,
        "NOT_FOUND",
        "文件不存在");
    return f;
  }

  public Map<String, Object> paper(String actor, String id, boolean owner) {
    var p = access.found(db.lock("papers", id));
    require(
        access.source(actor, p) && (!owner || eq(p.get("owner_id"), actor)),
        404,
        "NOT_FOUND",
        "文献不存在");
    return p;
  }

  public Map<String, Object> paperData(Map<String, Object> p) {
    return pick(
        p,
        "id",
        "lab_id",
        "owner_id",
        "title",
        "authors",
        "publication_year",
        "doi",
        "file_id",
        "visibility",
        "ingest_status",
        "ingest_error_code",
        "source_id",
        "version",
        "created_at");
  }

  public Object upload(String actor, MultipartFile file, Map<String, Object> form, String key)
      throws IOException {
    String lab = str(form, "lab_id"),
        purpose = str(form, "purpose"),
        client = str(form, "client_upload_id");
    require(access.member(actor, lab, null), 403, "FORBIDDEN", "无权访问实验室");
    require(
        Set.of("paper", "experiment", "report").contains(purpose),
        422,
        "VALIDATION_ERROR",
        "purpose 无效");
    require(
        key != null && !key.isBlank() && key.length() <= 100,
        422,
        "VALIDATION_ERROR",
        "Idempotency-Key 必填，最多100字符");
    UUID.fromString(client);
    String name =
        Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
    Map<String, String> mime =
        Map.of(
            ".pdf",
            "application/pdf",
            ".docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".png",
            "image/png",
            ".jpg",
            "image/jpeg",
            ".jpeg",
            "image/jpeg");
    require(
        (purpose.equals("experiment") ? Set.of(".png", ".jpg", ".jpeg") : Set.of(".pdf", ".docx"))
                .contains(ext)
            && eq(mime.get(ext), file.getContentType()),
        415,
        "UNSUPPORTED_FILE_TYPE",
        "文件扩展名或 MIME 不支持");
    int max = purpose.equals("experiment") ? 10 * 1024 * 1024 : 20 * 1024 * 1024;
    require(file.getSize() <= max, 413, "FILE_TOO_LARGE", "文件超出大小限制");
    byte[] bytes;
    try (InputStream in = file.getInputStream()) {
      bytes = in.readNBytes(max + 1);
    }
    require(bytes.length <= max, 413, "FILE_TOO_LARGE", "文件超出大小限制");
    require(bytes.length > 0, 422, "VALIDATION_ERROR", "文件不能为空");
    boolean valid =
        switch (ext) {
          case ".pdf" -> starts(bytes, new byte[] {37, 80, 68, 70, 45});
          case ".png" -> starts(bytes, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
          case ".jpg", ".jpeg" -> starts(bytes, new byte[] {(byte) 255, (byte) 216, (byte) 255});
          default -> docx(bytes);
        };
    require(valid, 415, "UNSUPPORTED_FILE_TYPE", "文件内容与类型不符");
    String sha = hash(bytes), digest = hash(lab + ":" + client + ":" + purpose + ":" + sha);
    db.lock("users", actor);
    var prior =
        db.one(
            "idempotency_records",
            "WHERE actor_id=? AND method='POST' AND path='/api/v1/files' AND `key`=?",
            actor,
            key);
    if (prior != null) {
      require(eq(prior.get("body_hash"), digest), 409, "IDEMPOTENCY_CONFLICT", "同一幂等键对应不同请求");
      var r = obj(prior.get("response"));
      file(actor, str(r, "file_id"));
      return r;
    }
    var existing =
        db.one("files", "WHERE owner_id=? AND lab_id=? AND client_upload_id=?", actor, lab, client);
    if (existing != null) {
      require(
          eq(existing.get("sha256"), sha) && eq(existing.get("purpose"), purpose),
          409,
          "IDEMPOTENCY_CONFLICT",
          "上传标识已用于其他文件");
      var replay =
          map(
              "file_id",
              existing.get("id"),
              "upload_status",
              existing.get("upload_status"),
              "scan_status",
              existing.get("scan_status"));
      db.insert(
          "idempotency_records",
          map(
              "actor_id",
              actor,
              "method",
              "POST",
              "path",
              "/api/v1/files",
              "key",
              key,
              "body_hash",
              digest,
              "response",
              replay,
              "expires_at",
              java.time.Instant.now().plusSeconds(604800).toString()));
      return replay;
    }
    String object = uid().replace("-", "");
    String objectKey = object.substring(0, 2) + "/" + object.substring(2);
    storage.put(objectKey, bytes, file.getContentType());
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) storage.delete(objectKey);
          }
        });
    var f =
        db.insert(
            "files",
            map(
                "lab_id",
                lab,
                "owner_id",
                actor,
                "object_key",
                objectKey,
                "client_upload_id",
                client,
                "purpose",
                purpose,
                "sha256",
                sha,
                "size",
                bytes.length,
                "mime",
                file.getContentType(),
                "upload_status",
                "uploaded",
                "scan_status",
                "skipped"));
    var response =
        map(
            "file_id",
            f.get("id"),
            "upload_status",
            f.get("upload_status"),
            "scan_status",
            f.get("scan_status"));
    db.insert(
        "idempotency_records",
        map(
            "actor_id",
            actor,
            "method",
            "POST",
            "path",
            "/api/v1/files",
            "key",
            key,
            "body_hash",
            digest,
            "response",
            response,
            "expires_at",
            java.time.Instant.now().plusSeconds(604800).toString()));
    return response;
  }

  static boolean starts(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length
        && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
  }

  static boolean docx(byte[] bytes) {
    boolean types = false, document = false;
    int count = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (++count > 10000) return false;
        types |= e.getName().equals("[Content_Types].xml");
        document |= e.getName().equals("word/document.xml");
        long size = 0;
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zip.read(buffer)) != -1) {
          size += n;
          if (size > 20 * 1024 * 1024) return false;
        }
      }
      return types && document;
    } catch (IOException e) {
      return false;
    }
  }

  public Map<String, Object> sourceDetail(
      String actor, String id, Integer revision, Map<String, Object> locator, boolean internal) {
    return sourceDetail(actor, id, revision, locator, internal, null);
  }

  public Map<String, Object> sourceDetail(
      String actor,
      String id,
      Integer revision,
      Map<String, Object> locator,
      boolean internal,
      String chunkId) {
    var s = access.found(db.get("sources", id));
    require(access.source(actor, s), 404, "NOT_FOUND", "来源不存在");
    String title = str(s, "kind");
    if (eq(s.get("kind"), "weekly_report")) {
      var report = db.get("weekly_reports", str(s, "entity_id"));
      title = report == null ? "学生周报" : report.get("week_start") + " 周报";
    }
    if (eq(s.get("kind"), "paper")) title = str(paper(actor, str(s, "entity_id"), false), "title");
    int n = revision == null ? num(s, "active_revision") : revision;
    require(n >= 1 && n <= num(s, "active_revision"), 404, "NOT_FOUND", "修订不存在");
    var rev = access.found(db.one("source_revisions", "WHERE source_id=? AND revision=?", id, n));
    if (Set.of("note", "reading_note").contains(str(s, "kind")))
      require(
          access.referencesReadable(actor, parse(str(rev, "text_snapshot"))),
          404,
          "NOT_FOUND",
          "来源不存在");
    String excerpt = Optional.ofNullable(str(rev, "text_snapshot")).orElse("");
    if (locator != null) {
      require(
          eq(locator.get("kind"), rev.get("locator_scheme")),
          422,
          "VALIDATION_ERROR",
          "locator 与来源类型不符");
      excerpt = access.sourceExcerpt(rev, locator, chunkId);
    }
    require(chunkId == null || locator != null, 422, "VALIDATION_ERROR", "chunk_id 需要 locator");
    int length = internal ? 3000 : 1000;
    return map(
        internal ? "source_id" : "id",
        id,
        internal ? "source_revision" : "revision",
        n,
        "kind",
        s.get("kind"),
        "title",
        title,
        "excerpt",
        internal && locator != null
            ? excerpt
            : excerpt.substring(0, Math.min(length, excerpt.length())),
        "chunk_id",
        chunkId,
        "locator",
        locator,
        "locator_scheme",
        rev.get("locator_scheme"),
        "file_id",
        rev.get("file_id"),
        "content_hash",
        rev.get("content_hash"));
  }

  public Object handle(
      String method, String path, String actor, Map<String, Object> b, Map<String, Object> q) {
    String[] p = path.split("/");
    if (p[1].equals("files")) return pick(file(actor, p[2]), FILE);
    if (p[1].equals("sources")) {
      Map<String, Object> loc = null;
      if (q.get("locator") != null) {
        Object parsed;
        try {
          parsed = parse(str(q, "locator"));
        } catch (Exception e) {
          throw new AppError(422, "VALIDATION_ERROR", "locator 必须是 JSON 对象");
        }
        require(parsed instanceof Map, 422, "VALIDATION_ERROR", "locator 必须是 JSON 对象");
        loc = obj(parsed);
      }
      return sourceDetail(
          actor,
          p[2],
          q.get("revision") == null ? null : Integer.valueOf(q.get("revision").toString()),
          loc,
          false);
    }
    if (p.length == 2) {
      if (method.equals("GET")) {
        var rows =
            db.query("papers", "WHERE revoked_at IS NULL").stream()
                .filter(r -> access.source(actor, r))
                .filter(
                    r ->
                        q.get("keyword") == null
                            || str(r, "title")
                                .toLowerCase(Locale.ROOT)
                                .contains(str(q, "keyword").toLowerCase(Locale.ROOT)))
                .filter(
                    r ->
                        q.get("task_id") == null
                            || db
                                .query(
                                    "reading_notes",
                                    "WHERE paper_id=? AND task_id=?",
                                    r.get("id"),
                                    q.get("task_id"))
                                .stream()
                                .anyMatch(n -> access.noteReadable(actor, n)))
                .toList();
        var page = page(rows, q, "papers", "created_at");
        page.put("papers", list(page.get("papers")).stream().map(r -> paperData(obj(r))).toList());
        return page;
      }
      var f = file(actor, str(b, "file_id"));
      require(
          eq(f.get("owner_id"), actor) && eq(f.get("purpose"), "paper"),
          403,
          "FORBIDDEN",
          "只能导入本人文献文件");
      require(!str(b, "title").isBlank(), 422, "VALIDATION_ERROR", "title 不能为空");
      db.lock("files", str(f, "id"));
      require(
          db.one("papers", "WHERE file_id=? AND revoked_at IS NULL", f.get("id")) == null,
          409,
          "PAPER_EXISTS",
          "文件已有文献记录");
      var r =
          db.insert(
              "papers",
              map(
                  "lab_id",
                  f.get("lab_id"),
                  "owner_id",
                  actor,
                  "title",
                  str(b, "title").trim(),
                  "file_id",
                  f.get("id"),
                  "visibility",
                  b.get("visibility")));
      var e =
          db.insert(
              "outbox_events",
              map(
                  "event_type",
                  "paper.ingest_requested",
                  "payload",
                  map("paper_id", r.get("id"), "file_id", f.get("id"), "revision", 1)));
      return map("paper_id", r.get("id"), "ingest_status", "queued", "ingest_job_id", e.get("id"));
    }
    var r = paper(actor, p[2], !method.equals("GET"));
    if (method.equals("GET")) return paperData(r);
    db.version(r, b);
    if (method.equals("DELETE")) {
      r.put("revoked_at", now());
      db.bump("papers", r);
      if (r.get("source_id") != null) {
        var s = db.get("sources", str(r, "source_id"));
        s.put("revoked_at", r.get("revoked_at"));
        db.update("sources", s);
      }
      db.outbox("paper.revoked", map("paper_id", r.get("id"), "source_id", r.get("source_id")));
      return map("revoked_at", r.get("revoked_at"));
    }
    require(
        !Set.of("queued", "parsing", "indexing").contains(str(r, "ingest_status")),
        409,
        "INGEST_IN_PROGRESS",
        "文献仍在解析中");
    r.put("ingest_status", "queued");
    r.put("ingest_error_code", null);
    db.bump("papers", r);
    var s = db.get("sources", str(r, "source_id"));
    var e =
        db.insert(
            "outbox_events",
            map(
                "event_type",
                "paper.ingest_requested",
                "payload",
                map(
                    "paper_id",
                    r.get("id"),
                    "file_id",
                    r.get("file_id"),
                    "revision",
                    s == null ? 1 : num(s, "active_revision") + 1)));
    return map(
        "ingest_job_id", e.get("id"), "ingest_status", "queued", "version", r.get("version"));
  }
}
