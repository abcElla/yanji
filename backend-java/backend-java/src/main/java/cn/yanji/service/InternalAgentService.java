package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@org.springframework.stereotype.Service
public class InternalAgentService {
  final Repository db;
  final Access access;
  final AgentService agents;
  final RecordService records;
  final FileSourceService files;
  final RetrievalService retrieval;
  final ResearchExperienceService experiences;

  public InternalAgentService(
      Repository db,
      Access access,
      AgentService agents,
      RecordService records,
      FileSourceService files,
      RetrievalService retrieval, ResearchExperienceService experiences) {
    this.db = db;
    this.access = access;
    this.agents = agents;
    this.records = records;
    this.files = files;
    this.retrieval = retrieval;
    this.experiences = experiences;
  }

  public static List<String> actions(String kind) {
    return switch (kind) {
      case "stage_plan" -> List.of("task.create_batch", "task.reschedule_batch");
      case "paper_guide" -> List.of("reading_note.draft");
      case "weekly_report" -> List.of("weekly_report.draft");
      case "risk_brief" -> List.of("task.reschedule_batch", "mentor_feedback.draft");
      default -> List.of();
    };
  }

  public boolean requesterStillAuthorized(Map<String, Object> run) {
    return access.runReadable(str(run, "requested_by"), run);
  }

  String scope(Map<String, Object> r) {
    String actor = str(r, "requested_by");
    var user = db.get("users", actor);
    require(
        user != null
            && eq(user.get("status"), "active")
            && access.canStudent(actor, str(r, "student_id"), str(r, "lab_id"), false)
            && access.runReadable(actor, r),
        403,
        "SCOPE_REVOKED",
        "原请求者已失去访问权");
    return actor;
  }

