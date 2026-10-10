package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@org.springframework.stereotype.Service
public class IdentityService {
  final Repository db;
  final Access access;
  final Environment env;
  final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
  final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();
  final String dummy;

  public IdentityService(Repository db, Access access, Environment env) {
    this.db = db;
    this.access = access;
    this.env = env;
    dummy = passwords.encode(token(24));
  }

  public String actor(String authorization) {
    require(
        authorization != null && authorization.startsWith("Bearer "),
        401,
        "SESSION_EXPIRED",
        "请重新登录");
    Map<String, Object> s =
        db.one("sessions", "WHERE token_hash=?", hash(authorization.substring(7)));
    require(
        s != null && s.get("revoked_at") == null && !before(s.get("expires_at")),
        401,
        "SESSION_EXPIRED",
        "会话已失效");
    Map<String, Object> u = db.get("users", str(s, "user_id"));
    require(u != null && eq(u.get("status"), "active"), 401, "SESSION_REVOKED", "会话已撤销");
    s.put("last_seen_at", now());
    db.update("sessions", s);
    return str(u, "id");
  }

  public Map<String, Object> user(String actor) {
    Map<String, Object> u = access.found(db.get("users", actor));
    Set<String> roles = new TreeSet<>();
    roles.add(str(u, "account_role"));
    for (var m : memberships(actor)) roles.add(str(m, "role"));
    return map(
        "id",
        actor,
        "display_name",
        u.get("display_name"),
        "account_role",
        u.get("account_role"),
        "allowed_roles",
        new ArrayList<>(roles));
  }

  public List<Map<String, Object>> memberships(String actor) {
    return db.query("memberships", "WHERE user_id=? AND active=TRUE", actor).stream()
        .filter(m -> access.member(actor, str(m, "lab_id"), str(m, "role")))
        .map(
            m -> {
              var result = new LinkedHashMap<String, Object>(m);
              var lab = db.get("labs", str(m, "lab_id"));
              if (lab != null) result.put("lab_name", lab.get("name"));
              return (Map<String, Object>) result;
            })
        .toList();
  }

  public Map<String, Object> me(String actor) {
    var members = memberships(actor);
    var links =
        db
            .query(
                "mentorships", "WHERE (mentor_id=? OR student_id=?) AND active=TRUE", actor, actor)
            .stream()
            .filter(l -> members.stream().anyMatch(m -> eq(m.get("lab_id"), l.get("lab_id"))))
            .toList();
    boolean enabled =
        members.stream().anyMatch(m -> eq(m.get("role"), "mentor"))
            || links.stream().anyMatch(l -> eq(l.get("student_id"), actor));
    boolean pending =
        db.one("mentor_applications", "WHERE student_id=? AND status='pending'", actor) != null;
    var u = user(actor);
    return map(
        "user",
        u,
        "allowed_roles",
        u.get("allowed_roles"),
        "onboarding_status",
        enabled ? "joined" : pending ? "pending" : "choose_mentor",
        "collaboration_enabled",
        enabled,
        "memberships",
        members.stream().map(m -> pick(m, "lab_id", "role")).toList(),
        "mentorships",
        links.stream().map(l -> pick(l, "lab_id", "mentor_id", "student_id")).toList());
  }

