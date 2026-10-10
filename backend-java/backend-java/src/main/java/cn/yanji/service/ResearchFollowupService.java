package cn.yanji.service;

import static cn.yanji.core.Data.*;
import cn.yanji.mapper.Repository;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.env.Environment;

/** Durable, recipient-scoped plans. Suggestions become tasks only after an explicit confirmation. */
@org.springframework.stereotype.Service
public class ResearchFollowupService {
  final Repository db;
  final Access access;
  final AgentService agents;
  final StageTaskService tasks;
  final Environment env;
  public ResearchFollowupService(Repository db, Access access, AgentService agents,
      StageTaskService tasks, Environment env) {
    this.db=db; this.access=access; this.agents=agents; this.tasks=tasks; this.env=env;
  }
  public Map<String,Object> readable(String actor,String id) {
    var row=readablePlan(actor,id);
    if(row.get("run_id")!=null) agents.run(actor,str(row,"run_id"));
    return row;
  }
  private Map<String,Object> readablePlan(String actor,String id) {
    var row=access.found(db.lock("research_followups",id));
    require(eq(row.get("recipient_id"),actor)
        && access.canStudent(actor,str(row,"student_id"),str(row,"lab_id"),false),
        404,"NOT_FOUND","推进计划不存在");
    return row;
  }
  Map<String,Object> detail(String actor,Map<String,Object> row) {
    return detail(actor,row,row.get("run_id")==null?null:agents.run(actor,str(row,"run_id")));
  }
  private Map<String,Object> detail(String actor,Map<String,Object> row,Map<String,Object> run) {
    var out=pick(row,"id","student_id","state","changes","task_ids","created_at","updated_at","version");
    out.put("run", run==null?null:agents.detail(run));
    out.put("tasks",list(row.get("task_ids")).stream().map(Object::toString)
        .map(id->access.visible(actor,"tasks",id)).map(tasks::taskData).toList());
    return out;
  }
  public Object handle(String method,String path,String actor,Map<String,Object> body,Map<String,Object> query) {
    String[] parts=path.split("/");
    if(parts.length==2) {
      if(method.equals("POST")) {
        String student=str(body,"student_id"),lab=access.lab(actor,student,false);
        String question=str(body,"question");
        require(question!=null&&!question.isBlank()&&question.length()<=2000,422,"VALIDATION_ERROR","请填写具体的科研问题，最多2000字");
        db.lock("users",student);
        var row=db.insert("research_followups",map("lab_id",lab,"student_id",student,"recipient_id",actor,
            "changes",List.of(map("reason","你向 AI助教提出了新的问题","question",question,"happened_at",now()))));
        var subject=map("student_id",student,"followup_id",row.get("id"));
        var stage=db.one("stages","WHERE student_id=? AND lab_id=? AND status='active' ORDER BY created_at DESC,id DESC",student,lab);
        if(stage!=null)subject.put("stage_id",stage.get("id"));
        var run=agents.create(actor,map("kind","research_followup","subject",subject,"input",map("question",question)));
        row.put("run_id",run.get("run_id"));row.put("run_created_at",now());row.put("state","active");
        db.bump("research_followups",row);return detail(actor,row);
      }
      if(query.get("student_id")!=null) access.lab(actor,str(query,"student_id"),false);
      var items=new ArrayList<Map<String,Object>>();
      var rows=query.get("student_id")==null
          ? db.query("research_followups","WHERE recipient_id=? AND state<>'closed' ORDER BY created_at DESC,id DESC",actor)
          : db.query("research_followups","WHERE recipient_id=? AND student_id=? AND state<>'closed' ORDER BY created_at DESC,id DESC",actor,query.get("student_id"));
      for(var row:rows) {
        if(!access.canStudent(actor,str(row,"student_id"),str(row,"lab_id"),false))continue;
        // A revoked source also revokes a plan that contains its contents.
        var run=row.get("run_id")==null?null:db.get("agent_runs",str(row,"run_id"));
        if(row.get("run_id")!=null && !access.runReadable(actor,run))continue;
        items.add(detail(actor,row,run)); if(items.size()==20)break;
      }
      return map("items",items);
    }
    var row=readablePlan(actor,parts[2]);
    if(method.equals("GET"))return detail(actor,row);
    db.lock("users",str(row,"student_id"));
    row=readable(actor,parts[2]);
    if(parts[3].equals("apply") && eq(row.get("state"),"applied")) {
      require(eq(json(row.get("applied_items")),json(body)),409,"ALREADY_APPLIED","这份计划已经加入任务，请查看已创建的任务");
      return detail(actor,row);
    }
    db.version(row,body);
    String feedbackNote=str(body,"feedback_note");
    require(feedbackNote==null||feedbackNote.length()<=2000,422,"VALIDATION_ERROR","修改或忽略原因最多2000字");
    if(parts[3].equals("dismiss")) {
      require(!eq(row.get("state"),"applied"),409,"INVALID_STATE","已落实的计划不能忽略");
      row.put("state","dismissed");
      row.put("applied_items",map("feedback_note",feedbackNote==null?"":feedbackNote));
      db.bump("research_followups",row); return detail(actor,row);
    }
    if(parts[3].equals("retry")) {
      require(!Set.of("applied","closed").contains(str(row,"state")),409,"INVALID_STATE","请在新的进展上继续分析");
      if(row.get("run_id")!=null) {
        var run=agents.run(actor,str(row,"run_id"));
        require(!Set.of("queued","running").contains(str(run,"status")),409,"RUN_IN_PROGRESS","AI 正在整理计划，请稍后查看");
      }
      String question="请结合最新进展，说明接下来优先做什么。";
      if(row.get("run_id")!=null) {
        var original=db.get("agent_runs",str(row,"run_id"));
        String asked=str(obj(original.get("input")),"question");
        if(asked!=null&&!asked.isBlank())question=asked;
      }
      var fresh=db.insert("research_followups",map("lab_id",row.get("lab_id"),"student_id",row.get("student_id"),
          "recipient_id",actor,"changes",List.of(map("reason","你要求重新分析当前科研进展","question",question))));
      var subject=map("student_id",row.get("student_id"),"followup_id",fresh.get("id"));
      var stage=db.one("stages","WHERE student_id=? AND lab_id=? AND status='active' ORDER BY created_at DESC,id DESC",row.get("student_id"),row.get("lab_id"));
      if(stage!=null)subject.put("stage_id",stage.get("id"));
      var run=agents.create(actor,map("kind","research_followup","subject",subject,"input",map("question",question)));
      fresh.put("run_id",run.get("run_id"));fresh.put("run_created_at",now());fresh.put("state","active");
      db.bump("research_followups",fresh);
      row.put("state","dismissed"); db.bump("research_followups",row);
      return detail(actor,fresh);
    }
    require(parts[3].equals("apply"),404,"NOT_FOUND","操作不存在");
    require(eq(row.get("state"),"active") && row.get("run_id")!=null,409,"PLAN_NOT_READY","请等待计划生成后再加入任务");
    var run=agents.run(actor,str(row,"run_id"));
    require(eq(run.get("status"),"succeeded"),409,"PLAN_NOT_READY","请等待计划生成后再加入任务");
    var items=list(body.get("items"));
    require(!items.isEmpty()&&items.size()<=5,422,"VALIDATION_ERROR","一次请选择一至五项任务");
    var ids=new ArrayList<String>();
    var stepIds=new LinkedHashMap<String,String>();
    for(Object raw:items) {
      var task=new LinkedHashMap<String,Object>(obj(raw));
      String stepId=str(task,"step_id");
      if(stepId!=null)require(!stepIds.containsKey(stepId),422,"VALIDATION_ERROR","计划步骤编号重复");
      if(task.get("existing_task_id")!=null) {
        var existing=access.visible(actor,"tasks",str(task,"existing_task_id"));
        require(eq(existing.get("student_id"),row.get("student_id"))
            && eq(existing.get("lab_id"),row.get("lab_id")),404,"NOT_FOUND","任务不存在");
        require(!eq(existing.get("status"),"completed"),409,"TASK_ALREADY_COMPLETED","任务已经完成，请选择待推进的任务");
        db.version(existing,map("expected_version",task.get("expected_version")));
        String id=str(existing,"id");
        require(!ids.contains(id),422,"VALIDATION_ERROR","不能重复关联同一任务");
        ids.add(id);
        if(stepId!=null)stepIds.put(stepId,id);
        continue;
      }
      require(str(task,"title")!=null&&!str(task,"title").isBlank(),422,"VALIDATION_ERROR","任务标题不能为空");
      task.put("student_id",row.get("student_id"));task.put("lab_id",row.get("lab_id"));
      if(task.containsKey("depends_on")) {
        var dependencies=new ArrayList<String>();
        for(Object ref:list(task.get("depends_on"))) {
          String requested=ref.toString();String dependency=stepIds.getOrDefault(requested,requested);
          var prerequisite=access.visible(actor,"tasks",dependency);
          require(eq(prerequisite.get("student_id"),row.get("student_id"))&&eq(prerequisite.get("lab_id"),row.get("lab_id")),404,"NOT_FOUND","前置任务不存在，请选择前置步骤或已有任务");
          dependencies.add(dependency);
        }
        task.put("dependency_ids",dependencies);
      }
      for(String field:List.of("step_id","task_mode","existing_task_id","depends_on","expected_version"))task.remove(field);
      if(body.get("stage_id")!=null)task.put("stage_id",body.get("stage_id"));
      String id=str(tasks.createTask(actor,task),"id");
      var created=db.get("tasks",id);created.put("origin","agent_approved");db.update("tasks",created);
      ids.add(id);
      if(stepId!=null)stepIds.put(stepId,id);
    }
    row.put("task_ids",ids);row.put("applied_items",body);row.put("state","applied");
    db.bump("research_followups",row);return detail(actor,row);
  }
  /** Called inside the worker transaction. Its receipt prevents duplicate event delivery. */
  public void consume(Map<String,Object> event) {
    var setting=db.get("research_followup_settings","global");
    if(setting!=null && instant(event.get("created_at")).isBefore(instant(setting.get("enabled_since"))))return;
    var payload=obj(event.get("payload"));String type=str(event,"event_type");
    Map<String,Object> record=null, source=null;
    String reason=null;
    boolean skill=type.equals("research_skill.completed");
    if(skill) {
      record=db.get("agent_runs",str(payload,"run_id"));
      if(record==null || !Set.of("risk_brief","weekly_report","stage_plan","paper_guide").contains(str(record,"kind"))
          || !eq(record.get("status"),"succeeded") || !eq(obj(record.get("result")).get("outcome"),"ok"))return;
      if(!access.runReadable(str(record,"requested_by"),record))return;
      reason="专项分析已经完成，结合分析结果更新下一步计划";
    } else if(type.equals("task.completed")) {
      record=db.get("tasks",str(payload,"task_id"));reason="一项任务已经完成，继续安排下一步";
    } else if(type.equals("experiment.confirmed")||type.equals("note.confirmed")) {
      boolean experiment=type.startsWith("experiment");
      record=db.get(experiment?"experiments":"reading_notes",str(payload,experiment?"experiment_id":"note_id"));
      source=db.get("sources",str(payload,"source_id"));
      reason=experiment?"你补充了实验记录，重新整理后续工作":"你完成了阅读笔记，更新科研准备计划";
    } else if(type.equals("paper.ready")) {
      record=db.get("papers",str(payload,"paper_id"));source=record;
      reason="新材料已经可以阅读，整理需要继续完成的工作";
    } else if(type.equals("thesis_feedback.created")) {
      var feedback=db.get("thesis_feedback",str(payload,"feedback_id"));
      var revision=feedback==null?null:db.get("thesis_revisions",str(feedback,"revision_id"));
      record=revision==null?null:db.get("theses",str(revision,"thesis_id"));
      source=db.get("sources",str(payload,"source_id"));reason="导师给出了新的稿件反馈，整理修改清单";
    }
    if(record==null||reason==null)return;
    if(!skill && !type.equals("task.completed") && source==null)return;
    String student=str(record,"student_id");if(student==null)student=str(record,"owner_id");
    String lab=str(record,"lab_id");if(student==null||lab==null)return;
    db.lock("users",student);
    var recipients=new LinkedHashSet<String>();recipients.add(skill?str(record,"requested_by"):student);
    if(!skill)for(var mentor:db.query("mentorships","WHERE student_id=? AND lab_id=? AND active=TRUE",student,lab))
      recipients.add(str(mentor,"mentor_id"));
    for(String recipient:recipients) {
      if(!access.canStudent(recipient,student,lab,false)||(source!=null&&!access.source(recipient,source)))continue;
      String receipt=UUID.nameUUIDFromBytes((str(event,"id")+":"+recipient).getBytes(StandardCharsets.UTF_8)).toString();
      if(db.get("research_followup_events",receipt)!=null)continue;
      var row=db.one("research_followups","WHERE student_id=? AND recipient_id=? AND state='waiting' ORDER BY created_at,id",student,recipient);
      if(row==null)row=db.insert("research_followups",map("student_id",student,"lab_id",lab,"recipient_id",recipient));
      var changes=new ArrayList<Object>(list(row.get("changes")));
      changes.add(map("event_id",event.get("id"),"reason",reason,"happened_at",event.get("created_at")));
      row.put("changes",changes);db.bump("research_followups",row);
      db.insert("research_followup_events",map("id",receipt,"event_id",event.get("id"),"recipient_id",recipient,"followup_id",row.get("id")));
    }
  }
  public void dispatchPending() {
    int count=0;
    for(var candidate:db.query("research_followups","WHERE state='waiting' ORDER BY created_at,id")) {
      if(count>=20)break;
      db.lock("users",str(candidate,"student_id"));
      var row=db.lock("research_followups",str(candidate,"id"));if(!eq(row.get("state"),"waiting"))continue;
      String actor=str(row,"recipient_id"),student=str(row,"student_id"),lab=str(row,"lab_id");
      if(!access.canStudent(actor,student,lab,false)) {row.put("state","closed");db.bump("research_followups",row);continue;}
      var recent=db.one("research_followups","WHERE student_id=? AND recipient_id=? AND run_created_at IS NOT NULL ORDER BY run_created_at DESC,id DESC",student,actor);
      if(recent!=null&&Duration.between(instant(recent.get("run_created_at")),Instant.now()).toSeconds()
          <env.getProperty("yanji.followup-cooldown-seconds",Long.class,90L))continue;
      boolean running=db.query("research_followups","WHERE student_id=? AND recipient_id=? AND state='active'",student,actor).stream()
          .map(r->db.get("agent_runs",str(r,"run_id"))).filter(Objects::nonNull)
          .anyMatch(r->Set.of("queued","running").contains(str(r,"status")));
      if(running)continue;
      var subject=map("student_id",student,"followup_id",row.get("id"));
      var stage=db.one("stages","WHERE student_id=? AND lab_id=? AND status='active' ORDER BY created_at DESC,id DESC",student,lab);
      if(stage!=null)subject.put("stage_id",stage.get("id"));
      var run=agents.create(actor,map("kind","research_followup","subject",subject,"input",
          map("question","结合当前阶段、最新进展和导师反馈，给我一份清晰的下一步计划。不要重复安排已完成的任务。")));
      row.put("run_id",run.get("run_id"));row.put("run_created_at",now());row.put("state","active");
      db.bump("research_followups",row);
      count++;
    }
  }
}