  void lease(Map<String, Object> r, int attempt, String token, boolean replay) {
    require(
        token != null
            && token.length() <= 200
            && num(r, "attempt") == attempt
            && r.get("lease_token_hash") != null
            && MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                str(r, "lease_token_hash").getBytes(StandardCharsets.UTF_8)),
        409,
        "LEASE_INVALID",
        "运行租约无效");
    if (replay && Set.of("succeeded", "failed").contains(str(r, "status"))) return;
    require(
        eq(r.get("status"), "running")
            && r.get("lease_expiry") != null
            && !before(r.get("lease_expiry")),
        409,
        "LEASE_EXPIRED",
        "运行租约已过期或任务已结束");
  }

  public Map<String, Object> addEvent(
      Map<String, Object> run, String key, String step, String status, String message) {
    var events = db.query("agent_run_events", "WHERE run_id=? ORDER BY seq DESC", run.get("id"));
    int next = events.isEmpty() ? 1 : num(events.get(0), "seq") + 1;
    return db.insert(
        "agent_run_events",
        map(
            "run_id",
            run.get("id"),
            "attempt",
            run.get("attempt"),
            "event_key",
            key,
            "seq",
            next,
            "step",
            step,
            "status",
            status,
            "message",
            message));
  }

  boolean visibleSource(Map<String, Object> r, String actor, Map<String, Object> s) {
    if (!access.source(actor, s) || !eq(s.get("lab_id"), r.get("lab_id"))) return false;
    String kind = str(s, "kind");
    if (kind.equals("weekly_report")) {
      var report = db.get("weekly_reports", str(s, "entity_id"));
      var saved = db.one("source_revisions", "WHERE source_id=? AND revision=?", s.get("id"), s.get("active_revision"));
      return report != null && eq(report.get("student_id"), r.get("student_id"))
          && saved != null && json(records.reportSourceContent(report)).equals(str(saved, "text_snapshot"));
    }
    if (kind.equals("thesis_feedback")) {
      var feedback = db.get("thesis_feedback", str(s, "entity_id"));
      var revision =
          feedback == null ? null : db.get("thesis_revisions", str(feedback, "revision_id"));
      var thesis = revision == null ? null : db.get("theses", str(revision, "thesis_id"));
      var sr =
          db.one(
              "source_revisions",
              "WHERE source_id=? AND revision=?",
              s.get("id"),
              s.get("active_revision"));
      return thesis != null
          && eq(thesis.get("student_id"), r.get("student_id"))
          && sr != null
          && json(pick(feedback, "content", "decision", "mentor_id", "created_at"))
              .equals(str(sr, "text_snapshot"));
    }
    if (kind.equals("paper")) {
      var p = db.get("papers", str(s, "entity_id"));
      return p != null && access.source(actor, p) && eq(p.get("ingest_status"), "ready");
    }
    if (Set.of("experiment", "note", "reading_note").contains(kind)) {
      String table = kind.equals("experiment") ? "experiments" : "reading_notes";
      var item = db.get(table, str(s, "entity_id"));
      if (item == null
          || !eq(item.get("owner_id"), r.get("student_id"))
          || !eq(item.get("status"), "confirmed")) return false;
      if (kind.equals("experiment")
          && !eq(actor, item.get("owner_id"))
          && !Set.of("lab", "mentors").contains(str(item, "visibility"))) return false;
      var sr =
          db.one(
              "source_revisions",
              "WHERE source_id=? AND revision=?",
              s.get("id"),
              s.get("active_revision"));
      if (sr == null) return false;
      var rev = records.revision(table, item, null);
      var expected =
          kind.equals("experiment")
              ? records.experimentSourceContent(rev)
              : records.noteSourceContent(rev);
      try {
        String saved = json(parse(str(sr, "text_snapshot")));
        return json(expected).equals(saved)
            || (kind.equals("experiment") && records.experimentSourceMatches(rev, saved))
            || (!kind.equals("experiment") && json(pick(rev, "claims", "questions", "source_refs")).equals(saved));
      } catch (Exception e) {
        return false;
      }
    }
    if (kind.equals("rule")) {
      var rule = db.get("stage_rule_sets", str(s, "entity_id"));
      var sr =
          db.one(
              "source_revisions",
              "WHERE source_id=? AND revision=?",
              s.get("id"),
              s.get("active_revision"));
      return rule != null
          && sr != null
          && eq(rule.get("revision"), s.get("active_revision"))
          && eq(rule.get("approved_by"), s.get("owner_id"))
          && access.member(str(rule, "approved_by"), str(r, "lab_id"), "mentor")
          && before(rule.get("effective_at"))
          && json(rule.get("rules_json")).equals(json(parse(str(sr, "text_snapshot"))));
    }
    return false;
  }

  boolean stageSource(Map<String, Object> s, String stage, Map<String, Object> r) {
    String kind = str(s, "kind");
    // An explicitly selected paper and its feedback remain readable alongside a stage.
    // visibleSource and the run snapshot still enforce ownership, revision and mentor ACL.
    String selectedPaper = str(obj(r.get("subject")), "paper_id");
    if (eq(r.get("kind"), "research_qa") && selectedPaper != null) {
      if (kind.equals("paper") && eq(s.get("entity_id"), selectedPaper)) return true;
      if (kind.equals("thesis_feedback")) {
        var feedback = db.get("thesis_feedback", str(s, "entity_id"));
        var revision = feedback == null ? null : db.get("thesis_revisions", str(feedback, "revision_id"));
        return revision != null && eq(revision.get("paper_id"), selectedPaper);
      }
      if (Set.of("note", "reading_note").contains(kind)) {
        var note = db.get("reading_notes", str(s, "entity_id"));
        if (note != null && eq(note.get("paper_id"), selectedPaper)) return true;
      }
    }
    if (kind.equals("thesis_feedback")) return false;
    if (kind.equals("rule")) {
      var st = db.get("stages", stage);
      return st != null && eq(s.get("entity_id"), st.get("rule_set_id"));
    }
    if (kind.equals("paper"))
      return db
          .query(
              "reading_notes",
              "WHERE paper_id=? AND owner_id=? AND status='confirmed'",
              s.get("entity_id"),
              r.get("student_id"))
          .stream()
          .anyMatch(
              n -> {
                var t = db.get("tasks", str(n, "task_id"));
                return t != null && eq(t.get("stage_id"), stage);
              });
    String table = kind.equals("experiment") ? "experiments" : "reading_notes";
    var record = db.get(table, str(s, "entity_id"));
    var task = record == null ? null : db.get("tasks", str(record, "task_id"));
    return task != null
        && eq(task.get("stage_id"), stage)
        && eq(task.get("student_id"), r.get("student_id"));
  }

  public Map<String, Object> context(Map<String, Object> run, String actor) {
    String stageId = str(obj(run.get("subject")), "stage_id");
    var stage = stageId == null ? null : db.get("stages", stageId);
    var rule = stage == null ? null : db.get("stage_rule_sets", str(stage, "rule_set_id"));
    if (stageId != null)
      require(
          stage != null
              && eq(stage.get("lab_id"), run.get("lab_id"))
              && eq(stage.get("student_id"), run.get("student_id")),
          409,
          "CONTEXT_STALE",
          "运行阶段已失效");
    var existing = db.get("context_snapshots", str(run, "snapshot_id"));
    if (existing != null) {
      var saved = obj(obj(existing.get("authorized_scope")).get("context"));
      if (stage != null)
        require(
            eq(stage.get("version"), obj(saved.get("stage")).get("version"))
                && eq(rule == null ? null : rule.get("revision"), saved.get("stage_rule_revision"))
                && json(rule == null ? null : rule.get("rules_json"))
                    .equals(json(saved.get("stage_rules"))),
            409,
            "CONTEXT_STALE",
            "阶段或规则版本已变化");
      for (Object raw : list(existing.get("source_versions"))) {
        var item = obj(raw);
        var source = db.get("sources", str(item, "source_id"));
        require(
            visibleSource(run, actor, source)
                && eq(source.get("active_revision"), item.get("revision")),
            409,
            "SOURCE_STALE",
            "上下文来源已变化");
      }
      for (Object raw : list(existing.get("business_versions"))) {
        var version = obj(raw);
        if (!eq(version.get("type"), "task")) continue;
        var task = db.get("tasks", str(version, "id"));
        require(
            task != null && eq(task.get("version"), version.get("version")),
            409,
            "CONTEXT_STALE",
            "任务事实已变化，请创建新的运行");
      }
      return saved;
    }
    var tasks =
        db
            .query(
                "tasks",
                "WHERE lab_id=? AND student_id=?",
                run.get("lab_id"),
                run.get("student_id"))
            .stream()
            .filter(t -> stageId == null || eq(run.get("kind"),"research_followup") || eq(t.get("stage_id"), stageId))
            .toList();
    String feedbackReportId = eq(run.get("kind"), "risk_brief") ? str(obj(run.get("input")), "report_id") : null;
    var feedbackReport = feedbackReportId == null ? null : records.record(actor, "weekly_reports", feedbackReportId, false);
    Map<String, Object> feedbackSource = null;
    if (feedbackReport != null) {
      require(eq(feedbackReport.get("student_id"), run.get("student_id"))
          && Set.of("submitted", "changes_requested").contains(str(feedbackReport, "status")),
          409, "CONTEXT_STALE", "周报已变化，请重新发起分析");
      feedbackSource = records.publishedReportSource(feedbackReport);
    }
    var sources =
        db.query("sources", "WHERE lab_id=? AND revoked_at IS NULL", run.get("lab_id")).stream()
            .filter(s -> visibleSource(run, actor, s))
            .filter(s -> feedbackReportId == null || (eq(s.get("kind"), "weekly_report") && eq(s.get("entity_id"), feedbackReportId)))
            .filter(s -> stageId == null || eq(run.get("kind"),"research_followup") || stageSource(s, stageId, run))
            .filter(
                s -> {
                  String paperId = str(obj(run.get("subject")), "paper_id");
                  if (eq(run.get("kind"), "research_qa") && paperId != null) {
                    if (eq(s.get("kind"), "paper")) return eq(s.get("entity_id"), paperId);
                    if (eq(s.get("kind"), "thesis_feedback")) {
                      var feedback = db.get("thesis_feedback", str(s, "entity_id"));
                      var revision =
                          feedback == null
                              ? null
                              : db.get("thesis_revisions", str(feedback, "revision_id"));
                      return revision != null && eq(revision.get("paper_id"), paperId);
                    }
                    if (Set.of("note", "reading_note").contains(str(s, "kind"))) {
                      var note = db.get("reading_notes", str(s, "entity_id"));
                      return note != null && eq(note.get("paper_id"), paperId);
                    }
                    return false;
                  }
                  if (!eq(run.get("kind"), "paper_guide")) return true;
                  if (eq(s.get("kind"), "paper")) return eq(s.get("entity_id"), paperId);
                  if (!Set.of("note", "reading_note").contains(str(s, "kind"))) return false;
                  var note = db.get("reading_notes", str(s, "entity_id"));
                  return note != null && eq(note.get("paper_id"), paperId);
                })
            .sorted(Comparator.comparing(s -> str(s, "id")))
            .toList();
    Instant start = null, end = null;
    String week = null;
    if (eq(run.get("kind"), "weekly_report")) {
      week = str(obj(run.get("input")), "week_start");
      start = LocalDate.parse(week).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
      end = start.plusSeconds(604800);
    }
    var experiments = new ArrayList<Map<String, Object>>();
    var notes = new ArrayList<Map<String, Object>>();
    var relevant = new ArrayList<Map<String, Object>>();
    for (var s : sources) {
      if (Set.of("experiment", "note", "reading_note").contains(str(s, "kind"))) {
        String table = eq(s.get("kind"), "experiment") ? "experiments" : "reading_notes";
        var record = db.get(table, str(s, "entity_id"));
        var rev = records.revision(table, record, null);
        if (start != null
            && (rev.get("confirmed_at") == null
                || instant(rev.get("confirmed_at")).isBefore(start)
                || !instant(rev.get("confirmed_at")).isBefore(end))) continue;
        var data = records.data(table, record, null, false);
        data.putAll(map("source_id", s.get("id"), "source_revision", s.get("active_revision")));
        if (table.equals("experiments"))
          experiments.add(
              pick(
                  data,
                  "id",
                  "task_id",
                  "revision",
                  "purpose",
                  "parameters",
                  "steps",
                  "results",
                  "version",
                  "confirmed_at",
                  "source_id",
                  "source_revision"));
        else
          notes.add(
              pick(
                  data,
                  "id",
                  "task_id",
                  "paper_id",
                  "revision",
                  "claims",
                  "questions",
                  "version",
                  "confirmed_at",
                  "source_id",
                  "source_revision"));
      }
      relevant.add(s);
    }
    String primaryPaper = str(obj(run.get("subject")), "paper_id");
    relevant.sort(
        Comparator.<Map<String, Object>, Boolean>comparing(
                s -> !(eq(s.get("kind"), "paper") && eq(s.get("entity_id"), primaryPaper)))
            .thenComparing(
                s -> !Set.of("rule", "experiment", "note", "reading_note").contains(str(s, "kind")))
            .thenComparing(s -> str(s, "id")));
    int totalRecords = experiments.size() + notes.size();
    int totalSources = relevant.size();
    relevant = new ArrayList<>(relevant.subList(0, Math.min(200, relevant.size())));
    Set<String> ids = new HashSet<>();
    relevant.forEach(s -> ids.add(str(s, "id")));
    experiments.removeIf(e -> !ids.contains(str(e, "source_id")));
    notes.removeIf(e -> !ids.contains(str(e, "source_id")));
    var coverage = ContextRecords.select(experiments, notes);
    int excluded = totalRecords - experiments.size() - notes.size();
    coverage.put("total_records", totalRecords);
    coverage.put("omitted_records", totalRecords - num(coverage, "included_records"));
    coverage.put("partial", totalRecords > num(coverage, "included_records"));
    coverage.put("excluded_by_source_limit", excluded);
    coverage.put("omitted_records_searchable", excluded == 0);
    coverage.put("source_scope_truncated", totalSources > relevant.size());
    var inlineIds = new HashSet<>(list(coverage.remove("selected_ids")));
    coverage.put(
        "omitted_sources",
        relevant.stream()
            .filter(s -> Set.of("experiment", "note", "reading_note").contains(str(s, "kind")))
            .filter(s -> !inlineIds.contains(s.get("id")))
            .map(
                s ->
                    map(
                        "source_id",
                        s.get("id"),
                        "source_revision",
                        s.get("active_revision"),
                        "kind",
                        s.get("kind"),
                        "record_id",
                        s.get("entity_id")))
            .toList());
    experiments.removeIf(e -> !inlineIds.contains(e.get("source_id")));
    notes.removeIf(e -> !inlineIds.contains(e.get("source_id")));
    var sourceVersions =
        relevant.stream()
            .map(s -> map("source_id", s.get("id"), "revision", s.get("active_revision")))
            .toList();
    var business = new ArrayList<Map<String, Object>>();
    tasks.forEach(
        t -> business.add(map("type", "task", "id", t.get("id"), "version", t.get("version"))));
    if (stage != null)
      business.add(map("type", "stage", "id", stage.get("id"), "version", stage.get("version")));
    var report =
        feedbackReport != null ? feedbackReport
            : eq(run.get("kind"), "weekly_report") && obj(run.get("input")).get("report_id") != null
                ? records.record(actor, "weekly_reports", str(obj(run.get("input")), "report_id"), false)
                : null;
    if (report != null)
      business.add(
          map("type", "weekly_report", "id", report.get("id"), "version", report.get("version")));
    var c =
        map(
            "schema_version",
            2,
            "snapshot_id",
            uid(),
            "run_id",
            run.get("id"),
            "requested_by",
            actor,
            "student_id",
            run.get("student_id"),
            "lab_id",
            run.get("lab_id"),
            "kind",
            run.get("kind"),
            "subject",
            run.get("subject"),
            "input",
            run.get("input"),
            "allowed_actions",
            actions(str(run, "kind")),
            "stage_rule_revision",
            rule == null ? null : rule.get("revision"),
            "tasks",
            tasks.stream()
                .map(
                    t -> {
                      var snapshot = pick(
                            t,
                            "id",
                            "stage_id",
                            "title",
                            "description",
                            "status",
                            "due_at",
                            "version");
                      snapshot.put("dependency_ids", db.query("task_dependencies", "WHERE task_id=?", t.get("id"))
                          .stream().map(d -> d.get("depends_on_task_id")).toList());
                      return snapshot;
                    })
                .toList(),
            "confirmed_experiments",
            experiments,
            "confirmed_notes",
            notes,
            "record_coverage",
            coverage,
            "evidence_policy",
            map(
                "task_facts_basis", "tasks_and_business_versions",
                "rule_sources_support", "requirements_only",
                "partial_records_require_disclosure", true),
            "existing_report_version",
            report == null ? 0 : report.get("version"),
            "source_versions",
            sourceVersions,
            "business_versions",
            business,
            "limits",
            map(
                "search_top_k",
                10,
                "max_proposals",
                20,
                "max_inline_records",
                ContextRecords.LIMIT),
            "stage",
            stage == null
                ? null
                : pick(stage, "id", "title", "goal", "due_at", "status", "version"),
            "stage_rules",
            rule == null ? null : rule.get("rules_json"),
            "stage_rule_source",
            null,
            "paper",
            null);
    if (feedbackReport != null) {
      var selectedReport = records.data("weekly_reports", feedbackReport, null, false);
      // The submitted report is the publication boundary. Its inherited citations may
      // point to private student records; do not grant those records to the mentor.
      for (String section : RecordService.SECTIONS)
        selectedReport.put(section, list(selectedReport.get(section)).stream()
            .map(raw -> pick(obj(raw), "id", "text", "origin")).toList());
      selectedReport.put("source_id", feedbackSource.get("id"));
      selectedReport.put("source_revision", feedbackSource.get("active_revision"));
      c.put("weekly_report", selectedReport);
    }
    {
      // Only the newest revision's current mentor feedback is an active requirement.
      // Historical feedback remains searchable, but must not silently override it.
      var constraints=new ArrayList<Map<String,Object>>();
      for(var thesis:db.query("theses","WHERE student_id=? AND lab_id=?",run.get("student_id"),run.get("lab_id"))) {
        var revision=db.one("thesis_revisions","WHERE thesis_id=? ORDER BY revision_number DESC,id DESC",thesis.get("id"));
        if(revision==null)continue;
        var seenMentors=new HashSet<String>();
        for(var feedback:db.query("thesis_feedback","WHERE revision_id=? ORDER BY created_at DESC,id DESC",revision.get("id"))) {
          String mentor=str(feedback,"mentor_id");
          if(!seenMentors.add(mentor))continue;
          if(db.one("mentorships","WHERE student_id=? AND mentor_id=? AND lab_id=? AND active=TRUE",run.get("student_id"),mentor,run.get("lab_id"))==null)continue;
          var source=db.one("sources","WHERE kind='thesis_feedback' AND entity_id=? AND revoked_at IS NULL",feedback.get("id"));
          if(source==null || !sourceVersions.stream().anyMatch(v->eq(v.get("source_id"),source.get("id")) && eq(v.get("revision"),source.get("active_revision"))))continue;
          var teacher=db.get("users",mentor);
          constraints.add(map("mentor_name",teacher==null?"导师":teacher.get("display_name"),"thesis_title",thesis.get("title"),
              "content",feedback.get("content"),"decision",feedback.get("decision"),"source_id",source.get("id"),"source_revision",source.get("active_revision")));
        }
      }
      c.put("mentor_constraints",constraints);
    }
    if (eq(run.get("kind"), "research_followup")) {
      var skillCatalog=new ArrayList<Map<String,Object>>();
      var studentSubject=map("student_id",run.get("student_id"));
      skillCatalog.add(map("id","risk_overview","label","检查任务安排","kind","risk_brief",
          "subject",studentSubject,"input",map("horizon_days",7)));
      var monday=LocalDate.now(ZoneId.of("Asia/Shanghai")).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      skillCatalog.add(map("id","weekly_summary","label","整理本周周报","kind","weekly_report",
          "subject",studentSubject,"input",map("week_start",monday.toString())));
      if(stage!=null)skillCatalog.add(map("id","current_stage","label","细化当前阶段计划","kind","stage_plan",
          "subject",map("student_id",run.get("student_id"),"stage_id",stage.get("id")),"input",map()));
      int guides=0;
      for(var source:relevant) {
        if(guides>=3)break;
        if(!eq(source.get("kind"),"paper"))continue;
        var paper=db.get("papers",str(source,"entity_id"));
        if(paper==null || !eq(paper.get("ingest_status"),"ready") || !access.source(actor,paper))continue;
        String title=str(paper,"title");
        skillCatalog.add(map("id","paper_guide_"+guides++,"label","导读："+title.substring(0,Math.min(195,title.length())),
            "kind","paper_guide","subject",map("student_id",run.get("student_id"),"paper_id",paper.get("id")),"input",map()));
      }
      skillCatalog.add(map("id","research_record","label","补充科研记录","route","/work"));
      skillCatalog.add(map("id","thesis_feedback","label","查看稿件与导师反馈","route","/theses"));
      skillCatalog.add(map("id","reading_note","label","补充阅读笔记","route","/library"));
      c.put("skill_catalog",skillCatalog);
      var followup=db.get("research_followups",str(obj(run.get("subject")),"followup_id"));
      if(followup!=null && eq(followup.get("recipient_id"),actor)) {
        var history=new ArrayList<Map<String,Object>>();
        for(var previous:db.query("research_followups","WHERE recipient_id=? AND student_id=? AND state IN ('active','applied') ORDER BY updated_at DESC,id DESC",actor,run.get("student_id"))) {
          if(history.size()>=5)break;
          var previousRun=db.get("agent_runs",str(previous,"run_id"));
          if(previousRun==null || !eq(previousRun.get("status"),"succeeded") || !access.runReadable(actor,previousRun))continue;
          // Historical prose is usable only when every dependency is pinned in this
          // new snapshot. Otherwise later revocation could miss a copied conclusion.
          var previousSnapshot=db.get("context_snapshots",str(previousRun,"snapshot_id"));
          if(previousSnapshot==null || !list(previousSnapshot.get("source_versions")).stream()
              .allMatch(raw -> sourceVersions.stream().anyMatch(current ->
                  eq(current.get("source_id"),obj(raw).get("source_id"))
                      && eq(current.get("revision"),obj(raw).get("revision")))))continue;
          var progress=new ArrayList<Map<String,Object>>();
          for(Object taskId:list(previous.get("task_ids"))) {
            var task=db.get("tasks",taskId.toString());
            if(task!=null && eq(task.get("student_id"),run.get("student_id")) && eq(task.get("lab_id"),run.get("lab_id")))
              progress.add(pick(task,"id","title","status"));
          }
          history.add(map("question",obj(previousRun.get("input")).get("question"),"summary",obj(previousRun.get("result")).get("summary"),"tasks",progress));
        }
        c.put("followup",map("changes",followup.get("changes"),"previous_plans",history,"recipient_role",
            eq(actor,run.get("student_id"))?"student":"mentor"));
        var toolResults=new ArrayList<Map<String,Object>>();
        for(var completed:db.query("agent_runs","WHERE requested_by=? AND student_id=? AND lab_id=? AND status='succeeded' AND kind<>'research_followup' ORDER BY finished_at DESC,id DESC",actor,run.get("student_id"),run.get("lab_id"))) {
          if(toolResults.size()>=3)break;
          if(!access.runReadable(actor,completed))continue;
          var toolSnapshot=db.get("context_snapshots",str(completed,"snapshot_id"));
          if(toolSnapshot==null || !list(toolSnapshot.get("source_versions")).stream()
              .allMatch(raw->sourceVersions.stream().anyMatch(current->eq(current.get("source_id"),obj(raw).get("source_id")) && eq(current.get("revision"),obj(raw).get("revision")))))continue;
          toolResults.add(map("kind",completed.get("kind"),"summary",obj(completed.get("result")).get("summary"),"finished_at",completed.get("finished_at")));
        }
        obj(c.get("followup")).put("tool_results",toolResults);
      }
    }
    String paperId = str(obj(run.get("subject")), "paper_id");
    if (paperId != null) {
      var paper = db.get("papers", paperId);
      require(
          paper != null && access.source(actor, paper) && eq(paper.get("ingest_status"), "ready"),
          409,
          "CONTEXT_STALE",
          "运行文献已失效");
      c.put(
          "paper",
          pick(paper, "id", "title", "authors", "publication_year", "doi", "source_id", "version"));
    }
    if (rule != null) {
      require(before(rule.get("effective_at")), 409, "CONTEXT_STALE", "规则尚未生效");
      for (var s : relevant)
        if (eq(s.get("kind"), "rule") && eq(s.get("entity_id"), rule.get("id"))) {
          var fields = obj(rule.get("rules_json"));
          String field =
              fields.containsKey("requirements")
                  ? "requirements"
                  : fields.keySet().stream().findFirst().orElse(null);
          if (field != null)
            c.put(
                "stage_rule_source",
                map(
                    "source_id",
                    s.get("id"),
                    "source_revision",
                    s.get("active_revision"),
                    "locator",
                    map("kind", "record_field", "field", field)));
        }
    }
    experiences.enrich(run,c);
    db.insert(
        "context_snapshots",
        map(
            "id",
            c.get("snapshot_id"),
            "run_id",
            run.get("id"),
            "authorized_scope",
            map(
                "lab_id",
                run.get("lab_id"),
                "student_id",
                run.get("student_id"),
                "requested_by",
                actor,
                "context",
                c),
            "source_versions",
            sourceVersions,
            "business_versions",
            business));
    run.put("snapshot_id", c.get("snapshot_id"));
    db.update("agent_runs", run);
    return c;
  }

  void pinned(Map<String, Object> c, Map<String, Object> ref) {
    require(
        list(c.get("source_versions")).stream()
            .anyMatch(
                raw ->
                    eq(obj(raw).get("source_id"), ref.get("source_id"))
                        && eq(obj(raw).get("revision"), ref.get("source_revision"))),
        404,
        "NOT_FOUND",
        "来源不在上下文快照中");
  }

  public Object handle(
      String method,
      String path,
      Map<String, Object> b,
      Map<String, Object> q,
      Map<String, Object> h) {
    String[] p = path.split("/");
    var r = access.found(db.lock("agent_runs", p[2]));
    String actor = scope(r), operation = p[3], token = str(h, "x-run-lease");
    int attempt =
        b.get("attempt") == null
            ? (h.get("x-run-attempt") == null
                ? 0
                : Integer.parseInt(h.get("x-run-attempt").toString()))
            : num(b, "attempt");
    if (operation.equals("claim")) {
      if (Set.of("succeeded", "failed", "cancelled").contains(str(r, "status")))
        return map("run_id", r.get("id"), "status", r.get("status"), "attempt", r.get("attempt"));
      require(
          !eq(r.get("status"), "running")
              || r.get("lease_expiry") == null
              || before(r.get("lease_expiry")),
          409,
          "RUN_BUSY",
          "运行正在由其他 Worker 处理");
      String lease = token(32);
      r.putAll(
          map(
              "attempt",
              num(r, "attempt") + 1,
              "status",
              "running",
              "step",
              "claimed",
              "started_at",
              r.get("started_at") == null ? now() : r.get("started_at"),
              "lease_expiry",
              Instant.now().plusSeconds(90).toString(),
              "lease_token_hash",
              hash(lease)));
      db.bump("agent_runs", r);
      addEvent(r, "claim:" + r.get("attempt"), "claimed", "running", null);
      return map(
          "run_id",
          r.get("id"),
          "status",
          "running",
          "attempt",
          r.get("attempt"),
          "lease_token",
          lease,
          "lease_expires_at",
          r.get("lease_expiry"),
          "worker_id",
          b.get("worker_id"));
    }
    if (Set.of("finalize", "fail").contains(operation)) {
      var receipt =
          db.one(
              "idempotency_records",
              "WHERE actor_id=? AND method='INTERNAL' AND path=? AND `key`=?",
              actor,
              "/internal/v1" + path,
              String.valueOf(attempt));
      if (receipt != null) {
        lease(r, attempt, token, true);
        require(
            eq(receipt.get("body_hash"), hash(json(b))),
            409,
            "IDEMPOTENCY_CONFLICT",
            "同一 attempt 对应不同结果");
        return receipt.get("response");
      }
    }
    lease(r, attempt, token, false);
    if (operation.equals("heartbeat")) {
      r.put("lease_expiry", Instant.now().plusSeconds(90).toString());
      db.update("agent_runs", r);
      return map(
          "run_id",
          r.get("id"),
          "attempt",
          r.get("attempt"),
          "lease_expires_at",
          r.get("lease_expiry"));
    }
    if (operation.equals("events")) {
      var event =
          db.one(
              "agent_run_events",
              "WHERE run_id=? AND attempt=? AND event_key=?",
              r.get("id"),
              attempt,
              b.get("event_key"));
      if (event != null)
        require(
            json(pick(event, "step", "status", "message"))
                .equals(json(pick(b, "step", "status", "message"))),
            409,
            "IDEMPOTENCY_CONFLICT",
            "同一 event_key 对应不同内容");
      else {
        event =
            addEvent(r, str(b, "event_key"), str(b, "step"), str(b, "status"), str(b, "message"));
        r.put("step", b.get("step"));
        db.update("agent_runs", r);
      }
      return map("seq", event.get("seq"), "run_id", r.get("id"));
    }
    if (operation.equals("fail")) return terminal(r, b, path, false, List.of());
    if (operation.equals("finalize"))
      require(r.get("snapshot_id") != null, 409, "CONTEXT_REQUIRED", "必须先获取运行上下文");
    var c = context(r, actor);
    if (operation.equals("context")) return c;
    if (operation.equals("sources")) {
      String source = p[4];
      var saved =
          list(c.get("source_versions")).stream()
              .map(Data::obj)
              .filter(s -> eq(s.get("source_id"), source))
              .findFirst()
              .orElseThrow(() -> new AppError(404, "NOT_FOUND", "来源不存在"));
      Integer n =
          q.get("revision") == null
              ? num(saved, "revision")
              : Integer.parseInt(q.get("revision").toString());
      require(n == num(saved, "revision"), 404, "NOT_FOUND", "修订不在快照中");
      Map<String, Object> locator = q.get("locator") == null ? null : obj(parse(str(q, "locator")));
      return files.sourceDetail(actor, source, n, locator, true, str(q, "chunk_id"));
    }
    if (operation.equals("search")) {
      String subjectStage = str(obj(r.get("subject")), "stage_id"), stage = str(b, "stage_id");
      if (stage == null) stage = subjectStage;
      require(subjectStage == null || eq(subjectStage, stage), 403, "FORBIDDEN", "阶段不在当前运行主题内");
      if (stage != null) {
        var st = db.get("stages", stage);
        require(
            st != null
                && eq(st.get("lab_id"), r.get("lab_id"))
                && eq(st.get("student_id"), r.get("student_id")),
            403,
            "FORBIDDEN",
            "阶段不在当前范围");
      }
      var eligible = new ArrayList<Map<String, Object>>();
      var sourceIds = list(b.get("source_ids"));
      for (Object sourceId : sourceIds)
        require(
            list(c.get("source_versions")).stream()
                .anyMatch(v -> eq(obj(v).get("source_id"), sourceId)),
            404,
            "NOT_FOUND",
            "来源不在当前运行快照中");
      for (Object raw : list(c.get("source_versions"))) {
        var sv = obj(raw);
        if (!sourceIds.isEmpty() && !sourceIds.contains(sv.get("source_id"))) continue;
        var s = db.get("sources", str(sv, "source_id"));
        // A guide must not mistake another authorized paper for its subject paper.
        if (eq(r.get("kind"), "paper_guide")) {
          String paperId = str(obj(r.get("subject")), "paper_id");
          if (eq(s.get("kind"), "paper") && !eq(s.get("entity_id"), paperId)) continue;
          if (list(b.get("source_kinds")).isEmpty() && !eq(s.get("kind"), "paper")) continue;
        }
        if (!list(b.get("source_kinds")).isEmpty()
            && !list(b.get("source_kinds")).contains(s.get("kind"))) continue;
        if (stage != null && !eq(r.get("kind"),"research_followup") && !stageSource(s, stage, r)) continue;
        for (var chunk :
            db.query(
                "chunks",
                "WHERE source_id=? AND source_revision=?",
                s.get("id"),
                sv.get("revision"))) {
          eligible.add(chunk);
        }
      }
      return retrieval.search(actor, r, c, eligible, b);
    }
    if (operation.equals("finalize")) {
      var result = obj(b.get("result"));
      var drafts = list(b.get("proposal_drafts"));
      require(
          eq(result.get("outcome"), "ok") || drafts.isEmpty(),
          422,
          "VALIDATION_ERROR",
          "资料不足时不能产生待执行建议");
      for (Object claim : list(result.get("claims"))) {
        var refs = list(obj(claim).get("source_refs"));
        access.evidence(actor, str(r, "lab_id"), refs);
        for (Object ref : refs) pinned(c, obj(ref));
      }
      var ids = new ArrayList<Object>();
      for (Object raw : drafts) {
        var draft = obj(raw);
        require(
            actions(str(r, "kind")).contains(str(draft, "action_type"))
                && eq(draft.get("target_student_id"), r.get("student_id"))
                && !before(draft.get("expires_at")),
            422,
            "VALIDATION_ERROR",
            "建议动作、目标或有效期无效");
        var proposal = new LinkedHashMap<>(draft);
        proposal.put("run_id", r.get("id"));
        proposal.put("status", "pending");
        proposal.put("version", 1);
        agents.validateProposal(actor, proposal, r);
        for (Object ref : agents.refs(proposal)) pinned(c, obj(ref));
        var saved = db.insert("proposals", proposal);
        ids.add(saved.get("id"));
      }
      return terminal(r, b, path, true, ids);
    }
    throw new AppError(404, "NOT_FOUND", "接口不存在");
  }

  Object terminal(
      Map<String, Object> r,
      Map<String, Object> b,
      String path,
      boolean success,
      List<Object> ids) {
    if (success) {
      var result = new LinkedHashMap<>(obj(b.get("result")));
      result.put("proposal_ids", ids);
      r.put("result", result);
      r.put("error", null);
    } else
      r.put(
          "error",
          map(
              "code",
              b.get("code"),
              "message",
              b.get("message"),
              "retryable",
              b.getOrDefault("retryable", false)));
    r.putAll(
        map(
            "status",
            success ? "succeeded" : "failed",
            "step",
            success ? "completed" : "failed",
            "finished_at",
            now(),
            "lease_expiry",
            null));
    db.bump("agent_runs", r);
    if(success && Set.of("risk_brief","weekly_report","stage_plan","paper_guide").contains(str(r,"kind"))
        && eq(obj(r.get("result")).get("outcome"),"ok")) {
      db.outbox("research_skill.completed",map("run_id",r.get("id")));
    }
    addEvent(
        r,
        (success ? "finalize:" : "fail:") + r.get("attempt"),
        success ? "completed" : "failed",
        success ? "completed" : "failed",
        success ? null : str(b, "message"));
    var response =
        map("run_id", r.get("id"), "status", r.get("status"), "version", r.get("version"));
    response.put(success ? "proposal_ids" : "error", success ? ids : r.get("error"));
    db.insert(
        "idempotency_records",
        map(
            "actor_id",
            r.get("requested_by"),
            "method",
            "INTERNAL",
            "path",
            "/internal/v1" + path,
            "key",
            String.valueOf(b.get("attempt")),
            "body_hash",
            hash(json(b)),
            "response",
            response,
            "expires_at",
            Instant.now().plusSeconds(604800).toString()));
    return response;
  }

  public static Set<String> terms(String query) {
    return LexicalSearch.terms(query);
  }
}
