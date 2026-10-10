package cn.yanji;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import cn.yanji.worker.BackgroundWorkers;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY;DB_CLOSE_DELAY=-1",
      "yanji.workers-enabled=false",
      "yanji.storage-root=./target/test-files",
      "yanji.internal-service-key=test-service-key-with-at-least-32-characters"
    })
@AutoConfigureMockMvc
class BackendIntegrationTest {
  @Autowired cn.yanji.service.ResearchExperienceService experiences;

  @Test
  void explicitDismissReasonIsStoredAndLearntAsPrivateCorrection() throws Exception {
    var previous=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,"kind","research_followup",
        "subject",map(),"input",map("question","基线实验的前置步骤"),"status","succeeded","result",map("summary","计划待确认")));
    var snapshot=db.insert("context_snapshots",map("run_id",previous.get("id"),"authorized_scope",map(),"source_versions",List.of(),"business_versions",List.of()));
    previous.put("snapshot_id",snapshot.get("id"));db.update("agent_runs",previous);
    var plan=db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"run_id",previous.get("id"),"state","active"));
    call("POST","/api/v1/research-followups/"+plan.get("id")+"/dismiss",studentToken,
        map("expected_version",plan.get("version"),"feedback_note","缺少基线实验的前置步骤，请先核对数据划分"),200,uid());
    assertThat(json(db.get("research_followups",str(plan,"id")).get("applied_items"))).contains("前置步骤");
    new TransactionTemplate(tm).execute(tx->{
      var context=map("tasks",List.of(),"source_versions",List.of());
      experiences.enrich(map("requested_by",student,"student_id",student,"lab_id",lab,"subject",map(),"input",map("question","基线实验前置步骤")),context);
      assertThat(json(context.get("feedback_memories"))).contains("user_correction","reason_known\":true","前置步骤");
      return null;
    });
  }

  @Test
  void experiencesLearnChosenTasksRefreshCompletionAndRespectRecipientScope() {
    new TransactionTemplate(tm).execute(s -> {
      var previous=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
          "kind","research_followup","subject",map("student_id",student),"input",map("question","怎么补充基线对照"),
          "status","succeeded","result",map("next_steps",List.of(map("title","阅读论文")))));
      var snapshot=db.insert("context_snapshots",map("run_id",previous.get("id"),"authorized_scope",map("requested_by",student),"source_versions",List.of(),"business_versions",List.of()));
      previous.put("snapshot_id",snapshot.get("id"));db.update("agent_runs",previous);
      var task=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",student,"title","补充基线对照","status","pending"));
      db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"run_id",previous.get("id"),"state","applied","task_ids",List.of(task.get("id"))));
      var current=map("requested_by",student,"student_id",student,"lab_id",lab,"subject",map(),"input",map("question","基线对照下一步"));
      var context=map("source_versions",List.of(),"tasks",List.of(task));
      experiences.enrich(current,context);
      assertThat(json(context.get("feedback_memories"))).contains("补充基线对照","customized_task_count","adoption_is_correctness\":false");
      int count=db.query("research_experiences","WHERE recipient_id=?",student).size();
      experiences.enrich(current,map("source_versions",List.of(),"tasks",List.of(task)));
      assertThat(db.query("research_experiences","WHERE recipient_id=?",student)).hasSize(count);
      task.put("status","completed");db.update("tasks",task);
      experiences.enrich(current,context);
      assertThat(json(context.get("feedback_memories"))).contains("completed_count\":1");
      assertThat(list(obj(context.get("reasoning_constraints")).get("completed_task_ids"))).contains(task.get("id"));
      current.put("requested_by",mentor);
      experiences.enrich(current,context);
      assertThat(list(context.get("feedback_memories"))).isEmpty();
      return null;
    });
  }

  @Test
  void feedbackLearningCanBeDisabledWithoutRemovingCurrentConstraints() {
    var disabled=new cn.yanji.service.ResearchExperienceService(db,access,
        new org.springframework.mock.env.MockEnvironment().withProperty("yanji.feedback-learning-enabled","false"));
    var context=map("mentor_constraints",List.of(map("content","当前导师要求")),"tasks",List.of());
    disabled.enrich(map("requested_by",student,"student_id",student,"lab_id",lab),context);
    assertThat(list(context.get("feedback_memories"))).isEmpty();
    assertThat(json(context.get("mentor_constraints"))).contains("当前导师要求");
    assertThat(obj(context.get("learning_policy")).get("feedback_learning_enabled")).isEqualTo(false);
  }

  @Test
  void historicalExperienceCannotCrossChangedSourceRevisionOrRevocation() {
    new TransactionTemplate(tm).execute(s -> {
      var source=db.insert("sources",map("lab_id",lab,"owner_id",student,"kind","guidance","entity_id",uid(),"visibility","lab","active_revision",1));
      var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,"kind","research_followup","subject",map(),"input",map("question","独有实验方案"),"status","succeeded"));
      var snapshot=db.insert("context_snapshots",map("run_id",run.get("id"),"authorized_scope",map(),"source_versions",List.of(map("source_id",source.get("id"),"revision",1)),"business_versions",List.of()));
      run.put("snapshot_id",snapshot.get("id"));db.update("agent_runs",run);
      var task=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",student,"title","独有实验方案","status","pending"));
      db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"run_id",run.get("id"),"state","applied","task_ids",List.of(task.get("id"))));
      var current=map("requested_by",student,"student_id",student,"lab_id",lab,"subject",map(),"input",map("question","独有实验方案"));
      var context=map("source_versions",List.of(map("source_id",source.get("id"),"revision",1)),"tasks",List.of());
      experiences.enrich(current,context);assertThat(list(context.get("feedback_memories"))).isNotEmpty();
      source.put("active_revision",2);db.update("sources",source);
      context.put("source_versions",List.of(map("source_id",source.get("id"),"revision",2)));
      experiences.enrich(current,context);assertThat(list(context.get("feedback_memories"))).isEmpty();
      source.put("revoked_at",now());db.update("sources",source);
      experiences.enrich(current,context);assertThat(list(context.get("feedback_memories"))).isEmpty();
      return null;
    });
  }

  @Autowired cn.yanji.service.ResearchFollowupService followups;
  @Test
  void completedResearchSkillUpdatesOnlyItsRequesterAndDoesNotLoop() {
    new TransactionTemplate(tm).execute(s -> {
      var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",mentor,"student_id",student,"kind","risk_brief","subject",map("student_id",student),"input",map("horizon_days",7),"status","succeeded","result",map("outcome","ok","summary","先核对单位")));
      var event=db.insert("outbox_events",map("event_type","research_skill.completed","payload",map("run_id",run.get("id"))));
      followups.consume(event);followups.consume(event);
      var rows=db.query("research_followups","WHERE student_id=?",student);
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("recipient_id")).isEqualTo(mentor);
      assertThat(list(rows.get(0).get("changes"))).hasSize(1);
      run.put("kind","research_followup");db.update("agent_runs",run);
      var looping=db.insert("outbox_events",map("event_type","research_skill.completed","payload",map("run_id",run.get("id"))));
      followups.consume(looping);
      assertThat(db.query("research_followups","WHERE student_id=?",student)).hasSize(1);
      return null;
    });
  }
  @Test
  void queuedPlansForOneRecipientDoNotStarveAnotherRecipient() {
    new TransactionTemplate(tm).execute(s -> {
      var running=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,"kind","research_followup","subject",map("student_id",student),"input",map("question","下一步")));
      db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"state","active","run_id",running.get("id")));
      for(int i=0;i<20;i++) db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"created_at","2026-01-01T00:00:00Z"));
      var ready=db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",mentor,"created_at","2026-02-01T00:00:00Z"));
      followups.dispatchPending();
      assertThat(db.get("research_followups",str(ready,"id")).get("state")).isEqualTo("active");
      return null;
    });
  }
  @Test
  void mentorConversationUsesScopedPlanAndIdempotentRun() throws Exception {
    var body=map("student_id",student,"question","我核对完台灯理论费用了，下一步怎么做？");
    String key=uid();
    var created=call("POST","/api/v1/research-followups",studentToken,body,200,key);
    var replay=call("POST","/api/v1/research-followups",studentToken,body,200,key);
    assertThat(replay.get("id")).isEqualTo(created.get("id"));
    assertThat(obj(created.get("run")).get("status")).isEqualTo("queued");
    call("POST","/api/v1/research-followups",otherToken,body,403,uid());
  }
  @Test
  void followupContextProvidesScopedRealSkillsForCurrentStudent() throws Exception {
    var asked=call("POST","/api/v1/research-followups",studentToken,
        map("student_id",student,"question","帮我检查安排并整理本周周报"),200,uid());
    String run=str(obj(asked.get("run")),"id");
    var lease=internal("/runs/"+run+"/claim",map("worker_id","skills-test"),200,0,null);
    var context=internal("/runs/"+run+"/context",null,200,num(lease,"attempt"),str(lease,"lease_token"));
    var skills=list(context.get("skill_catalog"));
    assertThat(skills).isNotEmpty();
    var risk=skills.stream().map(cn.yanji.core.Data::obj).filter(x->eq(x.get("id"),"risk_overview")).findFirst().orElseThrow();
    assertThat(obj(risk.get("subject")).get("student_id")).isEqualTo(student);
    assertThat(risk.get("kind")).isEqualTo("risk_brief");
    assertThat(num(obj(risk.get("input")),"horizon_days")).isEqualTo(7);
    assertThat(json(skills)).doesNotContain(other);
  }
  @Test
  void manualFollowupRetryCreatesRunWithOriginalQuestionWhenAutomationIsDisabled() throws Exception {
    var oldRun=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
        "kind","research_followup","subject",map("student_id",student),
        "input",map("question","怎么落实台灯论文修改意见？"),"status","failed"));
    var plan=db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,
        "state","active","run_id",oldRun.get("id")));
    var result=call("POST","/api/v1/research-followups/"+plan.get("id")+"/retry",studentToken,
        map("expected_version",plan.get("version")),200,uid());
    assertThat(obj(result.get("run")).get("status")).isEqualTo("queued");
    var freshRun=db.get("agent_runs",str(obj(result.get("run")),"id"));
    assertThat(obj(freshRun.get("input")).get("question")).isEqualTo("怎么落实台灯论文修改意见？");
  }
  @Test
  void previousPlanOutsideCurrentSourceSnapshotCannotBecomeConversationText() throws Exception {
    var oldSource=db.insert("sources",map("lab_id",lab,"owner_id",student,"kind","guidance",
        "entity_id",uid(),"visibility","lab","active_revision",1,"created_at","2000-01-01T00:00:00Z"));
    var previousRun=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
        "kind","research_followup","subject",map("student_id",student),"input",map("question","以前的问题"),
        "status","succeeded","result",map("summary","历史材料的专属结论不能越过快照边界")));
    var snapshot=db.insert("context_snapshots",map("run_id",previousRun.get("id"),"authorized_scope",map("lab_id",lab,"student_id",student,"requested_by",student),"business_versions",List.of(),"source_versions",
        List.of(map("source_id",oldSource.get("id"),"revision",1))));
    previousRun.put("snapshot_id",snapshot.get("id"));db.update("agent_runs",previousRun);
    db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,
        "state","active","run_id",previousRun.get("id")));
    for(int i=0;i<201;i++)db.insert("sources",map("lab_id",lab,"owner_id",student,"kind","guidance",
        "entity_id",uid(),"visibility","lab","active_revision",1));
    var asked=call("POST","/api/v1/research-followups",studentToken,
        map("student_id",student,"question","请继续安排研究工作"),200,uid());
    String run=str(obj(asked.get("run")),"id");
    var lease=internal("/runs/"+run+"/claim",map("worker_id","history-scope-test"),200,0,null);
    var context=internal("/runs/"+run+"/context",null,200,num(lease,"attempt"),str(lease,"lease_token"));
    assertThat(json(context)).doesNotContain("历史材料的专属结论不能越过快照边界");
  }
  @Test
  void replayCannotExposePlanAfterItsSourceIsRevoked() throws Exception {
    var source=db.insert("sources",map("lab_id",lab,"owner_id",student,"kind","guidance","entity_id",uid(),"visibility","lab","active_revision",1));
    var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
        "kind","research_followup","subject",map("student_id",student),"input",map("question","下一步"),
        "status","succeeded","result",map("summary","材料内的建议","claims",List.of(map("source_id",source.get("id"))))));
    var plan=db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"state","active","run_id",run.get("id")));
    String path="/api/v1/research-followups/"+plan.get("id")+"/dismiss",key=uid();
    var body=map("expected_version",plan.get("version"));
    call("POST",path,studentToken,body,200,key);
    source.put("revoked_at",now());db.update("sources",source);
    call("POST",path,studentToken,body,404,key);
    call("GET","/api/v1/research-followups/"+plan.get("id"),studentToken,null,404);
  }
  @Test
  void confirmingFollowupIsAtomicIdempotentAndRechecksMentorAuthorization() throws Exception {
    var plan=new TransactionTemplate(tm).execute(s -> {
      var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
          "kind","research_followup","subject",map("student_id",student),"input",map("question","下一步是什么"),
          "status","succeeded","result",map("outcome","ok","summary","先核对计算单位")));
      return db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",student,"state","active","run_id",run.get("id")));
    });
    var invalid=map("expected_version",plan.get("version"),"items",List.of(map("title","填写输入参数"),map("title","   ")));
    int before=db.query("tasks","WHERE student_id=?",student).size();
    String path="/api/v1/research-followups/"+plan.get("id")+"/apply";
    call("POST",path,studentToken,invalid,422,uid());
    assertThat(db.query("tasks","WHERE student_id=?",student)).hasSize(before);
    var body=map("expected_version",plan.get("version"),"items",List.of(map("title","填写台灯输入参数","acceptance_criteria",List.of("写清瓦数、小时、天数和电价"))));
    String key=uid();
    var saved=call("POST",path,studentToken,body,200,key);
    call("POST",path,studentToken,body,200,key);
    call("POST",path,studentToken,body,200,uid());
    assertThat(list(saved.get("task_ids"))).hasSize(1);
    assertThat(db.query("tasks","WHERE student_id=?",student)).hasSize(before+1);
    var teacherPlan=db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",mentor));
    var relation=db.one("mentorships","WHERE student_id=? AND mentor_id=? AND active=TRUE",student,mentor);
    relation.put("active",false);db.update("mentorships",relation);
    call("GET","/api/v1/research-followups/"+teacherPlan.get("id"),mentorToken,null,404);
    call("GET","/api/v1/research-followups?student_id="+student,mentorToken,null,403);
  }
  @Test
  void followupsMergeEventsAndKeepNewChangesWhileRunIsQueued() throws Exception {
    new TransactionTemplate(tm).execute(s -> {
      var task=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",student,"title","复核台灯用电计算","type","other","status","completed"));
      var event=db.insert("outbox_events",map("event_type","task.completed","payload",map("task_id",task.get("id"))));
      followups.consume(event); followups.consume(event);
      var rows=db.query("research_followups","WHERE student_id=?",student);
      assertThat(rows).hasSize(2);
      for(var row:rows) assertThat(list(row.get("changes"))).hasSize(1);
      followups.dispatchPending();
      for(var row:db.query("research_followups","WHERE student_id=?",student)) {
        assertThat(row.get("run_id")).isNotNull();
        assertThat(db.get("agent_runs",str(row,"run_id")).get("kind")).isEqualTo("research_followup");
      }
      var later=db.insert("outbox_events",map("event_type","task.completed","payload",map("task_id",task.get("id"))));
      followups.consume(later);followups.dispatchPending();
      assertThat(db.query("research_followups","WHERE student_id=? AND state='waiting'",student)).hasSize(2);
      return null;
    });
    var plan=obj(list(call("GET","/api/v1/research-followups",studentToken,null,200).get("items")).get(0));
    call("GET","/api/v1/research-followups/"+plan.get("id"),mentorToken,null,404);
    call("POST","/api/v1/research-followups/"+plan.get("id")+"/apply",studentToken,
        map("expected_version",plan.get("version"),"items",List.of(map("title","核对单位"))),409,uid());
  }
  @Test
  void researchFollowupListsAreScopedToCurrentStudentAndRecipient() throws Exception {
    var result = call("GET", "/api/v1/research-followups", studentToken, null, 200);
    assertThat(result.get("items")).isInstanceOf(List.class);
    call("GET", "/api/v1/research-followups?student_id=" + student, otherToken, null, 403);
    call("GET", "/api/v1/research-followups?student_id=" + student, mentorToken, null, 200);
  }
  @Autowired MockMvc mvc;
  @Test
  void riskContextIncludesCurrentTaskDependenciesWithoutSourceHits() throws Exception {
    var prerequisite=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",mentor,
        "title","核对参数","type","other","status","submitted"));
    var blocked=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",mentor,
        "title","整理结论","type","other","status","blocked"));
    db.insert("task_dependencies",map("task_id",blocked.get("id"),"depends_on_task_id",prerequisite.get("id")));
    var created=call("POST","/api/v1/agent-runs",studentToken,map("kind","risk_brief",
        "subject",map("student_id",student),"input",map("horizon_days",7)),202,uid());
    String run=str(created,"run_id");
    var lease=internal("/runs/"+run+"/claim",map("worker_id","dependency-test"),200,0,null);
    var context=internal("/runs/"+run+"/context",null,200,num(lease,"attempt"),str(lease,"lease_token"));
    var task=list(context.get("tasks")).stream().map(cn.yanji.core.Data::obj)
        .filter(t->eq(t.get("id"),blocked.get("id"))).findFirst().orElseThrow();
    assertThat(list(task.get("dependency_ids"))).containsExactly(prerequisite.get("id"));
  }
  @Test
  void followupLinksExistingTaskWithoutCreatingOrCompletingIt() throws Exception {
    var task=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",mentor,
        "title","核对参数","type","other","status","submitted"));
    var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
        "kind","research_followup","subject",map("student_id",student),"input",map("question","下一步"),
        "status","succeeded","result",map("outcome","ok")));
    var plan=db.insert("research_followups",map("lab_id",lab,"student_id",student,
        "recipient_id",student,"state","active","run_id",run.get("id")));
    int before=db.query("tasks","WHERE student_id=?",student).size();
    String path="/api/v1/research-followups/"+plan.get("id")+"/apply";
    var body=map("expected_version",plan.get("version"),"items",List.of(
        map("existing_task_id",task.get("id"),"expected_version",task.get("version"))));
    var saved=call("POST",path,studentToken,body,200,uid());
    call("POST",path,studentToken,body,200,uid());
    assertThat(list(saved.get("task_ids"))).containsExactly(task.get("id"));
    assertThat(db.query("tasks","WHERE student_id=?",student)).hasSize(before);
    assertThat(db.get("tasks",str(task,"id")).get("status")).isEqualTo("submitted");
    assertThat(num(db.get("tasks",str(task,"id")),"version")).isEqualTo(num(task,"version"));
  }
  @Test
  void structuredFollowupCreatesRealDependenciesAndRollsBackMissingPrerequisite() throws Exception {
    var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
        "kind","research_followup","subject",map("student_id",student),"input",map("question","安排基线"),
        "status","succeeded","result",map("outcome","ok")));
    var plan=db.insert("research_followups",map("lab_id",lab,"student_id",student,
        "recipient_id",student,"state","active","run_id",run.get("id")));
    String path="/api/v1/research-followups/"+plan.get("id")+"/apply";
    int before=db.query("tasks","WHERE student_id=?",student).size();
    call("POST",path,studentToken,map("expected_version",plan.get("version"),"items",List.of(
        map("step_id","s1","title","基线","type","experiment","depends_on",List.of()),
        map("step_id","s2","title","改进","type","experiment","depends_on",List.of("omitted-step")))),404,uid());
    assertThat(db.query("tasks","WHERE student_id=?",student)).hasSize(before);
    var saved=call("POST",path,studentToken,map("expected_version",plan.get("version"),"items",List.of(
        map("step_id","s1","title","基线","type","experiment","depends_on",List.of()),
        map("step_id","s2","title","改进","type","experiment","depends_on",List.of("s1")))),200,uid());
    var ids=list(saved.get("task_ids"));
    assertThat(ids).hasSize(2);
    assertThat(db.query("task_dependencies","WHERE task_id=?",ids.get(1))).hasSize(1);
    assertThat(db.query("task_dependencies","WHERE task_id=?",ids.get(1)).get(0).get("depends_on_task_id")).isEqualTo(ids.get(0));
  }
  @Test
  void followupLinkRejectsOtherStudentStaleCompletedAndDuplicateTasksAtomically() throws Exception {
    var task=db.insert("tasks",map("lab_id",lab,"student_id",student,"created_by",student,
        "title","核对参数","type","other","status","todo"));
    var foreign=db.insert("tasks",map("lab_id",lab,"student_id",other,"created_by",other,
        "title","其他学生任务","type","other","status","todo"));
    var run=db.insert("agent_runs",map("lab_id",lab,"requested_by",student,"student_id",student,
        "kind","research_followup","subject",map("student_id",student),"input",map("question","下一步"),
        "status","succeeded","result",map("outcome","ok")));
    var plan=db.insert("research_followups",map("lab_id",lab,"student_id",student,
        "recipient_id",student,"state","active","run_id",run.get("id")));
    String path="/api/v1/research-followups/"+plan.get("id")+"/apply";
    int before=db.query("tasks","WHERE student_id=?",student).size();
    call("POST",path,studentToken,map("expected_version",plan.get("version"),"items",List.of(
        map("title","不应留下的新任务"),map("existing_task_id",foreign.get("id"),"expected_version",1))),404,uid());
    call("POST",path,studentToken,map("expected_version",plan.get("version"),"items",List.of(
        map("existing_task_id",task.get("id"),"expected_version",99))),409,uid());
    var link=map("existing_task_id",task.get("id"),"expected_version",task.get("version"));
    call("POST",path,studentToken,map("expected_version",plan.get("version"),"items",List.of(link,link)),422,uid());
    task.put("status","completed");db.update("tasks",task);
    call("POST",path,studentToken,map("expected_version",plan.get("version"),"items",List.of(link)),409,uid());
    assertThat(db.query("tasks","WHERE student_id=?",student)).hasSize(before);
    assertThat(db.get("research_followups",str(plan,"id")).get("state")).isEqualTo("active");
  }
  @Autowired Repository db;
  @Autowired Contract contract;
  @Autowired BackgroundWorkers workers;
  @Autowired PlatformTransactionManager tm;
  @Autowired cn.yanji.service.Access access;
  @Autowired cn.yanji.service.FileSourceService fileSources;
  @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
  String student, mentor, other, studentToken, mentorToken, otherToken, lab;
  static final String KEY = "test-service-key-with-at-least-32-characters";

  Map<String, Object> call(String method, String path, String token, Object body, int expected)
      throws Exception {
    return call(method, path, token, body, expected, null);
  }

  Map<String, Object> call(
      String method, String path, String token, Object body, int expected, String idem)
      throws Exception {
    MockHttpServletRequestBuilder r =
        request(org.springframework.http.HttpMethod.valueOf(method), path)
            .contentType("application/json");
    if (token != null) r.header("Authorization", "Bearer " + token);
    if (body != null) r.content(json(body));
    if (idem != null) r.header("Idempotency-Key", idem);
    var result = mvc.perform(r).andReturn();
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(expected);
    assertThat(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    var envelope = obj(parse(result.getResponse().getContentAsString()));
    assertThat(envelope.keySet()).containsExactlyInAnyOrder("code", "data", "message");
    assertThat(num(envelope, "code")).isEqualTo(expected >= 400 ? expected * 100 : 0);
    return obj(envelope.get("data"));
  }

  Map<String, Object> internal(String path, Object body, int expected, int attempt, String lease)
      throws Exception {
    var r =
        (body == null
                ? get(java.net.URI.create("/internal/v1" + path))
                : post("/internal/v1" + path))
            .contentType("application/json")
            .header("X-Service-Key", KEY);
    if (body != null) r.content(json(body));
    if (attempt > 0) r.header("X-Run-Attempt", attempt);
    if (lease != null) r.header("X-Run-Lease", lease);
    var result = mvc.perform(r).andReturn();
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(expected);
    return obj(obj(parse(result.getResponse().getContentAsString())).get("data"));
  }

  String[] account(String role) throws Exception {
    String username = "u" + uid().replace("-", "").substring(0, 15);
    var reg =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            map(
                "username",
                username,
                "password",
                "Secret123",
                "display_name",
                role + "测试",
                "role",
                role),
            201);
    var login =
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            map(
                "username",
                username,
                "password",
                "Secret123",
                "installation_id",
                uid(),
                "device_label",
                "JUnit"),
            200);
    return new String[] {str(reg, "user_id"), str(login, "access_token")};
  }

  @BeforeEach
  void setup() throws Exception {
    var s = account("student");
    student = s[0];
    studentToken = s[1];
    var m = account("mentor");
    mentor = m[0];
    mentorToken = m[1];
    var o = account("student");
    other = o[0];
    otherToken = o[1];
    lab =
        str(
            obj(list(call("GET", "/api/v1/me", mentorToken, null, 200).get("memberships")).get(0)),
            "lab_id");
    var application =
        obj(
            call(
                    "POST",
                    "/api/v1/mentor-applications",
                    studentToken,
                    map("mentor_id", mentor, "lab_id", lab),
                    201)
                .get("application"));
    call(
        "POST",
        "/api/v1/mentor-applications/" + str(application, "id") + "/decision",
        mentorToken,
        map("decision", "approve"),
        200);
  }

  @Test
  void thesisHistoryFeedbackDownloadAndScopedResearch() throws Exception {
    var thesis =
        call(
            "POST",
            "/api/v1/theses",
            studentToken,
            map("title", "食堂排队研究", "lab_id", lab),
            201,
            "thesis-create");
    String id = str(thesis, "id"), base = "/api/v1/theses/" + id;
    assertThat(
            list(
                call("GET", "/api/v1/theses?student_id=" + student, mentorToken, null, 200)
                    .get("items")))
        .extracting(v -> str(obj(v), "id"))
        .contains(id);
    call("GET", base, otherToken, null, 404);
    call("GET", "/api/v1/theses?student_id=" + student, otherToken, null, 403);
    call(
        "POST",
        "/api/v1/theses",
        mentorToken,
        map("title", "越权", "lab_id", lab),
        403,
        "mentor-create");
    byte[] firstBytes = docx("排队初稿：模拟参与者等待时间");
    String file1 = thesisFile(firstBytes), file2 = thesisFile(docx("排队修改稿：补充中位数"));
    var body1 = map("file_id", file1, "change_summary", "初稿", "expected_version", 1);
    var r1 = call("POST", base + "/revisions", studentToken, body1, 201, "revision-one");
    assertThat(call("POST", base + "/revisions", studentToken, body1, 201, "revision-one"))
        .isEqualTo(r1);
    assertThat(
            call("POST", base + "/revisions", studentToken, body1, 201, "revision-one-new-key")
                .get("id"))
        .isEqualTo(r1.get("id"));
    call(
        "POST",
        base + "/revisions",
        studentToken,
        map("file_id", file2, "change_summary", "过期", "expected_version", 1),
        409,
        "stale-revision");
    var r2 =
        call(
            "POST",
            base + "/revisions",
            studentToken,
            map("file_id", file2, "change_summary", "补充统计", "expected_version", 2),
            201,
            "revision-two");
    String feedbackPath = base + "/revisions/" + str(r1, "id") + "/feedback";
    var fb = map("content", "请补充模拟样本取得方法和等待时间中位数", "decision", "changes_requested");
    call("POST", feedbackPath, studentToken, fb, 403, "student-feedback");
    var stranger = account("mentor");
    call("GET", base, stranger[1], null, 404);
    call("POST", feedbackPath, stranger[1], fb, 404, "stranger-feedback");
    var feedback = call("POST", feedbackPath, mentorToken, fb, 201, "feedback-one");
    assertThat(feedback.get("mentor_name")).isEqualTo("mentor测试");
    assertThat(call("POST", feedbackPath, mentorToken, fb, 201, "feedback-one"))
        .isEqualTo(feedback);
    call(
        "POST",
        base + "/revisions/" + str(r2, "id") + "/feedback",
        mentorToken,
        map("content", "方法说明清楚，修改稿通过", "decision", "accepted"),
        201,
        "feedback-accepted");
    var detail = call("GET", base, studentToken, null, 200);
    assertThat(num(detail, "version")).isEqualTo(3);
    assertThat(list(detail.get("revisions")))
        .extracting(v -> num(obj(v), "revision_number"))
        .containsExactly(2, 1);
    assertThat(list(obj(list(detail.get("revisions")).get(1)).get("feedback"))).hasSize(1);
    String downloadPath = base + "/revisions/" + str(r1, "id") + "/download";
    var download =
        mvc.perform(get(downloadPath).header("Authorization", "Bearer " + mentorToken)).andReturn();
    assertThat(download.getResponse().getStatus()).isEqualTo(200);
    assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(firstBytes);
    call("GET", downloadPath, otherToken, null, 404);
    workers.processFileOnce();
    workers.processFileOnce();
    assertThat(call("GET", base, studentToken, null, 200).get("revisions")).isNotNull();
    var readingStage = call("POST", "/api/v1/stages", mentorToken,
        map("student_id", student, "title", "论文复核", "goal", "对照当前稿件与导师意见"), 201);
    var run =
        call(
            "POST",
            "/api/v1/agent-runs",
            studentToken,
            map(
                "kind",
                "research_qa",
                "subject",
                map("student_id", student, "paper_id", r1.get("paper_id"), "stage_id", readingStage.get("id")),
                "input",
                map("question", "导师要求我补充哪些统计内容？")),
            202,
            "thesis-qa");
    String runId = str(run, "run_id");
    var claim =
        internal("/runs/" + runId + "/claim", map("worker_id", "thesis-test"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    var context = internal("/runs/" + runId + "/context", null, 200, attempt, lease);
    assertThat(json(context.get("feedback_memories")))
        .contains("模拟样本取得方法和等待时间中位数", "mentor_feedback");
    assertThat(db.query("research_experiences", "WHERE recipient_id=? AND student_id=?", student, student))
        .isNotEmpty();
    assertThat(list(context.get("source_versions")))
        .extracting(v -> str(obj(v), "source_id"))
        .contains(
            str(
                db.one(
                    "sources", "WHERE kind='thesis_feedback' AND entity_id=?", feedback.get("id")),
                "id"))
        .doesNotContain(str(db.get("papers", str(r2, "paper_id")), "source_id"));
    var search = internal("/runs/" + runId + "/search", map("query", "中位数"), 200, attempt, lease);
    assertThat(json(search)).contains("请补充模拟样本取得方法和等待时间中位数");
    assertThat(json(search)).doesNotContain("方法说明清楚，修改稿通过");
    var followup=call("POST","/api/v1/research-followups",studentToken,
        map("student_id",student,"question","导师让我补充中位数，应该怎么做？"),200,uid());
    String followupRun=str(obj(followup.get("run")),"id");
    var followupClaim=internal("/runs/"+followupRun+"/claim",map("worker_id","followup-test"),200,0,null);
    int followupAttempt=num(followupClaim,"attempt");
    String followupLease=str(followupClaim,"lease_token");
    var followupContext=internal("/runs/"+followupRun+"/context",null,200,followupAttempt,followupLease);
    assertThat(json(followupContext.get("mentor_constraints"))).contains("方法说明清楚，修改稿通过")
        .doesNotContain("请补充模拟样本取得方法和等待时间中位数");
    var followupSearch=internal("/runs/"+followupRun+"/search",map("query","中位数","stage_id",readingStage.get("id")),200,followupAttempt,followupLease);
    assertThat(json(followupSearch)).contains("请补充模拟样本取得方法和等待时间中位数");
    var feedbackSource =
        db.one("sources", "WHERE kind='thesis_feedback' AND entity_id=?", feedback.get("id"));
    String sourceId = str(feedbackSource, "id");
    call("GET", "/api/v1/sources/" + sourceId, otherToken, null, 404);
    var sourceExcerpt =
        internal(
            "/runs/"
                + runId
                + "/sources/"
                + sourceId
                + "?locator="
                + java.net.URLEncoder.encode(
                    json(map("kind", "record_field", "field", "content")),
                    java.nio.charset.StandardCharsets.UTF_8),
            null,
            200,
            attempt,
            lease);
    assertThat(sourceExcerpt.get("excerpt")).isEqualTo("请补充模拟样本取得方法和等待时间中位数");
    var realReference =
        map(
            "source_id",
            sourceId,
            "source_revision",
            1,
            "locator",
            map("kind", "record_field", "field", "content"),
            "quote",
            "模拟样本取得方法");
    access.evidence(student, lab, List.of(realReference));
    realReference.put("quote", "伪造的导师意见");
    assertThatThrownBy(() -> access.evidence(student, lab, List.of(realReference)))
        .isInstanceOf(AppError.class);
    new TransactionTemplate(tm)
        .execute(
            s -> {
              db.execute(
                  "UPDATE mentorships SET active=FALSE WHERE student_id=? AND mentor_id=?",
                  student,
                  mentor);
              return null;
            });
    call("GET", downloadPath, mentorToken, null, 404);
    call("POST", feedbackPath, mentorToken, fb, 404, "feedback-one");
    call("GET", base, mentorToken, null, 404);
    call("GET", "/api/v1/sources/" + sourceId, mentorToken, null, 404);
  }

  @Test
  void thesisMaximumLengthTitlePublishesWithoutTruncatingArchiveTitle() throws Exception {
    String title = "论文".repeat(250);
    var thesis =
        call(
            "POST",
            "/api/v1/theses",
            studentToken,
            map("title", title, "lab_id", lab),
            201,
            "long-title");
    String base = "/api/v1/theses/" + str(thesis, "id");
    call(
        "POST",
        base + "/revisions",
        studentToken,
        map("file_id", thesisFile(docx("内容")), "change_summary", "初稿", "expected_version", 1),
        201,
        "long-title-revision");
    assertThat(call("GET", base, studentToken, null, 200).get("title")).isEqualTo(title);
    // This test shares the integration database: finish its real ingest event before the next test.
    workers.processFileOnce();
  }

  @Test
  void thesisConcurrentVersionsAndFileOwnership() throws Exception {
    var thesis =
        call(
            "POST",
            "/api/v1/theses",
            studentToken,
            map("title", "并发论文", "lab_id", lab),
            201,
            "create-concurrent");
    String base = "/api/v1/theses/" + str(thesis, "id");
    String f1 = thesisFile(docx("第一稿")), f2 = thesisFile(docx("第二稿"));
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try {
      var jobs = new ArrayList<java.util.concurrent.Future<Integer>>();
      for (String file : List.of(f1, f2))
        jobs.add(
            executor.submit(
                () -> {
                  gate.await();
                  return mvc.perform(
                          post(base + "/revisions")
                              .contentType("application/json")
                              .header("Authorization", "Bearer " + studentToken)
                              .header("Idempotency-Key", file)
                              .content(
                                  json(
                                      map(
                                          "file_id",
                                          file,
                                          "change_summary",
                                          "提交",
                                          "expected_version",
                                          1))))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      gate.countDown();
      assertThat(List.of(jobs.get(0).get(), jobs.get(1).get())).containsExactlyInAnyOrder(201, 409);
    } finally {
      executor.shutdownNow();
    }
    assertThat(list(call("GET", base, studentToken, null, 200).get("revisions"))).hasSize(1);
    call(
        "POST",
        base + "/revisions",
        mentorToken,
        map("file_id", f1, "change_summary", "导师提交", "expected_version", 2),
        403,
        "mentor-revision");
    call(
        "POST",
        base + "/revisions",
        studentToken,
        map("file_id", uid(), "change_summary", "非法文件", "expected_version", 2),
        404,
        "missing-file");
    workers.processFileOnce();
  }

  String thesisFile(byte[] bytes) throws Exception {
    var response =
        mvc.perform(
                multipart("/api/v1/files")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "thesis.docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            bytes))
                    .param("purpose", "paper")
                    .param("lab_id", lab)
                    .param("client_upload_id", uid())
                    .header("Authorization", "Bearer " + studentToken)
                    .header("Idempotency-Key", uid()))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(201);
    return str(obj(obj(parse(response.getResponse().getContentAsString())).get("data")), "file_id");
  }

  @Test
  void readingNoteListRespectsPaperStudentConfirmationAndRevokedBinding() throws Exception {
    var paper = readyQaPaper("lab", "可阅读的资料内容");
    String pid = str(paper, "id");
    String url = "/api/v1/notes?paper_id=" + pid + "&student_id=" + student;
    var draft =
        call(
            "POST",
            "/api/v1/notes",
            studentToken,
            map("paper_id", pid, "claims", List.of("未确认的阅读想法")),
            201);
    var confirmed =
        call(
            "POST",
            "/api/v1/notes",
            studentToken,
            map("paper_id", pid, "claims", List.of("已核对的阅读要点")),
            201);
    call(
        "POST",
        "/api/v1/notes/" + str(confirmed, "id") + "/confirm",
        studentToken,
        map("expected_version", 1),
        200);
    var own = call("GET", url, studentToken, null, 200);
    assertThat(list(own.get("items")))
        .extracting(v -> str(obj(v), "id"))
        .containsExactlyInAnyOrder(str(draft, "id"), str(confirmed, "id"));
    assertThat(json(own)).contains("未确认的阅读想法", "已核对的阅读要点");
    assertThat(
            list(
                call("GET", "/api/v1/notes?paper_id=" + pid, studentToken, null, 200).get("items")))
        .hasSize(2);
    var mentorNotes = call("GET", url, mentorToken, null, 200);
    assertThat(list(mentorNotes.get("items")))
        .extracting(v -> str(obj(v), "id"))
        .containsExactly(str(confirmed, "id"));
    assertThat(json(mentorNotes)).doesNotContain("未确认的阅读想法");
    var join =
        obj(
            call(
                    "POST",
                    "/api/v1/mentor-applications",
                    otherToken,
                    map("mentor_id", mentor, "lab_id", lab),
                    201)
                .get("application"));
    call(
        "POST",
        "/api/v1/mentor-applications/" + str(join, "id") + "/decision",
        mentorToken,
        map("decision", "approve"),
        200);
    var othersDraft =
        call(
            "POST",
            "/api/v1/notes",
            otherToken,
            map("paper_id", pid, "claims", List.of("另一学生私有草稿")),
            201);
    assertThat(
            list(
                call("GET", "/api/v1/notes?paper_id=" + pid, studentToken, null, 200).get("items")))
        .extracting(v -> str(obj(v), "id"))
        .doesNotContain(str(othersDraft, "id"));
    assertThat(
            list(call("GET", "/api/v1/notes?paper_id=" + pid, otherToken, null, 200).get("items")))
        .extracting(v -> str(obj(v), "id"))
        .containsExactly(str(othersDraft, "id"));
    call("GET", url, otherToken, null, 404);
    call("GET", url, account("mentor")[1], null, 404);
    call("GET", "/api/v1/notes", studentToken, null, 422);
    call("GET", "/api/v1/notes?paper_id=invalid", studentToken, null, 422);
    call("GET", "/api/v1/notes?paper_id=" + uid(), studentToken, null, 404);
    var privatePaper = readyQaPaper("private", "私有资料");
    call(
        "GET",
        "/api/v1/notes?paper_id=" + str(privatePaper, "id") + "&student_id=" + student,
        mentorToken,
        null,
        404);
    new TransactionTemplate(tm)
        .execute(
            s -> {
              db.execute(
                  "UPDATE mentorships SET active=FALSE WHERE student_id=? AND mentor_id=?",
                  student,
                  mentor);
              return null;
            });
    call("GET", url, mentorToken, null, 404);
    assertThat(list(call("GET", url, studentToken, null, 200).get("items"))).hasSize(2);
  }

  @Test
  void identityAndPasswordRevocation() throws Exception {
    var me = call("GET", "/api/v1/me", studentToken, null, 200);
    assertThat(me.get("collaboration_enabled")).isEqualTo(true);
    assertThat(me.get("onboarding_status")).isEqualTo("joined");
    call("GET", "/api/v1/me", null, null, 401);
    call(
        "POST",
        "/api/v1/auth/password",
        studentToken,
        map("current_password", "wrong", "new_password", "Secret456"),
        401);
    call(
        "POST",
        "/api/v1/auth/password",
        studentToken,
        map("current_password", "Secret123", "new_password", "Secret456"),
        200);
    call("GET", "/api/v1/me", studentToken, null, 401);
  }

  @Test
  void strictValidationAndUnknownRoutes() throws Exception {
    call(
        "POST",
        "/api/v1/auth/register",
        null,
        map("username", "abc", "password", "123", "display_name", "x", "role", "admin"),
        422);
    call(
        "POST",
        "/api/v1/tasks",
        studentToken,
        map("student_id", student, "title", "task", "actor_id", mentor),
        422);
    call("GET", "/api/v1/tasks?limit=101", studentToken, null, 422);
    call("GET", "/api/v1/tasks?cursor=invalid", studentToken, null, 422);
    call("GET", "/api/v1/unknown", studentToken, null, 404);
  }

  @Test
  void stageAndTaskLifecycleAndVersion() throws Exception {
    var stage =
        call(
            "POST",
            "/api/v1/stages",
            mentorToken,
            map("student_id", student, "title", "阶段", "goal", "目标", "due_at", "2026-11-01"),
            201);
    String sid = str(stage, "id");
    assertThat(stage.get("due_at")).isEqualTo("2026-11-01T15:59:59Z");
    call(
        "POST",
        "/api/v1/stages",
        studentToken,
        map("student_id", student, "title", "阶段", "goal", "目标"),
        403);
    var task =
        call(
            "POST",
            "/api/v1/tasks",
            mentorToken,
            map("student_id", student, "stage_id", sid, "title", "培养任务"),
            201);
    String tid = str(task, "id");
    call(
        "PATCH",
        "/api/v1/tasks/" + tid,
        studentToken,
        map("expected_version", 1, "due_at", "2026-11-02"),
        403);
    call(
        "POST",
        "/api/v1/tasks/" + tid + "/transition",
        studentToken,
        map("expected_version", 1, "to_status", "in_progress"),
        200,
        "start");
    call(
        "PATCH",
        "/api/v1/tasks/" + tid,
        studentToken,
        map("expected_version", 1, "title", "过期"),
        409);
    call(
        "POST",
        "/api/v1/tasks/" + tid + "/transition",
        studentToken,
        map("expected_version", 2, "to_status", "submitted"),
        200,
        "submit");
    call(
        "POST",
        "/api/v1/tasks/" + tid + "/transition",
        studentToken,
        map("expected_version", 3, "to_status", "completed"),
        403,
        "student-complete");
    call(
        "POST",
        "/api/v1/tasks/" + tid + "/transition",
        mentorToken,
        map("expected_version", 3, "to_status", "completed"),
        200,
        "accept");
    call(
        "POST",
        "/api/v1/stages/" + sid + "/submit",
        studentToken,
        map("expected_version", 1),
        200,
        "stage-submit");
    call(
        "POST",
        "/api/v1/stages/" + sid + "/review",
        mentorToken,
        map("expected_version", 2, "decision", "accept"),
        200);
    call("GET", "/api/v1/tasks/" + tid, otherToken, null, 404);
  }

  @Test
  void dependencyCyclesAndIdempotentTransition() throws Exception {
    var a =
        call("POST", "/api/v1/tasks", studentToken, map("student_id", student, "title", "A"), 201);
    String aid = str(a, "id");
    var b =
        call(
            "POST",
            "/api/v1/tasks",
            studentToken,
            map("student_id", student, "title", "B", "dependency_ids", List.of(aid)),
            201);
    String bid = str(b, "id");
    call("POST", "/api/v1/tasks/" + bid + "/transition", studentToken,
        map("expected_version", 1, "to_status", "in_progress"), 400, "blocked-start");
    var stillTodo = call("GET", "/api/v1/tasks/" + bid, studentToken, null, 200);
    assertThat(stillTodo.get("status")).isEqualTo("todo");
    assertThat(str(stillTodo,"blocking_reason")).contains("A");
    assertThat(((Number) stillTodo.get("version")).intValue()).isEqualTo(1);
    call(
        "PATCH",
        "/api/v1/tasks/" + aid,
        studentToken,
        map("expected_version", 1, "dependency_ids", List.of(bid)),
        409);
    call(
        "POST",
        "/api/v1/tasks/" + bid + "/transition",
        studentToken,
        map("expected_version", 1, "to_status", "completed"),
        400,
        "blocked");
    var first =
        call(
            "POST",
            "/api/v1/tasks/" + aid + "/transition",
            studentToken,
            map("expected_version", 1, "to_status", "completed"),
            200,
            "complete");
    var replay =
        call(
            "POST",
            "/api/v1/tasks/" + aid + "/transition",
            studentToken,
            map("expected_version", 1, "to_status", "completed"),
            200,
            "complete");
    assertThat(replay).isEqualTo(first);
    var started = call("POST", "/api/v1/tasks/" + bid + "/transition", studentToken,
        map("expected_version", 1, "to_status", "in_progress"), 200, "unblocked-start");
    assertThat(started.get("status")).isEqualTo("in_progress");
    assertThat(started.get("blocking_reason")).isNull();
    call(
        "POST",
        "/api/v1/tasks/" + aid + "/transition",
        studentToken,
        map("expected_version", 1, "to_status", "blocked"),
        409,
        "complete");
  }

  @Test
  void experimentsRespectVisibilityAndHistory() throws Exception {
    var e =
        call(
            "POST",
            "/api/v1/experiments",
            studentToken,
            map("purpose", "验证", "visibility", "mentors", "results", "结果一"),
            201);
    String id = str(e, "id");
    call("GET", "/api/v1/experiments/" + id, mentorToken, null, 404);
    var confirm =
        call(
            "POST",
            "/api/v1/experiments/" + id + "/confirm",
            studentToken,
            map("expected_version", 1),
            200);
    assertThat(confirm.get("source_id")).isNotNull();
    call("GET", "/api/v1/experiments/" + id, mentorToken, null, 200);
    call("GET", "/api/v1/experiments/" + id, otherToken, null, 404);
    call(
        "PATCH",
        "/api/v1/experiments/" + id,
        studentToken,
        map("expected_version", 2, "purpose", "验证二", "results", "结果二"),
        200);
    call("GET", "/api/v1/experiments/" + id, mentorToken, null, 404);
    var old = call("GET", "/api/v1/experiments/" + id + "?revision=1", studentToken, null, 200);
    assertThat(old.get("results")).isEqualTo("结果一");
    assertThat(old.get("confirmed_at")).isNotNull();
    var privateRecord =
        call(
            "POST",
            "/api/v1/experiments",
            studentToken,
            map("purpose", "私密", "results", "私密结果"),
            201);
    call(
        "POST",
        "/api/v1/experiments/" + str(privateRecord, "id") + "/confirm",
        studentToken,
        map("expected_version", 1),
        200);
    call("GET", "/api/v1/experiments/" + str(privateRecord, "id"), mentorToken, null, 404);
  }

  @Test
  void weeklyReportSubmissionReviewAndHistory() throws Exception {
    call("POST", "/api/v1/weekly-reports", studentToken, map("week_start", "2026-10-07"), 400);
    var report =
        call("POST", "/api/v1/weekly-reports", studentToken, map("week_start", "2026-10-05"), 201);
    String id = str(report, "id");
    call("GET", "/api/v1/weekly-reports/" + id, mentorToken, null, 404);
    call(
        "POST",
        "/api/v1/weekly-reports/" + id + "/submit",
        studentToken,
        map("expected_version", 1),
        200,
        "report-submit");
    call(
        "POST",
        "/api/v1/weekly-reports/" + id + "/reviews",
        mentorToken,
        map(
            "expected_version",
            2,
            "report_revision",
            1,
            "decision",
            "changes_requested",
            "body",
            "请补充"),
        200);
    call(
        "PATCH",
        "/api/v1/weekly-reports/" + id,
        studentToken,
        map(
            "expected_version",
            3,
            "done_items",
            List.of(),
            "issues",
            List.of(),
            "next_plan",
            List.of(),
            "help_needed",
            List.of()),
        200);
    var old = call("GET", "/api/v1/weekly-reports/" + id + "?revision=1", studentToken, null, 200);
    assertThat(old.get("submitted_at")).isNotNull();
  }

  @Test
  void stablePagination() throws Exception {
    for (int i = 0; i < 3; i++)
      call(
          "POST",
          "/api/v1/tasks",
          studentToken,
          map("student_id", student, "title", "task" + i),
          201);
    var page = call("GET", "/api/v1/tasks?limit=2", studentToken, null, 200);
    assertThat(list(page.get("tasks"))).hasSize(2);
    assertThat(page.get("next_cursor")).isNotNull();
    var next =
        call(
            "GET",
            "/api/v1/tasks?limit=2&cursor=" + str(page, "next_cursor"),
            studentToken,
            null,
            200);
    assertThat(list(next.get("tasks"))).hasSize(1);
    assertThat(next.get("next_cursor")).isNull();
  }

  @Test
  void leaseAndTerminalReplay() throws Exception {
    var created =
        call(
            "POST",
            "/api/v1/agent-runs",
            studentToken,
            map(
                "kind",
                "research_qa",
                "subject",
                map("student_id", student),
                "input",
                map("question", "科研问题", "private_key", "should-not-leak")),
            202,
            "run");
    String id = str(created, "run_id");
    var publicRun = call("GET", "/api/v1/agent-runs/" + id, studentToken, null, 200);
    assertThat(obj(publicRun.get("input"))).doesNotContainKey("private_key");
    assertThat(publicRun).doesNotContainKeys("lease_token_hash", "snapshot_id");
    var claim = internal("/runs/" + id + "/claim", map("worker_id", "test"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    internal("/runs/" + id + "/claim", map("worker_id", "other"), 409, 0, null);
    internal("/runs/" + id + "/context", null, 409, attempt, "wrong");
    var context = internal("/runs/" + id + "/context", null, 200, attempt, lease);
    assertThat(context.get("schema_version")).isEqualTo(2);
    assertThat(context.get("student_id")).isEqualTo(student);
    var finalBody =
        map(
            "attempt",
            attempt,
            "result",
            map("outcome", "insufficient_evidence", "summary", "需要资料"));
    var first = internal("/runs/" + id + "/finalize", finalBody, 200, 0, lease);
    var replay = internal("/runs/" + id + "/finalize", finalBody, 200, 0, lease);
    assertThat(replay).isEqualTo(first);
    internal(
        "/runs/" + id + "/finalize",
        map("attempt", attempt, "result", map("outcome", "needs_input", "summary", "changed")),
        409,
        0,
        lease);
    assertThat(call("GET", "/api/v1/agent-runs/" + id, studentToken, null, 200).get("status"))
        .isEqualTo("succeeded");
  }

  @Test
  void proposalApprovalChecksBaseVersionsAndCreatesDraft() throws Exception {
    String week = "2026-10-05";
    var previousReport = call("POST", "/api/v1/weekly-reports", studentToken,
        map("week_start", week), 201);
    var secondReport = call("POST", "/api/v1/weekly-reports", studentToken,
        map("week_start", week), 201);
    assertThat(secondReport.get("id")).isNotEqualTo(previousReport.get("id"));
    String id =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "weekly_report",
                    "subject",
                    map("student_id", student),
                    "input",
                    map("week_start", week)),
                202,
                "weekly"),
            "run_id");
    var claim = internal("/runs/" + id + "/claim", map("worker_id", "test"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    internal("/runs/" + id + "/context", null, 200, attempt, lease);
    var draft =
        map(
            "action_type",
            "weekly_report.draft",
            "target_student_id",
            student,
            "payload",
            map(
                "week_start",
                week,
                "done_items",
                List.of(),
                "issues",
                List.of(),
                "next_plan",
                List.of(),
                "help_needed",
                List.of()),
            "reason",
            "草稿",
            "base_versions",
            List.of(map("type", "weekly_report", "id", null, "version", 0)),
            "expires_at",
            Instant.now().plusSeconds(3600).toString());
    var result =
        internal(
            "/runs/" + id + "/finalize",
            map(
                "attempt",
                attempt,
                "result",
                map("outcome", "ok", "summary", "草稿"),
                "proposal_drafts",
                List.of(draft)),
            200,
            0,
            lease);
    String pid = list(result.get("proposal_ids")).get(0).toString();
    call(
        "POST",
        "/api/v1/proposals/" + pid + "/approve",
        mentorToken,
        map("expected_version", 1),
        404,
        "mentor-approval");
    var receipt =
        call(
            "POST",
            "/api/v1/proposals/" + pid + "/approve",
            studentToken,
            map("expected_version", 1),
            200,
            "approve");
    assertThat(obj(receipt.get("execution_receipt")).get("status")).isEqualTo("draft");
    assertThat(list(obj(receipt.get("execution_receipt")).get("resource_ids")))
        .doesNotContain(previousReport.get("id"), secondReport.get("id"));
    assertThat(
            call(
                "POST",
                "/api/v1/proposals/" + pid + "/approve",
                studentToken,
                map("expected_version", 1),
                200,
                "approve"))
        .isEqualTo(receipt);
  }

  @Test
  void weeklyAiUpdatesOnlyExplicitDraftAndPreservesHumanItems() throws Exception {
    String week = "2026-10-05";
    var experiment = call("POST", "/api/v1/experiments", studentToken,
        map("purpose", "整理参数", "results", "参数已整理"), 201);
    var confirmed = call("POST", "/api/v1/experiments/" + str(experiment, "id") + "/confirm", studentToken,
        map("expected_version", 1), 200, "confirm-report-evidence");
    var evidence = map("source_id", confirmed.get("source_id"), "source_revision", confirmed.get("source_revision"),
        "locator", map("kind", "record_field", "field", "results"), "quote", "参数已整理");
    var report = call("POST", "/api/v1/weekly-reports", studentToken,
        map("week_start", week, "issues", List.of(map("id", uid(), "text", "需要补充参数", "origin", "human"))), 201);
    var sibling = call("POST", "/api/v1/weekly-reports", studentToken, map("week_start", week), 201);
    String reportId = str(report, "id");
    assertThat(db.get("weekly_reports", reportId)).containsEntry("week_start", week)
        .containsEntry("student_id", student).containsEntry("status", "draft").containsEntry("lab_id", lab);
    String runId = str(call("POST", "/api/v1/agent-runs", studentToken,
        map("kind", "weekly_report", "subject", map("student_id", student),
            "input", map("week_start", week, "report_id", reportId)), 202, "update-report-run"), "run_id");
    var claim = internal("/runs/" + runId + "/claim", map("worker_id", "test"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    var context = internal("/runs/" + runId + "/context", null, 200, attempt, lease);
    assertThat(list(context.get("business_versions")))
        .anySatisfy(raw -> assertThat(obj(raw)).containsEntry("type", "weekly_report").containsEntry("id", reportId));
    var finalized = internal("/runs/" + runId + "/finalize", map("attempt", attempt,
        "result", map("outcome", "ok", "summary", "草稿"), "proposal_drafts", List.of(map(
            "action_type", "weekly_report.draft", "target_student_id", student,
            "payload", map("week_start", week, "done_items", List.of(), "issues", List.of(),
                "next_plan", List.of(map("id", uid(), "text", "整理参数", "origin", "agent", "source_refs", List.of(evidence))), "help_needed", List.of()),
            "reason", "整理草稿", "base_versions", List.of(map("type", "weekly_report", "id", reportId, "version", report.get("version"))),
            "expires_at", Instant.now().plusSeconds(3600).toString()))), 200, 0, lease);
    String pid = list(finalized.get("proposal_ids")).get(0).toString();
    var approved = call("POST", "/api/v1/proposals/" + pid + "/approve", studentToken, map("expected_version", 1), 200, "update-report-approve");
    assertThat(list(obj(approved.get("execution_receipt")).get("resource_ids"))).containsExactly(reportId);
    var updated = call("GET", "/api/v1/weekly-reports/" + reportId, studentToken, null, 200);
    assertThat(list(updated.get("issues"))).hasSize(1);
    assertThat(list(updated.get("next_plan"))).hasSize(1);
    var unchanged = call("GET", "/api/v1/weekly-reports/" + str(sibling, "id"), studentToken, null, 200);
    assertThat(unchanged.get("version")).isEqualTo(sibling.get("version"));
  }

  @Test
  void mentorFeedbackContextPinsSubmittedReportAndItsVersion() throws Exception {
    var experiment = call("POST", "/api/v1/experiments", studentToken,
        map("purpose", "核对费用", "results", "给定参数估算为0.72元", "visibility", "private"), 201);
    var confirmed = call("POST", "/api/v1/experiments/" + str(experiment, "id") + "/confirm", studentToken,
        map("expected_version", experiment.get("version")), 200, "feedback-confirm-record");
    var report = call("POST", "/api/v1/weekly-reports", studentToken,
        map("week_start", "2026-10-05", "done_items", List.of(map("id", uid(), "text", "给定参数估算为0.72元",
            "origin", "human", "source_refs", List.of(map("source_id", confirmed.get("source_id"), "source_revision", 1,
                "chunk_id", null, "locator", map("kind", "record_field", "field", "results"), "quote", "给定参数估算为0.72元")))), "issues",
            List.of(map("id", uid(), "text", "实际电价尚未获取", "origin", "human"))), 201);
    String reportId = str(report, "id");
    call("POST", "/api/v1/weekly-reports/" + reportId + "/submit", studentToken,
        map("expected_version", report.get("version")), 200, "feedback-submit");
    var run = call("POST", "/api/v1/agent-runs", mentorToken,
        map("kind", "risk_brief", "subject", map("student_id", student),
            "input", map("horizon_days", 7, "report_id", reportId)), 202, "report-feedback");
    String runId = str(run, "run_id");
    var lease = internal("/runs/" + runId + "/claim", map("worker_id", "test"), 200, 0, null);
    var context = internal("/runs/" + runId + "/context", null, 200,
        num(lease, "attempt"), str(lease, "lease_token"));
    var target = obj(context.get("weekly_report"));
    assertThat(target.get("id")).isEqualTo(reportId);
    assertThat(str(target, "status")).isEqualTo("submitted");
    assertThat(list(target.get("issues"))).hasSize(1);
    assertThat(obj(list(target.get("issues")).get(0))).doesNotContainKey("source_refs");
    assertThat(target.get("source_id")).isNotNull();
    assertThat(list(context.get("source_versions"))).hasSize(1);
    assertThat(list(context.get("business_versions")))
        .anySatisfy(raw -> assertThat(obj(raw)).containsEntry("type", "weekly_report").containsEntry("id", reportId));
    assertThat(obj(list(target.get("done_items")).get(0))).doesNotContainKey("source_refs");
    call("GET", "/api/v1/agent-runs/" + runId, mentorToken, null, 200);
    call("GET", "/api/v1/experiments/" + str(experiment, "id"), mentorToken, null, 404);
    call("GET", "/api/v1/sources/" + target.get("source_id"), mentorToken, null, 200);
    var sourceRevision = db.one("source_revisions", "WHERE source_id=? AND revision=?", target.get("source_id"), target.get("source_revision"));
    var sourceContent = obj(parse(str(sourceRevision, "text_snapshot")));
    assertThat(str(sourceContent, "done_items")).isEqualTo("给定参数估算为0.72元");
    assertThat(json(sourceContent)).doesNotContain("source_refs", "origin", "chunk_id");
    var repeat = internal("/runs/" + runId + "/context", null, 200, num(lease, "attempt"), str(lease, "lease_token"));
    assertThat(repeat.get("snapshot_id")).isEqualTo(context.get("snapshot_id"));
  }

  @Test
  void agentConsumerClosesRevokedRequestsInsteadOfDispatchingForever() throws Exception {
    var run = call("POST", "/api/v1/agent-runs", mentorToken,
        map("kind", "risk_brief", "subject", map("student_id", student), "input", map("horizon_days", 7)),
        202, "revoked-dispatch");
    String runId = str(run, "run_id");
    new TransactionTemplate(tm).execute(status -> {
      var relation = db.one("mentorships", "WHERE mentor_id=? AND student_id=?", mentor, student);
      relation.put("active", false);
      db.update("mentorships", relation);
      var event = db.query("outbox_events", "WHERE event_type='agent_run.requested'").stream()
          .filter(e -> eq(obj(e.get("payload")).get("run_id"), runId)).findFirst().orElseThrow();
      // This suite shares H2 data across tests. Put this fixture before prior queued fixtures.
      event.put("created_at", "1900-01-01T00:00:00Z");
      db.update("outbox_events", event);
      return null;
    });
    workers.processAgentOnce();
    var saved = db.get("agent_runs", runId);
    assertThat(str(saved, "status")).isEqualTo("failed");
    assertThat(str(obj(saved.get("error")), "code")).isEqualTo("SCOPE_REVOKED");
    var event = db.query("outbox_events", "WHERE event_type='agent_run.requested'").stream()
        .filter(e -> eq(obj(e.get("payload")).get("run_id"), runId)).findFirst().orElseThrow();
    assertThat(event.get("published_at")).isNotNull();
  }

  @Test
  void riskContextPreservesConfirmedRecordTaskAssociation() throws Exception {
    var task = call("POST", "/api/v1/tasks", mentorToken, map("student_id", student, "title", "台灯计算"), 201);
    var record = call("POST", "/api/v1/experiments", studentToken,
        map("task_id", task.get("id"), "purpose", "核对台灯费用", "results", "计算完成，实测条件待确认", "visibility", "mentors"), 201);
    call("POST", "/api/v1/experiments/" + str(record, "id") + "/confirm", studentToken, map("expected_version", 1), 200);
    String run = str(call("POST", "/api/v1/agent-runs", mentorToken,
        map("kind", "risk_brief", "subject", map("student_id", student), "input", map("horizon_days", 7)), 202, "risk-record-task"), "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "qa"), 200, 0, null);
    var context = internal("/runs/" + run + "/context", null, 200, num(claim, "attempt"), str(claim, "lease_token"));
    var confirmed = list(context.get("confirmed_experiments")).stream().map(v -> obj(v))
        .filter(v -> eq(v.get("id"), record.get("id"))).findFirst().orElseThrow();
    assertThat(confirmed.get("task_id")).isEqualTo(task.get("id"));
  }

  @Test
  void largeWeeklyContextKeepsOmittedEvidenceSearchableAndSnapshotStable() throws Exception {
    var sourceQueries = new LinkedHashMap<String, String>();
    for (char suffix = 'a'; suffix <= 'l'; suffix++) {
      String purpose = "budgetrecord" + suffix;
      var record =
          call(
              "POST",
              "/api/v1/experiments",
              studentToken,
              map("purpose", purpose, "results", "已完成"),
              201);
      var confirmed =
          call(
              "POST",
              "/api/v1/experiments/" + str(record, "id") + "/confirm",
              studentToken,
              map("expected_version", 1),
              200);
      sourceQueries.put(str(confirmed, "source_id"), purpose);
    }
    var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String week =
        today
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toString();
    String run =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "weekly_report",
                    "subject",
                    map("student_id", student),
                    "input",
                    map("week_start", week)),
                202,
                "bounded-week"),
            "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "qa"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    var context = internal("/runs/" + run + "/context", null, 200, attempt, lease);
    assertThat(list(context.get("confirmed_experiments"))).hasSize(10);
    var coverage = obj(context.get("record_coverage"));
    assertThat(num(coverage, "total_records")).isEqualTo(12);
    assertThat(num(coverage, "omitted_records")).isEqualTo(2);
    assertThat(coverage.get("partial")).isEqualTo(true);
    assertThat(list(context.get("source_versions"))).hasSize(12);
    var included =
        list(context.get("confirmed_experiments")).stream()
            .map(r -> str(obj(r), "source_id"))
            .toList();
    String omitted =
        sourceQueries.keySet().stream()
            .filter(s -> !included.contains(s))
            .findFirst()
            .orElseThrow();
    var search =
        internal(
            "/runs/" + run + "/search",
            map("query", sourceQueries.get(omitted), "top_k", 10, "source_ids", List.of(omitted)),
            200,
            attempt,
            lease);
    assertThat(list(search.get("chunks")).stream().map(c -> str(obj(c), "source_id")))
        .contains(omitted);
    assertThat(list(search.get("chunks")).stream().map(c -> str(obj(c), "source_id")))
        .allMatch(omitted::equals);
    internal(
        "/runs/" + run + "/search",
        map("query", "实验", "source_ids", List.of(uid())),
        404,
        attempt,
        lease);
    assertThat(internal("/runs/" + run + "/context", null, 200, attempt, lease)).isEqualTo(context);
  }

  @Test
  void revokeScopeBlocksCachedRunReplayAndCallbacks() throws Exception {
    boolean liveRedis = Boolean.getBoolean("verify.local.rag");
    if (liveRedis)
      environment
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "qa-search-cache", map("yanji.redis-enabled", true)));
    try {
      var evidence =
          call(
              "POST",
              "/api/v1/experiments",
              studentToken,
              map("purpose", "实验缓存权限", "results", "授权实验材料", "visibility", "mentors"),
              201);
      call(
          "POST",
          "/api/v1/experiments/" + str(evidence, "id") + "/confirm",
          studentToken,
          map("expected_version", 1),
          200);
      var request =
          map(
              "kind",
              "research_qa",
              "subject",
              map("student_id", student),
              "input",
              map("question", "test"));
      String id =
          str(call("POST", "/api/v1/agent-runs", mentorToken, request, 202, "revoke"), "run_id");
      var claim = internal("/runs/" + id + "/claim", map("worker_id", "test"), 200, 0, null);
      int attempt = num(claim, "attempt");
      String lease = str(claim, "lease_token");
      internal("/runs/" + id + "/context", null, 200, attempt, lease);
      var first = internal("/runs/" + id + "/search", map("query", "实验"), 200, attempt, lease);
      assertThat(list(first.get("chunks"))).isNotEmpty();
      var repeated = internal("/runs/" + id + "/search", map("query", "实验"), 200, attempt, lease);
      if (liveRedis) {
        assertThat(first.get("cache_status")).isEqualTo("miss");
        assertThat(repeated.get("cache_status")).isEqualTo("hit");
      }
      new TransactionTemplate(tm)
          .execute(
              s -> {
                db.execute("UPDATE mentorships SET active=FALSE WHERE student_id=?", student);
                return null;
              });
      call("POST", "/api/v1/agent-runs", mentorToken, request, 403, "revoke");
      internal(
          "/runs/" + id + "/context", null, 403, num(claim, "attempt"), str(claim, "lease_token"));
      internal("/runs/" + id + "/search", map("query", "实验"), 403, attempt, lease);
    } finally {
      if (liveRedis) environment.getPropertySources().remove("qa-search-cache");
    }
  }

  @Test
  void changedTaskFactsRejectOldContextAndSearch() throws Exception {
    var task =
        call(
            "POST",
            "/api/v1/tasks",
            studentToken,
            map("student_id", student, "title", "任务事实"),
            201);
    String run =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "research_qa",
                    "subject",
                    map("student_id", student),
                    "input",
                    map("question", "当前任务")),
                202,
                "task-facts"),
            "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "qa"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    internal("/runs/" + run + "/context", null, 200, attempt, lease);
    call(
        "PATCH",
        "/api/v1/tasks/" + str(task, "id"),
        studentToken,
        map("expected_version", 1, "title", "修改后的任务事实"),
        200);
    var error = internal("/runs/" + run + "/context", null, 409, attempt, lease);
    assertThat(error.get("error_code")).isEqualTo("CONTEXT_STALE");
    internal("/runs/" + run + "/search", map("query", "任务"), 409, attempt, lease);
  }

  @Test
  void paperGuideSearchDoesNotMixAnotherAuthorizedPaper() throws Exception {
    var paperIds = new ArrayList<String>();
    var sourceIds = new ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      String paperId = uid(), sourceId = uid(), fileId = uid();
      paperIds.add(paperId);
      sourceIds.add(sourceId);
      db.insert(
          "files",
          map(
              "id",
              fileId,
              "lab_id",
              lab,
              "owner_id",
              student,
              "client_upload_id",
              uid(),
              "purpose",
              "paper",
              "object_key",
              "qa/" + uid(),
              "sha256",
              hash("fixture"),
              "size",
              1,
              "mime",
              "application/pdf",
              "upload_status",
              "uploaded",
              "scan_status",
              "clean"));
      db.insert(
          "papers",
          map(
              "id",
              paperId,
              "lab_id",
              lab,
              "owner_id",
              student,
              "title",
              "Guide title",
              "file_id",
              fileId,
              "visibility",
              "private",
              "ingest_status",
              "ready",
              "source_id",
              null,
              "version",
              1));
      db.insert(
          "sources",
          map(
              "id",
              sourceId,
              "lab_id",
              lab,
              "owner_id",
              student,
              "kind",
              "paper",
              "entity_id",
              paperId,
              "visibility",
              "private",
              "active_revision",
              1));
      db.update("papers", map("id", paperId, "source_id", sourceId));
      db.insert(
          "source_revisions",
          map(
              "source_id",
              sourceId,
              "revision",
              1,
              "content_hash",
              hash("Guide body"),
              "locator_scheme",
              "docx_paragraph",
              "text_snapshot",
              "Guide body"));
      for (int paragraph = 1; paragraph <= 3; paragraph++)
        db.insert(
            "chunks",
            map(
                "source_id",
                sourceId,
                "source_revision",
                1,
                "chunk_id",
                "paragraph:" + paragraph,
                "text",
                paragraph == 1 ? "Guide title" : "正文位置" + paragraph,
                "locator",
                map("kind", "docx_paragraph", "paragraph", paragraph)));
    }
    String run =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "paper_guide",
                    "subject",
                    map("student_id", student, "paper_id", paperIds.get(0)),
                    "input",
                    map()),
                202,
                "primary-paper"),
            "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "qa"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    var result =
        internal(
            "/runs/" + run + "/search", map("query", "Guide", "top_k", 6), 200, attempt, lease);
    assertThat(list(result.get("chunks"))).hasSize(3);
    assertThat(list(result.get("chunks")).stream().map(c -> str(obj(c), "source_id")))
        .allMatch(sourceIds.get(0)::equals);
    assertThat(result.get("coverage_complete")).isEqualTo(true);
    internal(
        "/runs/" + run + "/search",
        map("query", "Guide", "source_ids", List.of(sourceIds.get(1))),
        404,
        attempt,
        lease);
    internal("/runs/" + run + "/sources/" + sourceIds.get(1), null, 404, attempt, lease);
  }

  @Test
  void fileUploadDocxParsingAndDownload() throws Exception {
    byte[] docx = docx("科研实验结果 evidence");
    String clientUploadId = uid();
    var upload =
        new MockMultipartFile(
            "file",
            "paper.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docx);
    var result =
        mvc.perform(
                multipart("/api/v1/files")
                    .file(upload)
                    .param("purpose", "paper")
                    .param("lab_id", lab)
                    .param("client_upload_id", clientUploadId)
                    .header("Authorization", "Bearer " + studentToken)
                    .header("Idempotency-Key", "upload"))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(201);
    String fid =
        str(obj(obj(parse(result.getResponse().getContentAsString())).get("data")), "file_id");
    var duplicate =
        mvc.perform(
                multipart("/api/v1/files")
                    .file(upload)
                    .param("purpose", "paper")
                    .param("lab_id", lab)
                    .param("client_upload_id", clientUploadId)
                    .header("Authorization", "Bearer " + studentToken)
                    .header("Idempotency-Key", "upload-second-key"))
            .andReturn();
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(201);
    assertThat(
            str(
                obj(obj(parse(duplicate.getResponse().getContentAsString())).get("data")),
                "file_id"))
        .isEqualTo(fid);
    var paper =
        call(
            "POST",
            "/api/v1/papers",
            studentToken,
            map("file_id", fid, "title", "测试论文", "visibility", "lab"),
            202);
    String pid = str(paper, "paper_id");
    // Exercise the real consumer explicitly with scheduler disabled.
    workers.processFileOnce();
    var ready = call("GET", "/api/v1/papers/" + pid, studentToken, null, 200);
    assertThat(ready.get("ingest_status")).isEqualTo("ready");
    var source = call("GET", "/api/v1/sources/" + str(ready, "source_id"), studentToken, null, 200);
    assertThat(str(source, "excerpt")).contains("evidence");
    var download =
        mvc.perform(
                get("/api/v1/files/" + fid + "/download")
                    .header("Authorization", "Bearer " + studentToken))
            .andReturn();
    assertThat(download.getResponse().getStatus()).isEqualTo(200);
    assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(docx);
    var note =
        call(
            "POST",
            "/api/v1/notes",
            studentToken,
            map("paper_id", pid, "claims", List.of("结论", "这是给定条件估算"),
                "questions", List.of("实际电价待确认", "亮度档位待确认")),
            201);
    call(
        "POST",
        "/api/v1/notes/" + str(note, "id") + "/confirm",
        studentToken,
        map("expected_version", 1),
        200);
    call("GET", "/api/v1/notes/" + str(note, "id"), mentorToken, null, 200);
    var noteSource = db.one("sources", "WHERE kind='note' AND entity_id=?", note.get("id"));
    var noteRevision = db.one("source_revisions", "WHERE source_id=? AND revision=?", noteSource.get("id"), 1);
    var readableSnapshot = obj(parse(str(noteRevision, "text_snapshot")));
    assertThat(readableSnapshot.get("claims")).isEqualTo("结论\n这是给定条件估算");
    assertThat(readableSnapshot.get("questions")).isEqualTo("实际电价待确认\n亮度档位待确认");
    assertThat(readableSnapshot).doesNotContainKey("source_refs");
    var readableChunks = db.query("chunks", "WHERE source_id=? AND source_revision=?", noteSource.get("id"), 1);
    assertThat(readableChunks).extracting(c -> str(c, "text"))
        .contains("结论\n这是给定条件估算", "实际电价待确认\n亮度档位待确认");
  }

  @Test
  void emptyExperimentConfirmationIsAtomicAndVersioned() throws Exception {
    for (String value : List.of("", " \n\t ")) {
      var experiment =
          call(
              "POST",
              "/api/v1/experiments",
              studentToken,
              map("purpose", "确认校验", "results", value),
              201);
      String id = str(experiment, "id");
      int events = db.all("outbox_events").size();
      int sources = db.all("sources").size();
      int chunks = db.all("chunks").size();
      var rejected =
          call(
              "POST",
              "/api/v1/experiments/" + id + "/confirm",
              studentToken,
              map("expected_version", 1),
              422);
      assertThat(rejected.get("error_code")).isEqualTo("EXPERIMENT_RESULTS_REQUIRED");
      var unchanged = call("GET", "/api/v1/experiments/" + id, studentToken, null, 200);
      assertThat(unchanged.get("status")).isEqualTo("draft");
      assertThat(num(unchanged, "version")).isEqualTo(1);
      assertThat(num(unchanged, "current_revision")).isEqualTo(1);
      assertThat(db.all("outbox_events")).hasSize(events);
      assertThat(db.all("sources")).hasSize(sources);
      assertThat(db.all("chunks")).hasSize(chunks);
      call(
          "PATCH",
          "/api/v1/experiments/" + id,
          studentToken,
          map("expected_version", 1, "purpose", "确认校验", "results", "有效结果"),
          200);
      call(
          "POST",
          "/api/v1/experiments/" + id + "/confirm",
          studentToken,
          map("expected_version", 1),
          409);
      call(
          "POST",
          "/api/v1/experiments/" + id + "/confirm",
          studentToken,
          map("expected_version", 2),
          200);
      call(
          "POST",
          "/api/v1/experiments/" + id + "/confirm",
          studentToken,
          map("expected_version", 3),
          409);
    }
  }

  @Test
  void confirmedExperimentSourceUsesReadableParametersAndSteps() throws Exception {
    var record = call("POST", "/api/v1/experiments", studentToken,
        map("purpose", "核对台灯参数", "parameters", map("功率", "8 W", "来源", "导师给定"),
            "steps", List.of("换算千瓦", "核对费用"), "results", "理论费用差0.288元"), 201);
    var confirmed = call("POST", "/api/v1/experiments/" + str(record,"id") + "/confirm", studentToken, map("expected_version",1), 200, "readable-experiment");
    var source = db.one("source_revisions", "WHERE source_id=? AND revision=1", confirmed.get("source_id"));
    var fields = obj(parse(str(source,"text_snapshot")));
    assertThat(fields.get("parameters")).isInstanceOf(String.class);
    assertThat(fields.get("steps")).isEqualTo("换算千瓦\n核对费用");
    assertThat(fields).doesNotContainKey("file_ids");
    assertThat(confirmed.get("parameters")).isEqualTo(map("功率", "8 W", "来源", "导师给定"));
    assertThat(confirmed.get("steps")).isEqualTo(List.of("换算千瓦", "核对费用"));
    assertThat(confirmed.get("source_revision")).isEqualTo(1);
  }

  @Test
  void searchAndSourceUsePersistedLegacyJsonText() throws Exception {
    var experiment =
        call(
            "POST",
            "/api/v1/experiments",
            studentToken,
            map("purpose", "实验材料", "steps", List.of("实验中文", "换行\n步骤"), "results", "完成"),
            201);
    var confirmed =
        call(
            "POST",
            "/api/v1/experiments/" + str(experiment, "id") + "/confirm",
            studentToken,
            map("expected_version", 1),
            200);
    String source = str(confirmed, "source_id");
    var chunk =
        db.query("chunks", "WHERE source_id=?", source).stream()
            .filter(c -> eq(obj(c.get("locator")).get("field"), "steps"))
            .findFirst()
            .orElseThrow();
    String legacy = "[\"实验中文\", \"换行\\n步骤\"]";
    db.update("chunks", map("id", chunk.get("id"), "text", legacy));
    String run =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "research_qa",
                    "subject",
                    map("student_id", student),
                    "input",
                    map("question", "实验")),
                202,
                "legacy-text"),
            "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "qa-test"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    internal("/runs/" + run + "/context", null, 200, attempt, lease);
    var search =
        internal("/runs/" + run + "/search", map("query", "实验", "top_k", 10), 200, attempt, lease);
    var hit =
        list(search.get("chunks")).stream()
            .map(Data::obj)
            .filter(
                c ->
                    eq(c.get("source_id"), source)
                        && eq(obj(c.get("locator")).get("field"), "steps"))
            .findFirst()
            .orElseThrow();
    String query =
        "?revision=1&locator="
            + java.net.URLEncoder.encode(
                json(hit.get("locator")), java.nio.charset.StandardCharsets.UTF_8)
            + "&chunk_id="
            + java.net.URLEncoder.encode(
                str(hit, "chunk_id"), java.nio.charset.StandardCharsets.UTF_8);
    var detail = internal("/runs/" + run + "/sources/" + source + query, null, 200, attempt, lease);
    assertThat(detail.get("excerpt")).isEqualTo(hit.get("text")).isEqualTo(legacy);
    new TransactionTemplate(tm)
        .execute(
            s -> {
              access.evidence(
                  student,
                  lab,
                  List.of(
                      map(
                          "source_id",
                          source,
                          "source_revision",
                          1,
                          "locator",
                          hit.get("locator"),
                          "quote",
                          legacy)));
              return null;
            });
    var badQuery =
        query.replace(
            java.net.URLEncoder.encode(
                str(hit, "chunk_id"), java.nio.charset.StandardCharsets.UTF_8),
            "missing-chunk");
    internal("/runs/" + run + "/sources/" + source + badQuery, null, 404, attempt, lease);
    call(
        "PATCH",
        "/api/v1/experiments/" + str(experiment, "id"),
        studentToken,
        map("expected_version", 2, "purpose", "更新实验", "results", "新事实"),
        200);
    internal("/runs/" + run + "/context", null, 409, attempt, lease);
  }

  @Test
  void notificationItemsPaginationAndOwnership() throws Exception {
    long unreadBefore =
        db.query("notifications", "WHERE recipient_id=?", student).stream()
            .filter(n -> n.get("read_at") == null)
            .count();
    db.insert(
        "notifications",
        map(
            "recipient_id",
            student,
            "business_key",
            uid(),
            "type",
            "qa",
            "target",
            map("id", uid())));
    db.insert(
        "notifications",
        map(
            "recipient_id",
            student,
            "business_key",
            uid(),
            "type",
            "qa",
            "target",
            map("id", uid())));
    var first = call("GET", "/api/v1/notifications?limit=1", studentToken, null, 200);
    assertThat(first.get("items")).isEqualTo(first.get("notifications"));
    assertThat(list(first.get("items"))).hasSize(1);
    String id = str(obj(list(first.get("items")).get(0)), "id");
    var second =
        call(
            "GET",
            "/api/v1/notifications?limit=1&cursor=" + str(first, "next_cursor"),
            studentToken,
            null,
            200);
    assertThat(list(second.get("items"))).hasSize(1);
    assertThat(str(obj(list(second.get("items")).get(0)), "id")).isNotEqualTo(id);
    call("POST", "/api/v1/notifications/" + id + "/read", otherToken, map(), 404);
    call("POST", "/api/v1/notifications/" + id + "/read", studentToken, map(), 200);
    var unread = call("GET", "/api/v1/notifications?unread_only=true", studentToken, null, 200);
    assertThat(list(unread.get("items"))).hasSize((int) unreadBefore + 1);
    assertThat(list(call("GET", "/api/v1/notifications", otherToken, null, 200).get("items")))
        .isEmpty();
  }

  @Test
  void overdueTaskCreatesReadableDeduplicatedNotificationsAndClosesAfterCompletion() throws Exception {
    var task = call("POST", "/api/v1/tasks", studentToken,
        map("student_id", student, "title", "核实宿舍台灯电价来源",
            "due_at", Instant.now().minusSeconds(60).toString()), 201);
    String tid = str(task, "id");
    environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
        "qa-notification-worker", map("yanji.workers-enabled", true)));
    try {
      workers.heartbeat();
      workers.heartbeat();
      var studentRows = list(call("GET", "/api/v1/notifications", studentToken, null, 200).get("items"));
      assertThat(studentRows).hasSize(1);
      var notification = obj(studentRows.get(0));
      assertThat(notification.get("type")).isEqualTo("task_due");
      assertThat(obj(notification.get("target")).get("title")).isEqualTo("核实宿舍台灯电价来源");
      var mentorRows = list(call("GET", "/api/v1/notifications", mentorToken, null, 200).get("items"));
      assertThat(mentorRows).hasSize(1);
      String nid = str(notification, "id");
      call("POST", "/api/v1/notifications/" + nid + "/read", otherToken, map(), 404);
      var read = call("POST", "/api/v1/notifications/" + nid + "/read", studentToken, map(), 200);
      assertThat(read.get("read_at")).isNotNull();
      assertThat(call("POST", "/api/v1/notifications/" + nid + "/read", studentToken, map(), 200).get("read_at"))
          .isEqualTo(read.get("read_at"));
      call("POST", "/api/v1/tasks/" + tid + "/transition", studentToken,
          map("to_status", "in_progress", "expected_version", 1), 200, "notice-task-start");
      call("POST", "/api/v1/tasks/" + tid + "/transition", studentToken,
          map("to_status", "completed", "expected_version", 2), 200, "notice-task-complete");
      workers.heartbeat();
      assertThat(obj(list(call("GET", "/api/v1/notifications", studentToken, null, 200).get("items")).get(0)).get("closed_at"))
          .isNotNull();
      new TransactionTemplate(tm).execute(s -> {
        db.execute("UPDATE mentorships SET active=FALSE WHERE student_id=? AND mentor_id=?", student, mentor);
        return null;
      });
      assertThat(list(call("GET", "/api/v1/notifications", mentorToken, null, 200).get("items"))).isEmpty();
      call("POST", "/api/v1/notifications/" + str(obj(mentorRows.get(0)), "id") + "/read", mentorToken, map(), 404);
    } finally {
      environment.getPropertySources().remove("qa-notification-worker");
    }
  }

  @Test
  void restoredDeadlineAndMentorshipReactivateOneUnreadReminder() throws Exception {
    String due = Instant.now().minusSeconds(60).toString();
    var task = call("POST", "/api/v1/tasks", studentToken,
        map("student_id", student, "title", "核对台灯参数", "due_at", due), 201);
    String tid = str(task, "id");
    environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
        "qa-notification-worker", map("yanji.workers-enabled", true)));
    try {
      workers.heartbeat();
      var rows = list(call("GET", "/api/v1/notifications", studentToken, null, 200).get("items"));
      String nid = str(obj(rows.get(0)), "id");
      call("POST", "/api/v1/notifications/" + nid + "/read", studentToken, map(), 200);
      call("PATCH", "/api/v1/tasks/" + tid, studentToken, map("expected_version", 1, "due_at", null), 200);
      workers.heartbeat();
      assertThat(db.get("notifications", nid).get("closed_at")).isNotNull();
      call("PATCH", "/api/v1/tasks/" + tid, studentToken, map("expected_version", 2, "due_at", due), 200);
      workers.heartbeat(); workers.heartbeat();
      var reopened = list(call("GET", "/api/v1/notifications", studentToken, null, 200).get("items"));
      assertThat(reopened).hasSize(1);
      assertThat(obj(reopened.get(0)).get("closed_at")).isNull();
      assertThat(obj(reopened.get(0)).get("read_at")).isNull();
      String mentorNotice = str(obj(list(call("GET", "/api/v1/notifications", mentorToken, null, 200).get("items")).get(0)), "id");
      call("POST", "/api/v1/notifications/" + mentorNotice + "/read", mentorToken, map(), 200);
      new TransactionTemplate(tm).execute(tx -> { db.execute("UPDATE mentorships SET active=FALSE WHERE student_id=? AND mentor_id=?", student, mentor); return null; });
      workers.heartbeat();
      assertThat(db.get("notifications", mentorNotice).get("closed_at")).isNotNull();
      new TransactionTemplate(tm).execute(tx -> { db.execute("UPDATE mentorships SET active=TRUE WHERE student_id=? AND mentor_id=?", student, mentor); return null; });
      workers.heartbeat(); workers.heartbeat();
      var mentorRows = list(call("GET", "/api/v1/notifications", mentorToken, null, 200).get("items"));
      assertThat(mentorRows).hasSize(1);
      assertThat(obj(mentorRows.get(0)).get("closed_at")).isNull();
      assertThat(obj(mentorRows.get(0)).get("read_at")).isNull();
    } finally { environment.getPropertySources().remove("qa-notification-worker"); }
  }

  @Test
  void storageFailureRollsBackAndSameUploadCanRetry() throws Exception {
    var failedStorage = org.mockito.Mockito.mock(cn.yanji.service.StorageService.class);
    org.mockito.Mockito.doThrow(new AppError(503, "STORAGE_UNAVAILABLE", "存储离线"))
        .when(failedStorage)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    var failureService = new cn.yanji.service.FileSourceService(db, access, failedStorage);
    var upload =
        new MockMultipartFile(
            "file", "qa.pdf", "application/pdf", "%PDF-1.4\nQA\n%%EOF".getBytes());
    var form = map("purpose", "paper", "lab_id", lab, "client_upload_id", uid());
    int files = db.all("files").size();
    int keys = db.all("idempotency_records").size();
    var tx = new TransactionTemplate(tm);
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      try {
                        return failureService.upload(student, upload, form, "storage-retry");
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    }))
        .isInstanceOf(AppError.class);
    assertThat(db.all("files")).hasSize(files);
    assertThat(db.all("idempotency_records")).hasSize(keys);
    var retry =
        tx.execute(
            s -> {
              try {
                return fileSources.upload(student, upload, form, "storage-retry");
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    assertThat(db.all("files")).hasSize(files + 1);
    var replay =
        tx.execute(
            s -> {
              try {
                return fileSources.upload(student, upload, form, "storage-retry");
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    assertThat(replay).isEqualTo(retry);
    var changed =
        new MockMultipartFile(
            "file", "qa.pdf", "application/pdf", "%PDF-1.4\nDIFFERENT\n%%EOF".getBytes());
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      try {
                        return fileSources.upload(student, changed, form, "different-key");
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    }))
        .isInstanceOf(AppError.class)
        .satisfies(e -> assertThat(((AppError) e).code).isEqualTo("IDEMPOTENCY_CONFLICT"));
    var large =
        new MockMultipartFile(
            "file", "qa.pdf", "application/pdf", new byte[] {37, 80, 68, 70, 45}) {
          @Override
          public long getSize() {
            return 20L * 1024 * 1024 + 1;
          }
        };
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      try {
                        return fileSources.upload(student, large, form, "large-file");
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    }))
        .isInstanceOf(AppError.class)
        .satisfies(e -> assertThat(((AppError) e).status).isEqualTo(413));
  }

  @Test
  void validPdfUploadsParsesAndSourceLocatorIsExact() throws Exception {
    byte[] bytes;
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument();
        var output = new ByteArrayOutputStream()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      pdf.addPage(page);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(
            new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
            12);
        content.newLineAtOffset(50, 700);
        content.showText("QA experiment evidence");
        content.endText();
      }
      pdf.save(output);
      bytes = output.toByteArray();
    }
    var response =
        mvc.perform(
                multipart("/api/v1/files")
                    .file(new MockMultipartFile("file", "qa.pdf", "application/pdf", bytes))
                    .param("purpose", "paper")
                    .param("lab_id", lab)
                    .param("client_upload_id", uid())
                    .header("Authorization", "Bearer " + studentToken)
                    .header("Idempotency-Key", "pdf-file"))
            .andReturn();
    assertThat(response.getResponse().getStatus()).isEqualTo(201);
    String file =
        str(obj(obj(parse(response.getResponse().getContentAsString())).get("data")), "file_id");
    var paper =
        call(
            "POST",
            "/api/v1/papers",
            studentToken,
            map("file_id", file, "title", "QA PDF", "visibility", "lab"),
            202);
    workers.processFileOnce();
    var ready = call("GET", "/api/v1/papers/" + str(paper, "paper_id"), studentToken, null, 200);
    assertThat(ready.get("ingest_status")).isEqualTo("ready");
    var chunk = db.query("chunks", "WHERE source_id=?", ready.get("source_id")).get(0);
    var detail =
        fileSources.sourceDetail(
            student,
            str(ready, "source_id"),
            1,
            obj(chunk.get("locator")),
            true,
            str(chunk, "chunk_id"));
    assertThat(detail.get("excerpt")).isEqualTo(chunk.get("text"));
  }

  @Test
  void recoveryRestoresLostOutboxWithoutDuplicatingIt() throws Exception {
    String id =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "research_qa",
                    "subject",
                    map("student_id", student),
                    "input",
                    map("question", "test")),
                202,
                "recovery"),
            "run_id");
    new TransactionTemplate(tm)
        .execute(
            s -> {
              for (var e :
                  db.query(
                      "outbox_events",
                      "WHERE event_type='agent_run.requested' AND published_at IS NULL")) {
                if (eq(obj(e.get("payload")).get("run_id"), id)) {
                  e.put("published_at", now());
                  db.update("outbox_events", e);
                }
              }
              return null;
            });
    workers.recoverOnce();
    workers.recoverOnce();
    assertThat(
            db
                .query(
                    "outbox_events",
                    "WHERE event_type='agent_run.requested' AND published_at IS NULL")
                .stream()
                .filter(e -> eq(obj(e.get("payload")).get("run_id"), id))
                .count())
        .isEqualTo(1);
  }

  @Test
  void maliciousDocxExternalEntitiesAreRejected() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(
          "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///nonexistent'>]><foo>&xxe;</foo>".getBytes());
      zip.closeEntry();
    }
    assertThatThrownBy(
            () ->
                workers.extract(
                    out.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .isInstanceOf(org.xml.sax.SAXParseException.class);
  }

  Map<String, Object> readyQaPaper(String visibility, String text) throws Exception {
    var upload =
        mvc.perform(
                multipart("/api/v1/files")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "privacy.docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            docx(text)))
                    .param("purpose", "paper")
                    .param("lab_id", lab)
                    .param("client_upload_id", uid())
                    .header("Authorization", "Bearer " + studentToken)
                    .header("Idempotency-Key", uid()))
            .andReturn();
    assertThat(upload.getResponse().getStatus()).isEqualTo(201);
    String file =
        str(obj(obj(parse(upload.getResponse().getContentAsString())).get("data")), "file_id");
    var created =
        call(
            "POST",
            "/api/v1/papers",
            studentToken,
            map("file_id", file, "title", "权限回归文献", "visibility", visibility),
            202);
    workers.processFileOnce();
    var paper = call("GET", "/api/v1/papers/" + str(created, "paper_id"), studentToken, null, 200);
    assertThat(paper.get("ingest_status")).isEqualTo("ready");
    return paper;
  }

  @Test
  void privateGuideResultsProposalsEventsAndConfirmedNotesStayPrivate() throws Exception {
    String secret = "私有文献中的实验细节禁止自动分享";
    var paper = readyQaPaper("private", secret);
    String source = str(paper, "source_id");
    var chunk = db.query("chunks", "WHERE source_id=?", source).get(0);
    var ref =
        map(
            "source_id",
            source,
            "source_revision",
            1,
            "locator",
            chunk.get("locator"),
            "quote",
            secret);
    String run =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "paper_guide",
                    "subject",
                    map("student_id", student, "paper_id", paper.get("id")),
                    "input",
                    map()),
                202,
                "private-guide"),
            "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "privacy-qa"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    internal("/runs/" + run + "/context", null, 200, attempt, lease);
    var draft =
        map(
            "action_type",
            "reading_note.draft",
            "target_student_id",
            student,
            "payload",
            map(
                "paper_id",
                paper.get("id"),
                "claims",
                List.of(secret),
                "source_refs",
                List.of(ref)),
            "source_refs",
            List.of(ref),
            "reason",
            secret,
            "base_versions",
            List.of(map("type", "paper", "id", paper.get("id"), "version", 1)),
            "expires_at",
            Instant.now().plusSeconds(3600).toString());
    var finalized =
        internal(
            "/runs/" + run + "/finalize",
            map(
                "attempt",
                attempt,
                "result",
                map(
                    "outcome",
                    "ok",
                    "summary",
                    secret,
                    "claims",
                    List.of(map("text", secret, "source_refs", List.of(ref)))),
                "proposal_drafts",
                List.of(draft)),
            200,
            0,
            lease);
    String proposal = list(finalized.get("proposal_ids")).get(0).toString();
    call("GET", "/api/v1/agent-runs/" + run, studentToken, null, 200);
    call("GET", "/api/v1/proposals/" + proposal, studentToken, null, 200);
    for (String denied :
        List.of(
            "/agent-runs/" + run,
            "/agent-runs/" + run + "/events",
            "/proposals/" + proposal,
            "/proposals?run_id=" + run,
            "/papers/" + paper.get("id"),
            "/files/" + paper.get("file_id"),
            "/sources/" + source)) call("GET", "/api/v1" + denied, mentorToken, null, 404);
    call("GET", "/api/v1/agent-runs/" + run, otherToken, null, 404);
    var runs = call("GET", "/api/v1/agent-runs?student_id=" + student, mentorToken, null, 200);
    assertThat(list(runs.get("runs"))).isEmpty();
    assertThat(json(call("GET", "/api/v1/proposals?student_id=" + student, mentorToken, null, 200)))
        .doesNotContain(secret, proposal);
    var receipt =
        call(
            "POST",
            "/api/v1/proposals/" + proposal + "/approve",
            studentToken,
            map("expected_version", 1),
            200,
            "apply-private-note");
    String note = list(obj(receipt.get("execution_receipt")).get("resource_ids")).get(0).toString();
    call("GET", "/api/v1/notes/" + note, studentToken, null, 200);
    call("GET", "/api/v1/notes/" + note, mentorToken, null, 404);
    var confirmed =
        call(
            "POST",
            "/api/v1/notes/" + note + "/confirm",
            studentToken,
            map("expected_version", 1),
            200,
            "confirm-private-note");
    assertThat(
            call(
                "POST",
                "/api/v1/notes/" + note + "/confirm",
                studentToken,
                map("expected_version", 1),
                200,
                "confirm-private-note"))
        .isEqualTo(confirmed);
    call("GET", "/api/v1/notes/" + note, mentorToken, null, 404);
    call("GET", "/api/v1/sources/" + str(confirmed, "source_id"), mentorToken, null, 404);
    new TransactionTemplate(tm)
        .execute(
            s -> {
              db.execute(
                  "UPDATE memberships SET active=FALSE WHERE user_id=? AND lab_id=?", student, lab);
              return null;
            });
    call("GET", "/api/v1/agent-runs/" + run, studentToken, null, 404);
    call(
        "POST",
        "/api/v1/proposals/" + proposal + "/approve",
        studentToken,
        map("expected_version", 1),
        404,
        "apply-private-note");
  }

  @Test
  void mentorOwnedSharedAnalysisWorksUntilSourceBecomesPrivateEvenWithoutResultRefs()
      throws Exception {
    var paper = readyQaPaper("lab", "共享证据");
    var task =
        call(
            "POST", "/api/v1/tasks", mentorToken, map("student_id", student, "title", "导师指派"), 201);
    var request =
        map(
            "kind",
            "risk_brief",
            "subject",
            map("student_id", student),
            "input",
            map("horizon_days", 7));
    String run =
        str(call("POST", "/api/v1/agent-runs", mentorToken, request, 202, "shared-risk"), "run_id");
    var claim = internal("/runs/" + run + "/claim", map("worker_id", "qa"), 200, 0, null);
    int attempt = num(claim, "attempt");
    String lease = str(claim, "lease_token");
    internal("/runs/" + run + "/context", null, 200, attempt, lease);
    var draft =
        map(
            "action_type",
            "task.reschedule_batch",
            "target_student_id",
            student,
            "payload",
            map(
                "items",
                List.of(
                    map(
                        "task_id",
                        task.get("id"),
                        "new_due_at",
                        Instant.now().plusSeconds(86400).toString(),
                        "reason",
                        "调整计划"))),
            "reason",
            "仅请求者可见的分析",
            "base_versions",
            List.of(map("type", "task", "id", task.get("id"), "version", 1)),
            "expires_at",
            Instant.now().plusSeconds(3600).toString());
    var terminal =
        map(
            "attempt",
            attempt,
            "result",
            map("outcome", "ok", "summary", "无引用摘要"),
            "proposal_drafts",
            List.of(draft));
    var done = internal("/runs/" + run + "/finalize", terminal, 200, 0, lease);
    String proposal = list(done.get("proposal_ids")).get(0).toString();
    call("GET", "/api/v1/agent-runs/" + run, mentorToken, null, 200);
    var privatePaper = readyQaPaper("private", "不可通过第二条嵌套引用泄露");
    var originalPayload = db.get("proposals", proposal).get("payload");
    db.update(
        "proposals",
        map(
            "id",
            proposal,
            "payload",
            map(
                "legacy_nested_content",
                List.of(
                    map(
                        "source_refs",
                        List.of(
                            map("source_id", paper.get("source_id")),
                            map("source_id", privatePaper.get("source_id"))))))));
    call("GET", "/api/v1/agent-runs/" + run, mentorToken, null, 404);
    call("GET", "/api/v1/proposals/" + proposal, mentorToken, null, 404);
    db.update("proposals", map("id", proposal, "payload", originalPayload));
    var approved =
        call(
            "POST",
            "/api/v1/proposals/" + proposal + "/approve",
            mentorToken,
            map("expected_version", 1),
            200,
            "mentor-reschedule");
    assertThat(obj(approved.get("execution_receipt")).get("status")).isEqualTo("rescheduled");
    assertThat(db.get("tasks", str(task, "id")).get("created_by")).isEqualTo(mentor);
    db.update("papers", map("id", paper.get("id"), "visibility", "private"));
    call("GET", "/api/v1/agent-runs/" + run, mentorToken, null, 404);
    call("GET", "/api/v1/proposals/" + proposal, mentorToken, null, 404);
    call(
        "POST",
        "/api/v1/proposals/" + proposal + "/approve",
        mentorToken,
        map("expected_version", 1),
        404,
        "mentor-reschedule");
    call("POST", "/api/v1/agent-runs", mentorToken, request, 404, "shared-risk");
    internal("/runs/" + run + "/finalize", terminal, 403, 0, lease);
  }

  @Test
  void mentorTaskWithoutStageCannotBeCompletedByStudentAndRejectedWriteIsAtomic() throws Exception {
    var task =
        call(
            "POST",
            "/api/v1/tasks",
            mentorToken,
            map("student_id", student, "title", "未关联阶段的指派任务"),
            201);
    String id = str(task, "id");
    assertThat(task.get("created_by")).isEqualTo(mentor);
    assertThat(task.get("can_complete_personal")).isEqualTo(false);
    assertThat(call("GET", "/api/v1/tasks/" + id, studentToken, null, 200).get("created_by"))
        .isEqualTo(mentor);
    assertThat(
            obj(list(call("GET", "/api/v1/tasks", studentToken, null, 200).get("tasks")).get(0))
                .get("created_by"))
        .isEqualTo(mentor);
    for (String to : List.of("in_progress", "submitted")) {
      int version = to.equals("in_progress") ? 1 : 2;
      call(
          "POST",
          "/api/v1/tasks/" + id + "/transition",
          studentToken,
          map("expected_version", version, "to_status", to),
          200,
          "assigned-" + to);
    }
    int events = db.query("task_events", "WHERE task_id=?", id).size();
    int outbox = db.all("outbox_events").size();
    var complete = map("expected_version", 3, "to_status", "completed");
    call(
        "POST",
        "/api/v1/tasks/" + id + "/transition",
        studentToken,
        complete,
        403,
        "bypass-complete");
    assertThat(db.get("tasks", id).get("status")).isEqualTo("submitted");
    assertThat(num(db.get("tasks", id), "version")).isEqualTo(3);
    assertThat(db.query("task_events", "WHERE task_id=?", id)).hasSize(events);
    assertThat(db.all("outbox_events")).hasSize(outbox);
    // An old receipt from the buggy implementation must not bypass the new permission check.
    db.insert(
        "idempotency_records",
        map(
            "actor_id",
            student,
            "method",
            "POST",
            "path",
            "/api/v1/tasks/" + id + "/transition",
            "key",
            "old-buggy-receipt",
            "body_hash",
            hash(json(complete)),
            "response",
            map("status", "completed"),
            "expires_at",
            Instant.now().plusSeconds(3600).toString()));
    call(
        "POST",
        "/api/v1/tasks/" + id + "/transition",
        studentToken,
        complete,
        403,
        "old-buggy-receipt");
    call(
        "POST", "/api/v1/tasks/" + id + "/transition", mentorToken, complete, 200, "mentor-accept");
    call(
        "POST",
        "/api/v1/tasks",
        studentToken,
        map("student_id", student, "title", "伪造创建者", "created_by", student),
        422);
    var personal =
        call(
            "POST",
            "/api/v1/tasks",
            studentToken,
            map("student_id", student, "title", "真正个人任务"),
            201);
    assertThat(personal.get("created_by")).isEqualTo(student);
    assertThat(personal.get("can_complete_personal")).isEqualTo(true);
    call(
        "POST",
        "/api/v1/tasks/" + personal.get("id") + "/transition",
        studentToken,
        map("expected_version", 1, "to_status", "completed"),
        200,
        "personal-complete");
    var legacy =
        call(
            "POST",
            "/api/v1/tasks",
            studentToken,
            map("student_id", student, "title", "无法核实创建来源的旧任务"),
            201);
    db.execute("DELETE FROM task_events WHERE task_id=?", legacy.get("id"));
    assertThat(
            call("GET", "/api/v1/tasks/" + legacy.get("id"), studentToken, null, 200)
                .get("created_by"))
        .isNull();
    call(
        "POST",
        "/api/v1/tasks/" + legacy.get("id") + "/transition",
        studentToken,
        map("expected_version", 1, "to_status", "completed"),
        403,
        "unknown-creator");
  }

  @Test
  void experimentConfirmationReplaysAtomicallyAndReauthorizes() throws Exception {
    for (String visibility : List.of("private", "mentors")) {
      var record =
          call(
              "POST",
              "/api/v1/experiments",
              studentToken,
              map("purpose", "幂等实验", "results", "有效结果", "visibility", visibility),
              201);
      String id = str(record, "id"), path = "/api/v1/experiments/" + id + "/confirm";
      var body = map("expected_version", 1);
      var confirmed = call("POST", path, studentToken, body, 200, "confirm-" + visibility);
      int sources = db.all("sources").size(), revisions = db.all("source_revisions").size();
      int outbox = db.all("outbox_events").size();
      assertThat(call("POST", path, studentToken, body, 200, "confirm-" + visibility))
          .isEqualTo(confirmed);
      assertThat(db.all("sources")).hasSize(sources);
      assertThat(db.all("source_revisions")).hasSize(revisions);
      assertThat(db.all("outbox_events")).hasSize(outbox);
      assertThat(num(db.get("experiments", id), "version")).isEqualTo(2);
      var conflict =
          call(
              "POST", path, studentToken, map("expected_version", 2), 409, "confirm-" + visibility);
      assertThat(conflict.get("error_code")).isEqualTo("IDEMPOTENCY_CONFLICT");
      call("POST", path, mentorToken, body, 404, "confirm-" + visibility);
    }
    var record = db.query("experiments", "WHERE owner_id=?", student).get(0);
    new TransactionTemplate(tm)
        .execute(
            s -> {
              db.execute(
                  "UPDATE memberships SET active=FALSE WHERE user_id=? AND lab_id=?", student, lab);
              return null;
            });
    call(
        "POST",
        "/api/v1/experiments/" + record.get("id") + "/confirm",
        studentToken,
        map("expected_version", 1),
        404,
        "confirm-" + record.get("visibility"));
  }

  @Test
  void submittingReportPublishesOnlyItsFrozenRevisionNotRawAnalysis() throws Exception {
    var report =
        call("POST", "/api/v1/weekly-reports", studentToken, map("week_start", "2026-10-05"), 201);
    String id = str(report, "id");
    call(
        "PATCH",
        "/api/v1/weekly-reports/" + id,
        studentToken,
        map(
            "expected_version",
            1,
            "done_items",
            List.of(map("id", uid(), "text", "明确提交的内容", "origin", "human")),
            "issues",
            List.of(),
            "next_plan",
            List.of(),
            "help_needed",
            List.of()),
        200);
    String run =
        str(
            call(
                "POST",
                "/api/v1/agent-runs",
                studentToken,
                map(
                    "kind",
                    "weekly_report",
                    "subject",
                    map("student_id", student),
                    "input",
                    map("week_start", "2026-10-05")),
                202,
                "unpublished-analysis"),
            "run_id");
    call(
        "POST",
        "/api/v1/weekly-reports/" + id + "/submit",
        studentToken,
        map("expected_version", 2),
        200,
        "publish-frozen");
    call("GET", "/api/v1/weekly-reports/" + id + "?revision=1", mentorToken, null, 404);
    call("GET", "/api/v1/weekly-reports/" + id + "?revision=2", mentorToken, null, 200);
    call("GET", "/api/v1/agent-runs/" + run, mentorToken, null, 404);
  }

  static byte[] docx(String text) throws Exception {
    var out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes());
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(
          ("<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>"
                  + text
                  + "</w:t></w:r></w:p></w:body></w:document>")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  @Test
  void mentorCanPublishGuidanceButStudentsCannotWriteIt() throws Exception {
    var input =
        map(
            "lab_id",
            lab,
            "title",
            "开题前检查",
            "phase",
            "开题准备",
            "content",
            "先写清研究问题，再比较相关文献，列出需要验证的方法。",
            "requirements",
            List.of("研究问题", "文献比较", "实验方案"));
    call("POST", "/api/v1/guidance", studentToken, input, 403);
    var saved = call("POST", "/api/v1/guidance", mentorToken, input, 201);
    assertThat(saved.get("title")).isEqualTo("开题前检查");
    var read = call("GET", "/api/v1/guidance", mentorToken, null, 200);
    assertThat(list(read.get("items"))).anyMatch(v -> eq(obj(v).get("id"), saved.get("id")));
    assertThat(db.query("sources", "WHERE entity_id=?", saved.get("id"))).hasSize(1);
    var stage =
        call(
            "POST",
            "/api/v1/stages",
            mentorToken,
            map("student_id", student, "title", "开题准备", "goal", "整理研究问题和实验方案"),
            201);
    var inputApply = map("stage_id", stage.get("id"), "expected_version", stage.get("version"));
    call("POST", "/api/v1/guidance/" + saved.get("id") + "/apply", studentToken, inputApply, 403);
    var applied =
        call(
            "POST", "/api/v1/guidance/" + saved.get("id") + "/apply", mentorToken, inputApply, 200);
    assertThat(applied.get("rule_set_id")).isEqualTo(saved.get("id"));
    call("POST", "/api/v1/guidance/" + saved.get("id") + "/apply", mentorToken, inputApply, 409);
  }

  @Test
  void guidanceSaveReplaysOneRecordAndRejectsChangedBody() throws Exception {
    var input =
        map(
            "title",
            "数据划分要求",
            "phase",
            "实验研究",
            "content",
            "比较方法时使用相同的数据划分。",
            "requirements",
            List.of("保存划分清单"));
    var first = call("POST", "/api/v1/guidance", mentorToken, input, 201, "guidance-save");
    var second = call("POST", "/api/v1/guidance", mentorToken, input, 201, "guidance-save");
    assertThat(second).isEqualTo(first);
    assertThat(db.query("sources", "WHERE entity_id=?", first.get("id"))).hasSize(1);
    input.put("content", "修改后的要求");
    call("POST", "/api/v1/guidance", mentorToken, input, 409, "guidance-save");
  }

  @Test
  void overdueRiskContainsTaskTitleInsteadOfRequiringClientIdLookup() throws Exception {
    var task =
        call(
            "POST",
            "/api/v1/tasks",
            mentorToken,
            map("student_id", student, "title", "检查数据划分", "due_at", "2020-01-01T12:00:00+08:00"),
            201);
    var dashboard = call("GET", "/api/v1/dashboard", studentToken, null, 200);
    var risks = list(dashboard.get("risks"));
    assertThat(risks).filteredOn(r -> eq(obj(r).get("task_id"), task.get("id"))).hasSize(1);
    assertThat(obj(risks.get(0)).get("task_title")).isEqualTo("检查数据划分");
  }

  @Test
  void guidanceReplayRechecksOriginalLabAfterMembershipMove() throws Exception {
    var input =
        map("title", "实验要求", "phase", "实验研究", "content", "保存原始输出", "requirements", List.of());
    call("POST", "/api/v1/guidance", mentorToken, input, 201, "lab-move");
    db.execute("UPDATE memberships SET active=FALSE WHERE user_id=?", mentor);
    var newLab = db.insert("labs", map("name", "新的实验室"));
    db.insert("memberships", map("user_id", mentor, "lab_id", newLab.get("id"), "role", "mentor"));
    call("POST", "/api/v1/guidance", mentorToken, input, 403, "lab-move");
  }
}
