package cn.yanji.service;

import static cn.yanji.core.Data.*;
import cn.yanji.mapper.Repository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;

/** Rebuilds eligible memories from live origins before retrieval; stored text alone never grants access. */
@org.springframework.stereotype.Service
public class ResearchExperienceService {
  private final Repository db;
  private final Access access;
  private final Environment env;
  public ResearchExperienceService(Repository db,Access access,Environment env) {
    this.db=db;this.access=access;this.env=env;
  }
  public void enrich(Map<String,Object> run,Map<String,Object> context) {
    boolean learning=env.getProperty("yanji.feedback-learning-enabled",Boolean.class,true);
    boolean constrained=env.getProperty("yanji.constrained-reasoning-enabled",Boolean.class,true);
    var policy=map("feedback_learning_enabled",learning,"constrained_reasoning_enabled",constrained,
        "algorithm_version","experience-v2","memory_is_evidence",false,"updates_model_weights",false);
    policy.put("structured_planning_enabled",constrained);
    policy.put("grounded_guidance_enabled",constrained);
    policy.put("semantic_feedback_enabled",learning);
    policy.put("algorithm_version","experience-v3");
    context.put("learning_policy",policy);
    if(constrained) {
      var completed=new ArrayList<Object>();var blocked=new ArrayList<Object>();
      var taskRows=list(context.get("tasks"));
      for(Object raw:taskRows) {
        var task=obj(raw);
        if(eq(task.get("status"),"completed"))completed.add(task.get("id"));
        var unresolved=list(task.get("dependency_ids")).stream().filter(id->taskRows.stream()
            .noneMatch(t->eq(obj(t).get("id"),id)&&eq(obj(t).get("status"),"completed"))).toList();
        if(!unresolved.isEmpty())blocked.add(map("task_id",task.get("id"),"unresolved_dependencies",unresolved));
      }
      context.put("reasoning_constraints",map("priority",List.of("current_stage_rules","current_mentor_constraints","confirmed_facts","historical_experiences"),
          "completed_task_ids",completed,"blocked_tasks",blocked,"max_memory_items",5,
          "require_confirmation",true,"experiences_are_soft_preferences",true));
    }
    context.put("feedback_memories",List.of());
    if(!learning)return;
    String actor=str(run,"requested_by"),student=str(run,"student_id"),lab=str(run,"lab_id");
    if(!access.canStudent(actor,student,lab,false))return;
    db.lock("users",student);
    var candidates=new ArrayList<Map<String,Object>>();
    // Only sources already in this authorized snapshot are eligible, including historical feedback.
    for(Object raw:list(context.get("source_versions"))) {
      var ref=obj(raw);var source=db.get("sources",str(ref,"source_id"));
      if(source==null || !access.source(actor,source) || !eq(source.get("active_revision"),ref.get("revision")))continue;
      if(eq(source.get("kind"),"thesis_feedback")) {
        var feedback=db.get("thesis_feedback",str(source,"entity_id"));
        if(feedback==null || !bound(student,str(feedback,"mentor_id"),lab))continue;
        var revision=db.get("thesis_revisions",str(feedback,"revision_id"));
        var thesis=revision==null?null:db.get("theses",str(revision,"thesis_id"));
        if(thesis==null || !eq(thesis.get("student_id"),student)||!eq(thesis.get("lab_id"),lab))continue;
        learn(run,candidates,"mentor_feedback",str(feedback,"id"),str(feedback,"content"),str(thesis,"title"),
            List.of(ref),str(feedback,"created_at"),0.9,map("decision",feedback.get("decision"),
                "current_requirement",list(context.get("mentor_constraints")).stream().anyMatch(c->eq(obj(c).get("source_id"),source.get("id"))),
                "origin_subject",map("paper_id",revision.get("paper_id"),"thesis_id",thesis.get("id"))));
      } else if(eq(source.get("kind"),"weekly_report")) {
        var report=db.get("weekly_reports",str(source,"entity_id"));
        if(report==null||!eq(report.get("student_id"),student)||!eq(report.get("lab_id"),lab))continue;
        var seen=new HashSet<String>();
        for(var review:db.query("mentor_reviews","WHERE report_id=? AND report_revision=? ORDER BY created_at DESC,id DESC",report.get("id"),report.get("current_revision"))) {
          String mentor=str(review,"mentor_id");if(!seen.add(mentor)||!bound(student,mentor,lab))continue;
          learn(run,candidates,"report_feedback",str(review,"id"),str(review,"body"),"周报 科研进展",
              List.of(ref),str(review,"created_at"),0.9,map("decision",review.get("decision")));
        }
      }
    }
    int scanned=0;
    for(var plan:db.query("research_followups","WHERE recipient_id=? AND student_id=? AND lab_id=? AND state IN ('applied','dismissed') ORDER BY updated_at DESC,id DESC",actor,student,lab)) {
      if(scanned++>=50)break;
      var previous=db.get("agent_runs",str(plan,"run_id"));
      if(previous==null||!eq(previous.get("status"),"succeeded")||!access.runReadable(actor,previous))continue;
      var subject=obj(run.get("subject"));var oldSubject=obj(previous.get("subject"));
      if(List.of("stage_id","paper_id").stream().anyMatch(key->subject.get(key)!=null&&!eq(subject.get(key),oldSubject.get(key))))continue;
      var snapshot=db.get("context_snapshots",str(previous,"snapshot_id"));
      if(snapshot==null||!pinned(list(snapshot.get("source_versions")),list(context.get("source_versions"))))continue;
      String topic=ExperienceRanking.clip(str(obj(previous.get("input")),"question"),500);
      var observations=map("state",plan.get("state"),"adoption_is_correctness",false);
      var actual=new ArrayList<String>();int completed=0;
      for(Object id:list(plan.get("task_ids"))) {
        var task=db.get("tasks",id.toString());
        if(task==null||!eq(task.get("student_id"),student)||!eq(task.get("lab_id"),lab))continue;
        actual.add(ExperienceRanking.clip(str(task,"title"),150));
        if(eq(task.get("status"),"completed"))completed++;
      }
      observations.put("completed_count",completed);
      observations.put("accepted_tasks",actual);
      String explicitReason=ExperienceRanking.clip(str(obj(plan.get("applied_items")),"feedback_note"),2000);
      observations.put("explicit_reason",explicitReason);
      observations.put("reason_known",!explicitReason.isBlank());
      var suggested=list(obj(previous.get("result")).get("next_steps")).stream()
          .map(raw->ExperienceRanking.clip(Objects.toString(obj(raw).get("title"),""),150)).filter(title->!title.isBlank()).toList();
      observations.put("customized_task_count",actual.stream().filter(title->!suggested.contains(title)).count());
      // Record chosen/edited task titles rather than rewarding the model's own unverified conclusions.
      String lesson=eq(plan.get("state"),"dismissed")?"用户曾忽略该问题的计划；再次建议时先确认需求，不能推断原建议错误":
          "用户实际选择的任务："+String.join("；",actual)+"；已完成 "+completed+" 项，仅表示使用结果，不证明科研结论正确";
      learn(run,candidates,"user_experience",str(plan,"id"),lesson,topic,list(snapshot.get("source_versions")),
          str(plan,"updated_at"),completed>0?0.7:0.3,observations);
      if(!explicitReason.isBlank())learn(run,candidates,"user_correction",str(plan,"id"),explicitReason,topic,
          list(snapshot.get("source_versions")),str(plan,"updated_at"),0.6,observations);
    }
    String query=Objects.toString(obj(run.get("input")).get("question"),"")+" "+
        Objects.toString(obj(context.get("stage")).get("title"),"")+" "+Objects.toString(obj(context.get("paper")).get("title"),"");
    if(query.isBlank())query=switch(Objects.toString(run.get("kind"),"")) {
      case "weekly_report" -> "周报 科研进展 完成 问题 下周安排";
      case "stage_plan" -> "科研 阶段 计划 任务";
      case "risk_brief" -> "任务 安排 风险";
      case "paper_guide" -> "论文 文献 方法";
      default -> "科研 计划 导师 反馈";
    };
    var selected=ExperienceRanking.select(candidates,query,5,Instant.now());
    context.put("feedback_memories",selected);
    var patterns=new LinkedHashMap<String,Set<String>>();
    for(var candidate:candidates)if(eq(candidate.get("origin_kind"),"user_correction"))
      patterns.computeIfAbsent(str(candidate,"category"),ignored->new LinkedHashSet<>()).add(str(candidate,"origin_id"));
    policy.put("repeated_correction_patterns",patterns.entrySet().stream().filter(entry->entry.getValue().size()>=2)
        .map(entry->map("category",entry.getKey(),"distinct_observations",entry.getValue().size(),"is_confirmed_rule",false)).toList());
    policy.put("eligible_memory_count",candidates.size());policy.put("retrieved_memory_count",selected.size());
  }
  private boolean bound(String student,String mentor,String lab) {
    return db.one("mentorships","WHERE student_id=? AND mentor_id=? AND lab_id=? AND active=TRUE",student,mentor,lab)!=null;
  }
  private boolean pinned(List<Object> refs,List<Object> current) {
    return refs.stream().allMatch(raw->current.stream().anyMatch(c->eq(obj(c).get("source_id"),obj(raw).get("source_id"))&&eq(obj(c).get("revision"),obj(raw).get("revision"))));
  }
  private void learn(Map<String,Object> run,List<Map<String,Object>> candidates,String kind,String origin,String text,String topic,List<?> refs,String observed,double signal,Map<String,Object> observations) {
    int index=0;
    for(String lesson:ExperienceRanking.extract(text)) {
      var extracted=FeedbackExperienceExtractor.describe(lesson,topic);
      if(eq(kind,"mentor_feedback")&&eq(extracted.get("reuse_scope"),"temporary_requirement")
          && !Boolean.TRUE.equals(observations.get("current_requirement")))continue;
      long maxAge=env.getProperty("yanji.experience-max-age-days",Long.class,180L);
      if(eq(extracted.get("reuse_scope"),"temporary_requirement"))
        maxAge=env.getProperty("yanji.experience-temporary-max-age-days",Long.class,7L);
      if(java.time.Duration.between(instant(observed),Instant.now()).toDays()>maxAge)continue;
      String id=UUID.nameUUIDFromBytes((str(run,"requested_by")+":"+kind+":"+origin+":"+index++).getBytes(StandardCharsets.UTF_8)).toString();
      var payload=map("memory_id",id,"origin_kind",kind,"origin_id",origin,"lesson",lesson,"topic",topic,
          "source_refs",refs,"observed_at",observed,"signal",signal,"observations",observations);
      payload.putAll(extracted);
      var stored=db.get("research_experiences",id);
      if(stored==null)db.insert("research_experiences",map("id",id,"lab_id",run.get("lab_id"),"student_id",run.get("student_id"),"recipient_id",run.get("requested_by"),"payload",payload));
      else if(!eq(json(stored.get("payload")),json(payload))) {stored.put("payload",payload);db.bump("research_experiences",stored);}
      candidates.add(payload);
    }
  }
}
