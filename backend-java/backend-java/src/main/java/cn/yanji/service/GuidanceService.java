package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.mapper.Repository;
import java.util.*;

/** Mentor-authored guidance uses the existing rule and source pipeline, including indexing. */
@org.springframework.stereotype.Service
public class GuidanceService {
  final Repository db;
  final Access access;
  final IdentityService identity;
  final RecordService records;

  public GuidanceService(
      Repository db, Access access, IdentityService identity, RecordService records) {
    this.db = db;
    this.access = access;
    this.identity = identity;
    this.records = records;
  }

  Map<String, Object> view(Map<String, Object> row) {
    var data = new LinkedHashMap<>(obj(row.get("rules_json")));
    data.put("id", row.get("id"));
    data.put("lab_id", row.get("lab_id"));
    data.put("revision", row.get("revision"));
    return data;
  }

  public String authoringLab(String actor, Map<String, Object> body) {
    String lab = str(body, "lab_id");
    var mentorLabs =
        identity.memberships(actor).stream()
            .filter(m -> eq(m.get("role"), "mentor"))
            .map(m -> str(m, "lab_id"))
            .distinct()
            .toList();
    require(!mentorLabs.isEmpty(), 403, "FORBIDDEN", "只有导师可以编辑科研指导");
    if (lab == null) {
      require(mentorLabs.size() == 1, 422, "VALIDATION_ERROR", "请选择对应实验室");
      lab = mentorLabs.get(0);
    }
    require(mentorLabs.contains(lab), 403, "FORBIDDEN", "不能编辑其他实验室的科研指导");
    return lab;
  }

  public Object handle(
      String method,
      String path,
      String actor,
      Map<String, Object> body,
      Map<String, Object> query) {
    if (path.endsWith("/apply")) {
      var guidance = access.found(db.get("stage_rule_sets", path.split("/")[2]));
      String targetLab = str(guidance, "lab_id");
      require(access.member(actor, targetLab, "mentor"), 403, "FORBIDDEN", "只有导师可以设置阶段指导");
      var stage = access.visible(actor, "stages", str(body, "stage_id"));
      access.student(actor, str(stage, "student_id"), str(stage, "lab_id"), true);
      require(eq(stage.get("lab_id"), targetLab), 403, "FORBIDDEN", "不能使用其他实验室的指导");
      stage = db.lock("stages", str(stage, "id"));
      db.version(stage, body);
      require(eq(stage.get("status"), "active"), 400, "INVALID_STATE", "当前阶段不能修改指导内容");
      stage.put("rule_set_id", guidance.get("id"));
      db.bump("stages", stage);
      return pick(stage, "id", "title", "rule_set_id", "version");
    }
    if (method.equals("GET")) {
      return map(
          "items",
          db.all("stage_rule_sets").stream()
              .filter(r -> access.member(actor, str(r, "lab_id"), null))
              .filter(r -> query.get("lab_id") == null || eq(query.get("lab_id"), r.get("lab_id")))
              .sorted(Comparator.comparing(r -> str(r, "created_at"), Comparator.reverseOrder()))
              .map(this::view)
              .toList());
    }
    String lab = authoringLab(actor, body);
    var rules = pick(body, "title", "phase", "content", "requirements");
    var row =
        db.insert(
            "stage_rule_sets",
            map(
                "lab_id",
                lab,
                "revision",
                1,
                "rules_json",
                rules,
                "approved_by",
                actor,
                "effective_at",
                now()));
    String text = json(rules);
    var source =
        db.insert(
            "sources",
            map(
                "lab_id",
                lab,
                "owner_id",
                actor,
                "kind",
                "rule",
                "entity_id",
                row.get("id"),
                "visibility",
                "lab",
                "active_revision",
                1));
    var revision =
        db.insert(
            "source_revisions",
            map(
                "source_id",
                source.get("id"),
                "revision",
                1,
                "content_hash",
                hash(text),
                "locator_scheme",
                "record_field",
                "text_snapshot",
                text));
    records.chunks(revision);
    return view(row);
  }
}
