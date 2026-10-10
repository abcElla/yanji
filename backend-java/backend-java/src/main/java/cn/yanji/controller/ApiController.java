package cn.yanji.controller;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import cn.yanji.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class ApiController {
  final Repository db;
  final Contract contract;
  final IdentityService identity;
  final StageTaskService stages;
  final RecordService records;
  final AgentService agents;
  final InternalAgentService internal;
  final FileSourceService files;
  final OverviewService overview;
  final GuidanceService guidance;
  final ThesisService theses;
  final ResearchFollowupService followups;
  final Environment env;
  final TransactionTemplate tx;

  public ApiController(
      Repository db,
      Contract contract,
      IdentityService identity,
      StageTaskService stages,
      RecordService records,
      AgentService agents,
      InternalAgentService internal,
      FileSourceService files,
      OverviewService overview,
      GuidanceService guidance,
      ThesisService theses,
      ResearchFollowupService followups,
      Environment env,
      PlatformTransactionManager tm) {
    this.db = db;
    this.contract = contract;
    this.identity = identity;
    this.stages = stages;
    this.records = records;
    this.agents = agents;
    this.internal = internal;
    this.files = files;
    this.overview = overview;
    this.guidance = guidance;
    this.theses = theses;
    this.followups = followups;
    this.env = env;
    tx = new TransactionTemplate(tm);
  }

  @GetMapping({"/openapi.json", "/v3/api-docs"})
  public Object openapi() {
    return contract.document;
  }

  @GetMapping(value = "/docs", produces = "text/html;charset=UTF-8")
  public String docs() {
    return "<html lang='zh'><meta charset='UTF-8'><title>研迹 Java API</title><h1>研迹 Java API · 契约 3.3</h1><p><a href='/openapi.json'>下载 OpenAPI（可导入 Apifox）</a></p><p>公开接口 /api/v1，Agent 内部接口 /internal/v1。</p></html>";
  }

  @RequestMapping({"/api/v1/**", "/internal/v1/**", "/health/**"})
  public ResponseEntity<?> handle(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    String method = request.getMethod(), path = request.getRequestURI();
    if (path.startsWith("/api/v1/") || path.startsWith("/internal/v1/")) {
      response.setHeader("Cache-Control", "private, no-store");
      response.setHeader("Vary", "Authorization");
    }
    Map<String, Object> q = map(), h = map();
    request.getParameterMap().forEach((k, v) -> q.put(k, v[0]));
    Collections.list(request.getHeaderNames())
        .forEach(k -> h.put(k.toLowerCase(Locale.ROOT), request.getHeader(k)));
    String auth = request.getHeader("Authorization");
    if (path.startsWith("/health/"))
      require(method.equals("GET"), 405, "METHOD_NOT_ALLOWED", "请求方法不支持");
    if (path.equals("/health/live")) return ok(200, map("status", "ok"));
    if (path.equals("/health/ready")) {
      Map<String, Object> ready;
      try {
        ready = tx.execute(s -> overview.readiness());
      } catch (org.springframework.dao.DataAccessException e) {
        throw new AppError(503, "READINESS_UNAVAILABLE", "数据库或Worker心跳表不可用");
      }
      if (!eq(ready.get("status"), "ready"))
        throw new AppError(503, "BACKGROUND_NOT_READY", "后台消费者缺失或队列积压", ready, true);
      return ok(200, ready);
    }
    var route = contract.route(method, path);
    contract.validateParams(route, q, h);
    Map<String, Object> body = map();
    if (!(request instanceof MultipartHttpServletRequest) && request.getContentLengthLong() != 0) {
      byte[] bytes = request.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
      require(bytes.length <= 2 * 1024 * 1024, 413, "BODY_TOO_LARGE", "请求正文过大");
      if (bytes.length > 0) {
        Object value;
        try {
          value = JSON.readValue(bytes, Object.class);
        } catch (Exception e) {
          throw new AppError(422, "VALIDATION_ERROR", "JSON 正文格式无效");
        }
        require(value instanceof Map, 422, "VALIDATION_ERROR", "正文必须是 JSON 对象");
        body = obj(value);
      }
    }
    Set<String> provided = new HashSet<>(body.keySet());
    contract.validateBody(route, body);
    if (method.equals("PATCH") && (path.contains("/tasks/") || path.contains("/stages/")))
      body.keySet().retainAll(provided);
    final Map<String, Object> b = body;
    Object result =
        tx.execute(
            status -> {
              if (path.startsWith("/internal/v1")) {
                String configured = env.getProperty("yanji.internal-service-key", "");
                require(configured.length() >= 32, 503, "SERVICE_NOT_CONFIGURED", "内部服务身份尚未配置");
                String received = str(h, "x-service-key");
                require(
                    received != null
                        && MessageDigest.isEqual(
                            configured.getBytes(StandardCharsets.UTF_8),
                            received.getBytes(StandardCharsets.UTF_8)),
                    401,
                    "SERVICE_UNAUTHORIZED",
                    "内部服务身份无效");
                return internal.handle(method, path.substring("/internal/v1".length()), b, q, h);
              }
              String relative = path.substring("/api/v1".length());
              if (relative.equals("/client-config"))
                return map(
                    "api_contract",
                    "3.3",
                    "min_supported_build",
                    env.getProperty("yanji.min-supported-build", Integer.class, 1),
                    "latest_build",
                    env.getProperty("yanji.latest-build", Integer.class, 1),
                    "update_url",
                    env.getProperty("yanji.update-url"));
              String actor =
                  Set.of("/auth/register", "/auth/login").contains(relative)
                      ? null
                      : identity.actor(auth);
              if (request instanceof MultipartHttpServletRequest upload) {
                try {
                  require(upload.getFile("file") != null, 422, "VALIDATION_ERROR", "file 必填");
                  return files.upload(
                      actor, upload.getFile("file"), q, request.getHeader("Idempotency-Key"));
                } catch (java.io.IOException e) {
                  throw new AppError(503, "STORAGE_UNAVAILABLE", "文件上传失败");
                }
              }
              if (relative.startsWith("/files/") && relative.endsWith("/download")) {
                var f = files.file(actor, relative.split("/")[2]);
                return new Download(
                    files.storage.get(str(f, "object_key")), str(f, "mime"), str(f, "id"));
              }
              if (relative.startsWith("/theses/") && relative.endsWith("/download")) {
                String[] parts = relative.split("/");
                var f = theses.download(actor, parts[2], parts[4]);
                return new Download(
                    files.storage.get(str(f, "object_key")), str(f, "mime"), str(f, "id"));
              }
              boolean idem =
                  method.equals("POST")
                      && (relative.equals("/agent-runs")
                          || relative.endsWith("/retry")
                          || relative.endsWith("/approve")
                          || relative.endsWith("/transition")
                          || (relative.startsWith("/stages/") && relative.endsWith("/submit"))
                          || (relative.startsWith("/weekly-reports/")
                              && relative.endsWith("/submit")));
              // Confirmation remains usable without a key by legacy clients; a supplied key
              // opts into the same atomic receipt handling as the other idempotent writes.
              boolean confirmation =
                  (relative.startsWith("/experiments/") || relative.startsWith("/notes/"))
                      && relative.endsWith("/confirm")
                      && method.equals("POST");
              idem = idem || (confirmation && request.getHeader("Idempotency-Key") != null);
              idem =
                  idem
                      || (method.equals("POST")
                          && relative.equals("/guidance")
                          && request.getHeader("Idempotency-Key") != null);
              idem = idem || (method.equals("POST") && (relative.startsWith("/theses") || relative.startsWith("/research-followups")));
              if (idem) {
                String key = request.getHeader("Idempotency-Key");
                require(
                    key != null && !key.isBlank() && key.length() <= 100,
                    422,
                    "VALIDATION_ERROR",
                    "Idempotency-Key 必填，最多100字符");
                db.lock("users", actor);
                if (relative.equals("/guidance")) guidance.authoringLab(actor, b);
                if (relative.startsWith("/theses")) theses.authorizeWrite(actor, relative, b);
                authorizeReplay(relative, actor);
                if (relative.startsWith("/tasks/") && relative.endsWith("/transition"))
                  stages.authorizeCompletionReplay(actor, relative.split("/")[2], b);
                if (relative.equals("/agent-runs"))
                  stages.access.lab(actor, str(obj(b.get("subject")), "student_id"), false);
                if (relative.equals("/research-followups"))
                  stages.access.lab(actor, str(b,"student_id"), false);
                String digest = hash(json(b));
                var prior =
                    db.one(
                        "idempotency_records",
                        "WHERE actor_id=? AND method=? AND path=? AND `key`=?",
                        actor,
                        method,
                        path,
                        key);
                if (prior != null) {
                  if (relative.equals("/guidance"))
                    guidance.authoringLab(
                        actor, map("lab_id", obj(prior.get("response")).get("lab_id")));
                  if (relative.equals("/agent-runs"))
                    agents.run(actor, str(obj(prior.get("response")), "run_id"));
                  if (relative.equals("/research-followups"))
                    followups.readable(actor,str(obj(prior.get("response")),"id"));
                  require(
                      eq(prior.get("body_hash"), digest),
                      409,
                      "IDEMPOTENCY_CONFLICT",
                      "同一幂等键对应不同请求");
                  return prior.get("response");
                }
                Object data =
                    dispatch(method, relative, actor, b, q, auth, request.getRemoteAddr());
                db.insert(
                    "idempotency_records",
                    map(
                        "actor_id",
                        actor,
                        "method",
                        method,
                        "path",
                        path,
                        "key",
                        key,
                        "body_hash",
                        digest,
                        "response",
                        data,
                        "expires_at",
                        Instant.now().plusSeconds(604800).toString()));
                return data;
              }
              return dispatch(method, relative, actor, b, q, auth, request.getRemoteAddr());
            });
    if (result instanceof Download d)
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(d.mime()))
          .header("Cache-Control", "no-store")
          .header("X-Content-Type-Options", "nosniff")
          .header(
              "Content-Disposition",
              "attachment; filename=\"" + d.id() + extension(d.mime()) + "\"")
          .body(d.bytes());
    return ok(route.status(), result);
  }

  void authorizeReplay(String path, String actor) {
    String[] p = path.split("/");
    if (p.length < 3) return;
    switch (p[1]) {
      case "stages", "tasks" -> stages.access.visible(actor, p[1], p[2]);
      case "weekly-reports" -> records.record(actor, "weekly_reports", p[2], true);
      case "experiments" -> records.record(actor, "experiments", p[2], true);
      case "notes" -> records.record(actor, "reading_notes", p[2], true);
      case "research-followups" -> followups.readable(actor, p[2]);
      case "agent-runs" -> agents.run(actor, p[2]);
      case "proposals" -> agents.proposal(actor, p[2]);
      default -> {}
    }
  }

  Object dispatch(
      String method,
      String path,
      String actor,
      Map<String, Object> b,
      Map<String, Object> q,
      String auth,
      String ip) {
    String group = path.split("/")[1];
    return switch (group) {
      case "auth", "me", "mentors", "mentor-applications" ->
          identity.handle(method, path, actor, b, q, auth, ip);
      case "research-followups" -> followups.handle(method, path, actor, b, q);
      case "guidance" -> guidance.handle(method, path, actor, b, q);
      case "theses" -> theses.handle(method, path, actor, b, q);
      case "stages", "tasks" -> stages.handle(method, path, actor, b, q);
      case "experiments", "notes", "weekly-reports", "review-drafts" ->
          records.handle(method, path, actor, b, q);
      case "agent-runs", "proposals" -> agents.handle(method, path, actor, b, q);
      case "files", "papers", "sources" -> files.handle(method, path, actor, b, q);
      case "dashboard", "students", "notifications" -> overview.handle(method, path, actor, b, q);
      default -> throw new AppError(404, "NOT_FOUND", "接口不存在");
    };
  }

  static String extension(String mime) {
    return switch (mime) {
      case "application/pdf" -> ".pdf";
      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
      case "image/png" -> ".png";
      case "image/jpeg" -> ".jpg";
      default -> "";
    };
  }

  static ResponseEntity<?> ok(int status, Object data) {
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(Result.success(data));
  }

  record Download(byte[] bytes, String mime, String id) {}
}
