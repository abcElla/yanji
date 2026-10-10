package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.contract.OutputGuard;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class OutputGuardTest {
  @Test
  void paperMethodReadingPreservesMissingAttachmentAndInsufficientEvidence() {
    var context = Fixtures.context("research_qa");
    context.withObject("/input").put("question", "请结合这篇文献，解释关键方法、适用条件和局限，并说明与我的当前研究任务有什么关系。");
    context.putObject("paper").put("source_id", Fixtures.SOURCE);
    var evidence = Fixtures.evidence();
    ((ObjectNode) evidence.get(0)).put("text", "计算方法详见尚未上传的附件，适用条件也在附件中。");
    var answer = Fixtures.answer();
    answer.withObject("/result").put("outcome", "insufficient_evidence");
    answer.withObject("/result").putArray("claims");
    answer.withObject("/result").withArray("missing_inputs").add("包含计算方法的附件");
    var result = guard.prepare(answer, context, evidence, 1);
    assertThat(result.at("/result/outcome").asText()).isEqualTo("insufficient_evidence");
    assertThat(result.at("/result/missing_inputs").toString()).contains("包含计算方法的附件");
    assertThat(result.at("/result/claims").isEmpty()).isTrue();
  }

  @Test
  void paperMethodReadingUsesLocatedFormulaInsteadOfInventingAdmissionRequirements() {
    var context = Fixtures.context("research_qa");
    context.withObject("/input").put("question", "请结合这篇文献，解释关键方法、适用条件和局限，并说明与我的当前研究任务有什么关系。");
    context.putObject("paper").put("source_id", Fixtures.SOURCE);
    var evidence = Fixtures.evidence();
    ((ObjectNode) evidence.get(0))
        .put("text", "计算方法：功率除以1000，再乘使用时长和天数，得到kWh。适用条件：电价与功率为导师给定情景，真实值未知。");
    var answer = Fixtures.answer();
    answer.withObject("/result").put("outcome", "ok");
    answer.withObject("/result").putArray("claims");
    answer.withObject("/result").withArray("limitations").add("没有教材不能解读方法");
    var result = guard.prepare(answer, context, evidence, 1);
    assertThat(result.at("/result/claims").size()).isGreaterThan(0);
    assertThat(result.at("/result/missing_inputs").isEmpty()).isTrue();
    assertThat(result.at("/result/summary").asText()).contains("计算方法", "给定情景");
    assertThat(result.at("/result/limitations").toString()).doesNotContain("教材", "任务书");
    for (var claim : result.at("/result/claims"))
      assertThat(evidence.get(0).path("text").asText())
          .contains(claim.at("/source_refs/0/quote").asText());
    context.withObject("/input").put("question", "这个估算能证明学校实际收费错误吗？");
    answer.withObject("/result").put("outcome", "insufficient_evidence");
    var openQuestion = guard.prepare(answer, context, evidence, 1);
    assertThat(openQuestion.at("/result/outcome").asText()).isEqualTo("insufficient_evidence");
    assertThat(openQuestion.at("/result/claims").isEmpty()).isTrue();
  }

  @Test
  void aReadyPaperGuideDoesNotTurnModelInventionsIntoMandatoryMaterials() {
    var context = Fixtures.context("paper_guide");
    var answer = Fixtures.answer();
    context.putObject("paper").put("id", Fixtures.STAGE).put("version", 1);
    context.withArray("allowed_actions").add("reading_note.draft");
    var proposal =
        answer
            .withArray("proposal_drafts")
            .addObject()
            .put("action_type", "reading_note.draft")
            .put("reason", "核对笔记草稿");
    proposal.putArray("source_refs").add(Fixtures.reference());
    var payload = proposal.putObject("payload").put("paper_id", Fixtures.STAGE).putNull("task_id");
    payload.putArray("claims").add("结果为10");
    payload.putArray("questions").add("是否必须先提交宿舍配电审批？");
    payload.putArray("source_refs").add(Fixtures.reference());
    answer.withObject("/result").withArray("missing_inputs").add("电量计接线照片及宿舍管理规定");
    answer.withObject("/result").withArray("limitations").add("必须研究多个宿舍楼才可以通过");
    var result = guard.prepare(answer, context, Fixtures.evidence(), 1);
    assertThat(result.at("/result/missing_inputs").isEmpty()).isTrue();
    assertThat(result.at("/result/limitations").toString())
        .doesNotContain("接线照片", "宿舍管理规定", "多个宿舍楼");
    assertThat(result.at("/result/claims/0/text").asText()).isEqualTo("结果为10");
    assertThat(result.at("/proposal_drafts/0/payload/questions").toString())
        .doesNotContain("配电审批")
        .contains("原文");
  }

  @Test
  void riskReschedulingTargetsActualRisksAndFutureDatesWithinTheReviewWindow() {
    var context = Fixtures.context("risk_brief");
    context.withObject("/input").put("horizon_days", 7);
    context.withArray("allowed_actions").add("task.reschedule_batch");
    var task =
        context
            .withArray("tasks")
            .addObject()
            .put("id", Fixtures.STAGE)
            .put("status", "in_progress")
            .put("due_at", "2026-10-10T12:00:00Z");
    context
        .withArray("business_versions")
        .addObject()
        .put("type", "task")
        .put("id", Fixtures.STAGE)
        .put("version", 1);
    var answer = Fixtures.answer();
    var proposal =
        answer
            .withArray("proposal_drafts")
            .addObject()
            .put("action_type", "task.reschedule_batch")
            .put("reason", "请导师确认新的安排");
    proposal.putArray("source_refs").add(Fixtures.reference());
    var item =
        proposal
            .putObject("payload")
            .putArray("items")
            .addObject()
            .put("task_id", Fixtures.STAGE)
            .put("new_due_at", "2026-10-10T12:00:00Z");
    assertThatThrownBy(() -> guard.prepare(answer, context, Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
    task.put("due_at", "2026-10-01T12:00:00Z");
    for (String invalid : new String[] {"2026-10-01T12:00:00Z", "2026-11-01T12:00:00Z"}) {
      item.put("new_due_at", invalid);
      assertThatThrownBy(() -> guard.prepare(answer, context, Fixtures.evidence(), 1))
          .isInstanceOf(AgentFailure.class);
    }
    item.put("new_due_at", "2026-10-10T12:00:00Z");
    assertThat(
            guard
                .prepare(answer, context, Fixtures.evidence(), 1)
                .at("/proposal_drafts/0/payload/items/0/new_due_at")
                .asText())
        .isEqualTo("2026-10-10T12:00:00Z");
  }

  @Test
  void reportFeedbackCannotPublishInventedEquipmentBrandsOrSchoolRequirements() {
    var context = Fixtures.context("risk_brief");
    context.withObject("/input").put("report_id", Fixtures.STAGE);
    context
        .putObject("weekly_report")
        .put("id", Fixtures.STAGE)
        .put("current_revision", 2)
        .put("source_id", Fixtures.SOURCE)
        .put("source_revision", 1);
    context.withArray("allowed_actions").add("mentor_feedback.draft");
    context
        .withArray("business_versions")
        .addObject()
        .put("type", "weekly_report")
        .put("id", Fixtures.STAGE)
        .put("version", 3);
    var evidence = Fixtures.evidence();
    ((ObjectNode) evidence.get(0))
        .putObject("locator")
        .put("kind", "record_field")
        .put("field", "issues");
    for (String field : java.util.List.of("done_items", "next_plan", "help_needed")) {
      var section = ((ObjectNode) evidence.get(0)).deepCopy();
      section.putObject("locator").put("kind", "record_field").put("field", field);
      section.putNull("chunk_id");
      section.put(
          "text",
          switch (field) {
            case "done_items" -> "完成了计算核对。";
            case "next_plan" -> "核对后再安排测量。";
            default -> "请导师确认电价查询渠道。";
          });
      evidence.add(section);
    }
    var reference = Fixtures.reference();
    reference.putObject("locator").put("kind", "record_field").put("field", "issues");
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0")).putArray("source_refs").add(reference);
    answer.withObject("/result").withArray("limitations").add("必须买小米智能插座，并测量启动电流和温度。");
    var proposal =
        answer
            .withArray("proposal_drafts")
            .addObject()
            .put("action_type", "mentor_feedback.draft")
            .put("reason", "必须购买设备");
    proposal.putArray("source_refs").add(reference);
    proposal
        .putObject("payload")
        .put("report_id", Fixtures.STAGE)
        .put("report_revision", 2)
        .put("body", "请买小米设备，用万用表测量电流，并查询学校后勤官网电费公示栏。");
    var out = guard.prepare(answer, context, evidence, 1);
    assertThat(out.at("/proposal_drafts/0/payload/body").asText())
        .contains("结果为10", "核对")
        .doesNotContain("小米", "万用表", "后勤官网");
    assertThat(out.at("/result/limitations").toString()).doesNotContain("小米", "温度", "启动电流");
    assertThat(out.at("/proposal_drafts/0/payload/body").asText())
        .contains("完成了计算核对", "核对后再安排测量", "请导师确认电价查询渠道");
  }

  @Test
  void mentorFeedbackPinsReportAndRevisionInsteadOfAcceptingAnotherTarget() {
    var context = Fixtures.context("risk_brief");
    context.withObject("/input").put("report_id", Fixtures.STAGE);
    context.putObject("weekly_report").put("id", Fixtures.STAGE).put("current_revision", 2);
    context.withArray("allowed_actions").add("mentor_feedback.draft");
    context
        .withArray("business_versions")
        .addObject()
        .put("type", "weekly_report")
        .put("id", Fixtures.STAGE)
        .put("version", 3);
    var answer = Fixtures.answer();
    var proposal =
        answer
            .withArray("proposal_drafts")
            .addObject()
            .put("action_type", "mentor_feedback.draft")
            .put("reason", "核对后反馈");
    proposal.putArray("source_refs").add(Fixtures.reference());
    proposal
        .putObject("payload")
        .put("report_id", Fixtures.STAGE)
        .put("report_revision", 2)
        .put("body", "请先说明参数来源，再安排重复验证。");
    var prepared = guard.prepare(answer, context, Fixtures.evidence(), 1);
    assertThat(prepared.path("proposal_drafts")).hasSize(1);
    assertThat(prepared.at("/result/summary").asText())
        .contains("这份周报", "反馈草稿")
        .doesNotContain("调整安排");
    var insufficient = Fixtures.answer();
    insufficient
        .withObject("/result")
        .put("outcome", "insufficient_evidence")
        .put("summary", "尚未找到当前周报，请重新提交。");
    insufficient.withObject("/result").withArray("claims").removeAll();
    assertThat(
            guard
                .prepare(insufficient, context, Fixtures.JSON.createArrayNode(), 1)
                .at("/result/summary")
                .asText())
        .contains("尚未找到")
        .doesNotContain("反馈草稿");
    proposal.withObject("/payload").put("report_revision", 1);
    assertThatThrownBy(() -> guard.prepare(answer, context, Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
    proposal.withObject("/payload").put("report_revision", 2).put("report_id", Fixtures.STUDENT);
    assertThatThrownBy(() -> guard.prepare(answer, context, Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void researchSummaryShowsVerifiedContentWithoutAcceptingModelSummary() {
    var answer = Fixtures.answer();
    answer.withObject("/result").put("summary", "已完成实际采集，结果已经证明有效");
    var evidence = Fixtures.evidence();
    String purpose = "目的：比较午餐高峰的等待时间，尚未开展真实采集。";
    ((ObjectNode) evidence.get(0)).put("text", purpose);
    ((ObjectNode) answer.at("/result/claims/0/source_refs/0")).put("quote", purpose);
    var out = guard.prepare(answer, Fixtures.context("research_qa"), evidence, 1);
    assertThat(out.at("/result/summary").asText())
        .contains(purpose, "确认")
        .doesNotContain("已完成实际采集", "已经证明有效");
  }

  @Test
  void researchSummaryJoinsPdfLineWrapsButLeavesQuotesAndClaimsExact() {
    String quote = "等待时间定义为开\r\n始排队到取餐的分钟数。English\nwords remain separated.";
    var answer = Fixtures.answer();
    var evidence = Fixtures.evidence();
    ((ObjectNode) evidence.get(0)).put("text", quote);
    ((ObjectNode) answer.at("/result/claims/0/source_refs/0")).put("quote", quote);
    var out = guard.prepare(answer, Fixtures.context("research_qa"), evidence, 1);
    assertThat(out.at("/result/summary").asText())
        .contains("开始排队到取餐", "English words remain separated")
        .doesNotContain("开\n始", "开\r\n始");
    assertThat(out.at("/result/claims/0/text").asText()).isEqualTo(quote);
    assertThat(out.at("/result/claims/0/source_refs/0/quote").asText()).isEqualTo(quote);
  }

  @Test
  void selectedThesisSummaryPrioritizesItsLocatedFeedbackAndKeepsReferences() {
    var context = Fixtures.context("research_qa");
    context.withObject("/subject").put("paper_id", Fixtures.STAGE);
    String feedbackId = "10000000-0000-4000-8000-000000000005";
    context
        .withArray("source_versions")
        .addObject()
        .put("source_id", feedbackId)
        .put("revision", 1);
    var evidence = Fixtures.evidence();
    var feedback =
        evidence
            .addObject()
            .put("source_id", feedbackId)
            .put("source_revision", 1)
            .put("chunk_id", "field:content:0")
            .put("text", "请说明12条事件的取得方法，并补充中位数。");
    feedback.putObject("locator").put("kind", "record_field").put("field", "content");
    var answer = Fixtures.answer();
    var claim =
        answer.withObject("/result").withArray("claims").addObject().put("text", "导师已批准所有稿件");
    var ref = feedback.deepCopy();
    ref.remove("text");
    ref.put("quote", feedback.path("text").asText());
    claim.putArray("source_refs").add(ref);
    var out = guard.prepare(answer, context, evidence, 1);
    String summary = out.at("/result/summary").asText();
    assertThat(summary).contains("导师", "请说明12条事件的取得方法，并补充中位数。", "核对").doesNotContain("导师已批准所有稿件");
    assertThat(summary.indexOf("请说明12条事件")).isLessThan(summary.indexOf("结果为10"));
    assertThat(out.at("/result/claims/1/source_refs/0")).isEqualTo(ref);
    context.withArray("source_versions").remove(1);
    assertThatThrownBy(() -> guard.prepare(answer, context, evidence, 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void planningDoesNotInventAMandatoryRehearsalLabelButRetainsPresetDataFacts() {
    var answer = Fixtures.answer();
    var plan = Fixtures.plan();
    ((ObjectNode) plan.at("/payload/tasks/0"))
        .put("description", "设计匿名观察表。表头注明“仅用于预演，不替代真实采集”。记录匿名编号、入队时间和取餐时间。");
    answer.withArray("proposal_drafts").add(plan);
    var evidence = Fixtures.evidence();
    String dataBoundary = "数据来自12条预设排队事件，不是真实采集。";
    ((ObjectNode) evidence.get(0)).put("text", "结果为10。" + dataBoundary);
    ((ObjectNode) answer.at("/result/claims/0")).put("text", dataBoundary);
    ((ObjectNode) answer.at("/result/claims/0/source_refs/0")).put("quote", dataBoundary);
    var out = guard.prepare(answer, Fixtures.planningContext(), evidence, 1);
    assertThat(out.at("/proposal_drafts/0/payload/tasks/0/description").asText())
        .contains("设计匿名观察表", "记录匿名编号、入队时间和取餐时间")
        .doesNotContain("表头注明", "仅用于预演");
    assertThat(out.at("/result/claims/0/text").asText()).isEqualTo(dataBoundary);
    assertThat(out.at("/result/claims/0/source_refs/0/quote").asText()).isEqualTo(dataBoundary);
  }

  @Test
  void planningRetainsSupportedDependenciesAndRejectsInventedPredecessors() {
    var answer = Fixtures.answer();
    var plan = Fixtures.plan();
    var tasks = plan.withObject("/payload").withArray("tasks");
    ((ObjectNode) tasks.get(0)).put("title", "整理资料来源");
    var second = ((ObjectNode) tasks.get(0)).deepCopy().put("local_key", "b").put("title", "设计观察表");
    second.withArray("depends_on_local_keys").add("a");
    tasks.add(second);
    var independent =
        ((ObjectNode) tasks.get(0)).deepCopy().put("local_key", "c").put("title", "阅读相关方法");
    tasks.add(independent);
    answer.withArray("proposal_drafts").add(plan);
    var out = guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1);
    assertThat(out.at("/proposal_drafts/0/payload/tasks/1/depends_on_local_keys/0").asText())
        .isEqualTo("a");
    assertThat(out.at("/proposal_drafts/0/payload/tasks/2/depends_on_local_keys")).isEmpty();
    second.withArray("depends_on_local_keys").add("does-not-exist");
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void taskDescriptionsDoNotInventMinimumMethodCounts() {
    var raw = Fixtures.answer();
    var plan = Fixtures.plan();
    ((ObjectNode) plan.at("/payload/tasks/0")).put("description", "列出至少两种拟用于图像分类的算法，说明至少两点适用条件。");
    raw.withArray("proposal_drafts").add(plan);
    var out = guard.prepare(raw, Fixtures.planningContext(), Fixtures.evidence(), 1);
    assertThat(out.at("/proposal_drafts/0/payload/tasks/0/description").asText())
        .isEqualTo("列出拟用于图像分类的算法，说明适用条件。");
  }

  @Test
  void taskDescriptionsDoNotPresentUnsupportedExamplesAsResearchRequirements() {
    var raw = Fixtures.answer();
    var plan = Fixtures.plan();
    ((ObjectNode) plan.at("/payload/tasks/0")).put("description", "确认图片数据来源（如CIFAR-10子集），说明使用条件。");
    raw.withArray("proposal_drafts").add(plan);
    var out = guard.prepare(raw, Fixtures.planningContext(), Fixtures.evidence(), 1);
    assertThat(out.at("/proposal_drafts/0/payload/tasks/0/description").asText())
        .isEqualTo("确认图片数据来源，说明使用条件。");
  }

  @Test
  void stageDraftDoesNotInventWordMaximums() {
    var raw = Fixtures.answer();
    var plan = Fixtures.plan();
    ((ObjectNode) plan.at("/payload/tasks/0"))
        .putArray("acceptance_criteria")
        .add("一份不超过200字的书面陈述");
    raw.withArray("proposal_drafts").add(plan);
    var out = guard.prepare(raw, Fixtures.planningContext(), Fixtures.evidence(), 1);
    assertThat(out.at("/proposal_drafts/0/payload/tasks/0/acceptance_criteria/0").asText())
        .isEqualTo("一份书面陈述");
  }

  @Test
  void stageDraftDoesNotInventWordMinimumsOrPublishTemporaryCitationIds() {
    for (boolean authorized : java.util.List.of(false, true)) {
      var evidence = Fixtures.evidence();
      if (authorized) ((ObjectNode) evidence.get(0)).put("text", "结果为10。导师要求不少于50字的差异解释。");
      var answer = Fixtures.answer();
      var plan = Fixtures.plan();
      ((ObjectNode) plan.at("/payload/tasks/0")).put("description", "依据c1_1和c2_2–c2_6进行分析");
      ((ObjectNode) plan.at("/payload/tasks/0")).putArray("acceptance_criteria").add("不少于50字的差异解释");
      answer.withArray("proposal_drafts").add(plan);
      var out = guard.prepare(answer, Fixtures.planningContext(), evidence, 1);
      assertThat(out.at("/proposal_drafts/0/payload/tasks/0/description").asText())
          .doesNotContain("c1_1", "c2_2", "c2_6");
      assertThat(out.at("/proposal_drafts/0/payload/tasks/0/acceptance_criteria/0").asText())
          .isEqualTo(authorized ? "不少于50字的差异解释" : "差异解释");
    }
  }

  @Test
  void researchAnswerCannotExpandAPurposeQuoteIntoExecutionFacts() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0")).put("text", "实验尚未执行，准确率是0.5和1.0");
    ((ObjectNode) answer.path("result")).put("summary", "已确定该实验尚未执行");
    var out = guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1);
    assertThat(out.at("/result/claims/0/text").asText()).isEqualTo("结果为10");
    assertThat(out.at("/result/summary").asText()).doesNotContain("已确定该实验尚未执行");
    assertThat(out.at("/result/summary").asText()).contains("确认");
  }

  @Test
  void weeklyCannotClaimThreeRecordsFromOneQuotedResult() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0")).put("text", "三个实验全部得到相同结果");
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("weekly_report"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void compactWeeklyClaimsAndDoneItemsUseTheSelectedOriginalText() {
    var evidence = Fixtures.evidence();
    ((ObjectNode) evidence.get(0)).put("text", "synthetic evidence only; not scientific evidence.");
    var context = Fixtures.context("weekly_report");
    context.withObject("/input").put("week_start", "2026-10-05");
    context.put("existing_report_version", 0);
    context.withArray("allowed_actions").add("weekly_report.draft");
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0"))
        .putArray("source_refs")
        .add(Fixtures.object("{\"citation_id\":\"c1_1\"}"));
    var proposal =
        Fixtures.object(
            "{\"action_type\":\"weekly_report.draft\",\"reason\":\"三项已完成\",\"source_refs\":[{\"citation_id\":\"c1_1\"}],\"payload\":{\"week_start\":\"2026-10-05\",\"done_items\":[{\"text\":\"三个实验全部成功\",\"source_refs\":[{\"citation_id\":\"c1_1\"}]}],\"issues\":[],\"next_plan\":[],\"help_needed\":[]}}");
    answer.withArray("proposal_drafts").add(proposal);
    var out = guard.prepare(answer, context, evidence, 1);
    assertThat(out.at("/proposal_drafts/0/payload/done_items/0/text").asText())
        .isEqualTo(evidence.get(0).path("text").asText());
    assertThat(out.at("/result/claims/0/text").asText())
        .isEqualTo(evidence.get(0).path("text").asText());
    assertThat(out.toString()).doesNotContain("三个实验全部成功", "三项已完成");
  }

  @Test
  void legacyExactQuotationIsNotTranslatedAfterValidation() {
    var answer = Fixtures.answer();
    var evidence = Fixtures.evidence();
    String quote = "synthetic evidence, not scientific evidence";
    ((ObjectNode) evidence.get(0)).put("text", quote);
    ((ObjectNode) answer.at("/result/claims/0")).put("text", quote);
    ((ObjectNode) answer.at("/result/claims/0/source_refs/0")).put("quote", quote);
    assertThat(
            guard
                .prepare(answer, Fixtures.context("paper_guide"), evidence, 1)
                .at("/result/claims/0/text")
                .asText())
        .isEqualTo(quote);
  }

  @Test
  void legacyReadingNoteCannotSaveUnsupportedConclusion() {
    var context = Fixtures.context("paper_guide");
    context.withArray("allowed_actions").add("reading_note.draft");
    context.set("paper", Fixtures.object("{\"id\":\"" + Fixtures.STAGE + "\",\"version\":1}"));
    var answer = Fixtures.answer();
    var proposal =
        Fixtures.object(
            "{\"action_type\":\"reading_note.draft\",\"reason\":\"核对草稿\",\"source_refs\":[],\"payload\":{\"paper_id\":\""
                + Fixtures.STAGE
                + "\",\"task_id\":null,\"claims\":[\"已证明准确率99%\"],\"questions\":[],\"source_refs\":[]}}");
    proposal.withArray("source_refs").add(Fixtures.reference());
    proposal.withObject("/payload").withArray("source_refs").add(Fixtures.reference());
    answer.withArray("proposal_drafts").add(proposal);
    assertThatThrownBy(() -> guard.prepare(answer, context, Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void planningAndRiskSummariesDoNotPublishUnsupportedModelAssertions() {
    for (String kind : java.util.List.of("stage_plan", "risk_brief")) {
      var answer = Fixtures.answer();
      ((ObjectNode) answer.path("result")).put("summary", "已证明任务逾期造成实验失败");
      var result = guard.prepare(answer, Fixtures.context(kind), Fixtures.evidence(), 1);
      assertThat(result.at("/result/summary").asText()).doesNotContain("已证明任务逾期造成实验失败");
      assertThat(result.at("/result/claims/0/text").asText()).isEqualTo("结果为10");
    }
  }

  @Test
  void compactCitationRejectsUnknownChoiceAndProvenanceOverrides() {
    for (String ref :
        java.util.List.of(
            "{\"citation_id\":\"c99_1\"}",
            "{\"citation_id\":\"c1_1\",\"source_id\":\"invented\"}")) {
      var answer = Fixtures.answer();
      ((ObjectNode) answer.at("/result/claims/0"))
          .putArray("source_refs")
          .add(Fixtures.object(ref));
      assertThatThrownBy(
              () -> guard.prepare(answer, Fixtures.context("paper_guide"), Fixtures.evidence(), 1))
          .isInstanceOf(AgentFailure.class);
    }
  }

  @Test
  void compactCitationUsesExactBackendMetadataAndPublishesQuotedFacts() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0")).put("text", "这个引文证明任务已逾期");
    ((ObjectNode) answer.at("/result/claims/0"))
        .putArray("source_refs")
        .add(Fixtures.object("{\"citation_id\":\"c1_1\"}"));
    var result = guard.prepare(answer, Fixtures.context("paper_guide"), Fixtures.evidence(), 1);
    assertThat(result.at("/result/claims/0/text").asText()).isEqualTo("实验结果为10，尚未重复验证。");
    assertThat(result.at("/result/claims/0/source_refs/0/source_id").asText())
        .isEqualTo(Fixtures.SOURCE);
    assertThat(result.at("/result/claims/0/source_refs/0/locator"))
        .isEqualTo(Fixtures.evidence().get(0).path("locator"));
    assertThat(result.toString()).doesNotContain("这个引文证明任务已逾期", "citation_id");
  }

  @Test
  void taskStateCannotBorrowAnUnrelatedRuleQuote() {
    for (String kind : java.util.List.of("stage_plan", "risk_brief")) {
      var answer = Fixtures.answer();
      ((ObjectNode) answer.at("/result/claims/0")).put("text", "任务A的截止日期是2026-10-01，已经逾期");
      assertThatThrownBy(
              () -> guard.prepare(answer, Fixtures.context(kind), Fixtures.evidence(), 1))
          .isInstanceOf(AgentFailure.class);
    }
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void schemaFailureLogsConstraintLocationWithoutModelContent(CapturedOutput output) {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0/source_refs/0"))
        .put("quote", "QA_PRIVATE_CONTENT".repeat(30));
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
    assertThat(output.getAll()).contains("schema_invalid", "maxLength", "quote");
    assertThat(output.getAll()).doesNotContain("QA_PRIVATE_CONTENT");
  }

  final OutputGuard guard =
      new OutputGuard(
          Fixtures.JSON, Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void acceptsSchemaNullableLocatorFieldsButKeepsTheActualEvidencePosition() {
    var answer = Fixtures.answer();
    var locator = (ObjectNode) answer.at("/result/claims/0/source_refs/0/locator");
    locator.putNull("paragraph").putNull("field");
    assertThatCode(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .doesNotThrowAnyException();
    var result = guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1);
    assertThat(result.at("/result/claims/0/source_refs/0/locator"))
        .isEqualTo(Fixtures.evidence().get(0).path("locator"));
    locator.put("paragraph", 2);
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void addsTrustedAttemptAndAcceptsLocatedQuote() {
    var result =
        guard.prepare(Fixtures.answer(), Fixtures.context("research_qa"), Fixtures.evidence(), 3);
    assertThat(result.path("attempt").asInt()).isEqualTo(3);
  }

  @Test
  void rejectsInventedQuote() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.at("/result/claims/0/source_refs/0")).put("quote", "准确率99%");
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void rejectsSourceOutsideSnapshot() {
    var context = Fixtures.context("research_qa");
    context.withArray("source_versions").removeAll();
    assertThatThrownBy(() -> guard.prepare(Fixtures.answer(), context, Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void rejectsUnauthorizedAction() {
    var answer = Fixtures.answer();
    answer.withArray("proposal_drafts").add(Fixtures.plan());
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void fillsProposalMetadataFromContext() {
    var answer = Fixtures.answer();
    answer.withArray("proposal_drafts").add(Fixtures.plan());
    var out = guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1);
    assertThat(out.at("/proposal_drafts/0/target_student_id").asText()).isEqualTo(Fixtures.STUDENT);
    assertThat(out.at("/proposal_drafts/0/base_versions/0/version").asInt()).isEqualTo(1);
  }

  @Test
  void rejectsCyclicPlan() {
    var answer = Fixtures.answer();
    var plan = Fixtures.plan();
    ((ObjectNode) plan.at("/payload/tasks/0")).withArray("depends_on_local_keys").add("a");
    answer.withArray("proposal_drafts").add(plan);
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void insufficientEvidenceCannotCarryProposal() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.path("result")).put("outcome", "insufficient_evidence");
    answer.withArray("proposal_drafts").add(Fixtures.plan());
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void factualClaimMustHaveEvidence() {
    var answer = Fixtures.answer();
    ((ArrayNode) answer.at("/result/claims/0/source_refs")).removeAll();
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void rejectsUnknownOutputFields() {
    var answer = Fixtures.answer();
    ((ObjectNode) answer.path("result")).put("created_tasks", true);
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.context("research_qa"), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }

  @Test
  void rejectsMalformedTaskTypeRatherThanCoercingIt() {
    for (var value :
        java.util.List.of(
            Fixtures.JSON.nullNode(),
            Fixtures.JSON.createObjectNode(),
            Fixtures.JSON.createArrayNode(),
            Fixtures.JSON.getNodeFactory().numberNode(1))) {
      var answer = Fixtures.answer();
      var plan = Fixtures.plan();
      ((ObjectNode) plan.at("/payload/tasks/0")).set("type", value);
      answer.withArray("proposal_drafts").add(plan);
      assertThatThrownBy(
              () -> guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1))
          .isInstanceOf(AgentFailure.class);
    }
  }

  @Test
  void rejectsUnknownPayloadFields() {
    var answer = Fixtures.answer();
    var plan = Fixtures.plan();
    ((ObjectNode) plan.path("payload")).put("execute_now", true);
    answer.withArray("proposal_drafts").add(plan);
    assertThatThrownBy(
            () -> guard.prepare(answer, Fixtures.planningContext(), Fixtures.evidence(), 1))
        .isInstanceOf(AgentFailure.class);
  }
}