  public Object handle(
      String method,
      String path,
      String actor,
      Map<String, Object> b,
      Map<String, Object> q,
      String auth,
      String ip) {
    if (path.equals("/auth/register")) {
      String name = str(b, "username").toLowerCase(Locale.ROOT);
      require(db.one("users", "WHERE username=?", name) == null, 409, "INVALID_STATE", "用户名不可用");
      var u =
          db.insert(
              "users",
              map(
                  "username",
                  name,
                  "password_hash",
                  passwords.encode(str(b, "password")),
                  "display_name",
                  b.get("display_name"),
                  "account_role",
                  b.getOrDefault("role", "student")));
      if (eq(u.get("account_role"), "mentor")) {
        var lab = db.insert("labs", map("name", str(b, "display_name") + "的实验室"));
        db.insert(
            "memberships", map("lab_id", lab.get("id"), "user_id", u.get("id"), "role", "mentor"));
      }
      return map("user_id", u.get("id"));
    }
    if (path.equals("/auth/login")) {
      String name = str(b, "username").toLowerCase(Locale.ROOT), key = name + ":" + ip;
      synchronized (failures) {
        List<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayList<>());
        recent.removeIf(t -> t.isBefore(Instant.now().minusSeconds(900)));
        require(recent.size() < 5, 429, "QUOTA_EXCEEDED", "登录尝试过多，请稍后重试");
        var u = db.one("users", "WHERE username=?", name);
        boolean ok =
            passwords.matches(str(b, "password"), u == null ? dummy : str(u, "password_hash"));
        if (u == null || !eq(u.get("status"), "active") || !ok) {
          recent.add(Instant.now());
          throw new AppError(401, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        failures.remove(key);
        String token = token(48);
        var s =
            db.insert(
                "sessions",
                map(
                    "user_id",
                    u.get("id"),
                    "token_hash",
                    hash(token),
                    "installation_id",
                    b.get("installation_id"),
                    "device_label",
                    b.get("device_label"),
                    "expires_at",
                    Instant.now()
                        .plusSeconds(
                            env.getProperty("yanji.session-days", Integer.class, 7) * 86400L)
                        .toString()));
        return map(
            "access_token",
            token,
            "token_type",
            "Bearer",
            "expires_at",
            s.get("expires_at"),
            "session_id",
            s.get("id"),
            "user",
            user(str(u, "id")));
      }
    }
    if (path.equals("/me")) return me(actor);
    if (path.equals("/auth/logout")) {
      var s = db.one("sessions", "WHERE token_hash=?", hash(auth.substring(7)));
      s.put("revoked_at", now());
      db.update("sessions", s);
      return map("ok", true);
    }
    if (path.equals("/auth/password")) {
      var u = db.lock("users", actor);
      require(
          passwords.matches(str(b, "current_password"), str(u, "password_hash")),
          401,
          "INVALID_CREDENTIALS",
          "当前密码错误");
      u.put("password_hash", passwords.encode(str(b, "new_password")));
      db.update("users", u);
      db.execute(
          "UPDATE sessions SET revoked_at=? WHERE user_id=?",
          java.sql.Timestamp.from(Instant.now()),
          actor);
      return map("ok", true);
    }
    if (path.equals("/auth/sessions"))
      return db.query("sessions", "WHERE user_id=? AND revoked_at IS NULL", actor).stream()
          .filter(s -> !before(s.get("expires_at")))
          .map(
              s -> {
                var r = pick(s, "id", "device_label", "last_seen_at", "expires_at");
                r.put("current", eq(s.get("token_hash"), hash(auth.substring(7))));
                return r;
              })
          .toList();
    if (path.startsWith("/auth/sessions/")) {
      var s = access.found(db.lock("sessions", path.substring(path.lastIndexOf('/') + 1)));
      require(eq(s.get("user_id"), actor), 404, "NOT_FOUND", "会话不存在");
      s.put("revoked_at", now());
      db.update("sessions", s);
      return map("ok", true);
    }
    if (path.equals("/mentors")) {
      String keyword =
          String.valueOf(q.getOrDefault("keyword", "")).trim().toLowerCase(Locale.ROOT);
      int limit = Integer.parseInt(q.getOrDefault("limit", 20).toString());
      List<Map<String, Object>> rows = new ArrayList<>();
      for (var m : db.query("memberships", "WHERE role='mentor' AND active=TRUE ORDER BY id")) {
        var u = db.get("users", str(m, "user_id"));
        var l = db.get("labs", str(m, "lab_id"));
        if (u == null
            || !eq(u.get("status"), "active")
            || !eq(u.get("account_role"), "mentor")
            || !access.member(str(u, "id"), str(l, "id"), "mentor")) continue;
        if (!str(u, "display_name").toLowerCase(Locale.ROOT).contains(keyword)
            && !str(u, "id").contains(keyword)) continue;
        if (q.get("cursor") != null && str(m, "id").compareTo(str(q, "cursor")) <= 0) continue;
        rows.add(
            map(
                "mentor_id",
                u.get("id"),
                "display_name",
                u.get("display_name"),
                "lab_id",
                l.get("id"),
                "lab_name",
                l.get("name"),
                "cursor",
                m.get("id")));
      }
      String next = rows.size() > limit ? str(rows.get(limit - 1), "cursor") : null;
      rows = rows.subList(0, Math.min(limit, rows.size()));
      rows.forEach(r -> r.remove("cursor"));
      return map("mentors", rows, "next_cursor", next);
    }
    if (path.equals("/mentor-applications/me"))
      return map(
          "application",
          db.one(
              "mentor_applications", "WHERE student_id=? ORDER BY created_at DESC,id DESC", actor));
    if (path.equals("/mentor-applications/pending")) {
      require(
          eq(db.get("users", actor).get("account_role"), "mentor"), 403, "FORBIDDEN", "仅导师可查看申请");
      var rows =
          db
              .query(
                  "mentor_applications",
                  "WHERE mentor_id=? AND status='pending' ORDER BY created_at,id",
                  actor)
              .stream()
              .filter(a -> access.member(actor, str(a, "lab_id"), "mentor"))
              .toList();
      rows.forEach(
          a ->
              a.put(
                  "student_display_name",
                  db.get("users", str(a, "student_id")).get("display_name")));
      return map("applications", rows);
    }
    if (path.equals("/mentor-applications")) {
      var u = db.lock("users", actor);
      String mentor = str(b, "mentor_id"), lab = str(b, "lab_id");
      require(
          eq(u.get("account_role"), "student") && !eq(actor, mentor),
          403,
          "FORBIDDEN",
          "导师账号不能提交学生申请");
      var mu = db.get("users", mentor);
      require(
          mu != null
              && eq(mu.get("status"), "active")
              && eq(mu.get("account_role"), "mentor")
              && access.member(mentor, lab, "mentor"),
          404,
          "NOT_FOUND",
          "导师不存在");
      require(
          db.one("mentorships", "WHERE student_id=? AND active=TRUE", actor) == null
              && db.one("mentor_applications", "WHERE student_id=? AND status='pending'", actor)
                  == null,
          409,
          "INVALID_STATE",
          "已绑定导师或已有申请");
      return map(
          "application",
          db.insert(
              "mentor_applications", map("student_id", actor, "mentor_id", mentor, "lab_id", lab)));
    }
    if (path.endsWith("/decision")) {
      String id = path.split("/")[2];
      var first = access.found(db.get("mentor_applications", id));
      db.lock("users", str(first, "student_id"));
      var a = access.found(db.lock("mentor_applications", id));
      require(eq(a.get("mentor_id"), actor), 404, "NOT_FOUND", "申请不存在");
      require(access.member(actor, str(a, "lab_id"), "mentor"), 403, "FORBIDDEN", "导师身份已失效");
      require(eq(a.get("status"), "pending"), 409, "INVALID_STATE", "申请已处理");
      if (eq(b.get("decision"), "reject")) {
        require(
            b.get("reason") != null && !str(b, "reason").isBlank(),
            422,
            "VALIDATION_ERROR",
            "拒绝时必须填写原因");
        a.put("status", "rejected");
        a.put("rejection_reason", str(b, "reason").trim());
      } else {
        String student = str(a, "student_id"), lab = str(a, "lab_id");
        var su = db.get("users", student);
        require(
            su != null
                && eq(su.get("status"), "active")
                && db.one("mentorships", "WHERE student_id=? AND active=TRUE", student) == null,
            409,
            "INVALID_STATE",
            "学生已绑定导师或失效");
        var m =
            db.one("memberships", "WHERE user_id=? AND lab_id=? AND role='student'", student, lab);
        if (m == null)
          db.insert("memberships", map("user_id", student, "lab_id", lab, "role", "student"));
        else {
          m.put("active", true);
          db.update("memberships", m);
        }
        var link =
            db.one(
                "mentorships",
                "WHERE student_id=? AND mentor_id=? AND lab_id=?",
                student,
                actor,
                lab);
        if (link == null)
          db.insert("mentorships", map("student_id", student, "mentor_id", actor, "lab_id", lab));
        else {
          link.put("active", true);
          db.update("mentorships", link);
        }
        a.put("status", "approved");
      }
      a.put("decided_at", now());
      db.update("mentor_applications", a);
      return map("application", a);
    }
    throw new AppError(404, "NOT_FOUND", "接口不存在");
  }
}
