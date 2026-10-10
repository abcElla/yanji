package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.agent.backend.*;
import cn.yanji.agent.contract.*;
import cn.yanji.agent.model.ModelGateway;
import cn.yanji.agent.workflow.*;
import com.fasterxml.jackson.databind.node.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ResearchLoopWorkflowTest {
  @Test
  void adviceModeAnswersEvenWhenResearchQaSearchIsEmpty() {
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    workflow(false, Duration.ofSeconds(1), false).execute(Fixtures.RUN);
    verify(model).generate(argThat(c -> c.path("advice_mode").asBoolean()), any(), any());
    verify(backend)
        .finalizeRun(
            any(), argThat(out -> out.at("/result/summary").asText().equals("有实验结果，尚需验证。")));
  }

  @Test
  void riskAdviceDoesNotStopAtTheNoRiskTemplate() {
    var context = Fixtures.context("risk_brief");
    context
        .putArray("tasks")
        .addObject()
        .put("title", "阅读论文")
        .put("status", "todo")
        .putNull("due_at");
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    var raw = Fixtures.answer();
    raw.withObject("/result").put("summary", "目前没有任务阻塞，建议先整理论文的问题、方法和实验设计。");
    when(model.generate(any(), any(), any())).thenReturn(raw);
    workflow(false, Duration.ofSeconds(1), false).execute(Fixtures.RUN);
    verify(model).generate(any(), any(), any());
    verify(backend)
        .finalizeRun(any(), argThat(out -> out.at("/result/summary").asText().contains("实验设计")));
  }

  @Test
  void adviceModeRetainsRejectedPlanningAsTextWithoutMakingItAdoptable() {
    when(backend.context(any())).thenReturn(ConstraintAwarePlanningTest.context());
    var raw = Fixtures.answer();
    raw.withObject("/result").put("summary", "先评估改进方案的收益，再核对基线实验的依赖。");
    raw.withObject("/result")
        .putArray("next_steps")
        .addObject()
        .put("title", "改进模型")
        .put("description", "对比模块结构和训练设置。");
    when(model.generate(any(), any(), any())).thenReturn(raw);
    workflow(false, Duration.ofSeconds(1), false).execute(Fixtures.RUN);
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                out ->
                    out.at("/result/summary").asText().contains("改进方案的收益")
                        && out.at("/result/claims").toString().contains("对比模块结构")
                        && out.at("/result/next_steps").isEmpty()
                        && out.path("proposal_drafts").isEmpty()));
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void explicitFeedbackPassIsUsedBeforeGenerationAndCannotSpendAThirdCall() {
    var context = Fixtures.context("research_followup");
    context
        .putObject("learning_policy")
        .put("feedback_learning_enabled", true)
        .put("constrained_reasoning_enabled", true)
        .put("grounded_guidance_enabled", true);
    context.putArray("feedback_memories").addObject().put("lesson", "请先固定数据划分");
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    var interpretations = Fixtures.JSON.createArrayNode();
    interpretations
        .addObject()
        .put("memory_index", 0)
        .put("source_quote", "请先固定数据划分")
        .put("direct_request", true)
        .put("intent", "data_split")
        .put("requested_change", "先固定划分");
    when(model.extractFeedback(any())).thenReturn(interpretations);
    var wrong = Fixtures.answer();
    wrong.withObject("/result").putArray("claims").addObject().put("text", "模型准确率98%");
    when(model.generate(any(), any(), any())).thenReturn(wrong);
    workflow(false, Duration.ofSeconds(2), false).execute(Fixtures.RUN);
    verify(model).extractFeedback(argThat(c -> !c.has("student_id") && !c.has("source_versions")));
    verify(model, times(1))
        .generate(
            argThat(
                c -> c.at("/feedback_interpretations/0/requested_change").asText().equals("先固定划分")),
            any(),
            any());
    verify(model, never()).plan(any());
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                out ->
                    out.at("/result/summary").asText().contains("有实验结果，尚需验证。")
                        && out.at("/result/claims").toString().contains("待核对的分析")));
  }

  @Test
  void followupStillGeneratesAMaterialPreparationPlanWhenSearchIsEmpty() {
    var context = Fixtures.context("research_followup");
    context.putObject("followup").putArray("changes").addObject().put("reason", "导师补充了修改意见");
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    var answer = Fixtures.answer();
    answer
        .withObject("/result")
        .putArray("next_steps")
        .addObject()
        .put("title", "补充计算条件")
        .put("type", "writing");
    when(model.generate(any(), any(), any())).thenReturn(answer);
    workflow(true, Duration.ofSeconds(1), false).execute(Fixtures.RUN);
    verify(model, times(1)).plan(any());
    verify(model, times(1)).generate(argThat(c -> c.path("followup").isObject()), any(), any());
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
    verify(backend)
        .finalizeRun(
            any(), argThat(out -> out.at("/result/next_steps/0/title").asText().equals("补充计算条件")));
  }

  @Test
  void displayModeFinalizesUnexpectedActionsWithoutFailingOrGeneratingAgain() {
    var answer = Fixtures.answer();
    answer.withArray("proposal_drafts").add(Fixtures.plan());
    when(model.generate(any(), any(), any())).thenReturn(answer);
    workflow(true, Duration.ofSeconds(1), false).execute(Fixtures.RUN);
    verify(model, times(1)).generate(any(), any(), any());
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                out ->
                    out.path("proposal_drafts").isEmpty()
                        && out.at("/result/claims").toString().contains("验证实验")));
  }

  @Test
  void twoIndependentRunsReadNewSnapshotAndEvidenceRevision() {
    String secondId = "20000000-0000-4000-8000-000000000001";
    var first = Fixtures.context("research_qa");
    first.put("snapshot_id", "snapshot-first");
    var second = Fixtures.context("research_qa");
    second.put("run_id", secondId).put("snapshot_id", "snapshot-second");
    ((ObjectNode) second.path("source_versions").get(0)).put("revision", 2);
    when(backend.context(any()))
        .thenAnswer(inv -> ((Lease) inv.getArgument(0)).runId().equals(secondId) ? second : first);
    var next = Fixtures.evidence();
    ((ObjectNode) next.get(0)).put("source_revision", 2).put("text", "已重复3次，并明确数据划分。");
    when(backend.search(any(), any()))
        .thenAnswer(
            inv -> {
              var response = Fixtures.JSON.createObjectNode();
              response.set(
                  "chunks",
                  ((Lease) inv.getArgument(0)).runId().equals(secondId)
                      ? next
                      : Fixtures.evidence());
              return response;
            });
    when(model.generate(any(), any(), any()))
        .thenAnswer(
            inv -> {
              var raw = Fixtures.answer();
              ArrayNode evidence = inv.getArgument(1);
              if (evidence.get(0).path("source_revision").asInt() == 2) {
                var ref = (ObjectNode) raw.at("/result/claims/0/source_refs/0");
                ref.put("source_revision", 2).put("quote", "已重复3次");
              } else raw.withObject("/result").withArray("missing_inputs").add("重复材料与数据划分");
              return raw;
            });
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenAnswer(
            inv ->
                Fixtures.JSON
                    .createObjectNode()
                    .put("source_id", Fixtures.SOURCE)
                    .put("source_revision", (int) inv.getArgument(2))
                    .put(
                        "excerpt",
                        (int) inv.getArgument(2) == 2 ? "已重复3次，并明确数据划分。" : "实验结果为10，尚未重复验证。"));
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    workflow(true, Duration.ofSeconds(1)).execute(secondId);
    verify(backend)
        .finalizeRun(
            argThat(lease -> lease.runId().equals(Fixtures.RUN)),
            argThat(body -> body.at("/result/missing_inputs").size() == 1));
    verify(backend)
        .finalizeRun(
            argThat(lease -> lease.runId().equals(secondId)),
            argThat(
                body ->
                    body.at("/result/missing_inputs").isEmpty()
                        && body.at("/result/claims/0/source_refs/0/source_revision").asInt() == 2));
  }

  @Test
  void changedSourceDuringFinalRecheckNeverCommitsOldSnapshot() {
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenReturn(
            Fixtures.JSON
                .createObjectNode()
                .put("source_id", Fixtures.SOURCE)
                .put("source_revision", 2)
                .put("excerpt", "实验结果为10，尚未重复验证。"));
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend).fail(any(), eq("SOURCE_STALE"), anyString(), eq(false));
  }

  @Test
  void studentResearchQaCannotProduceTaskEvenAfterPlanning() {
    var raw = Fixtures.answer();
    raw.withArray("proposal_drafts").add(Fixtures.plan());
    when(model.generate(any(), any(), any())).thenReturn(raw);
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend, never()).finalizeRun(any(), any());
    verify(model, times(1)).generate(any(), any(), any());
  }

  @Test
  void unmappedStageRulesStopBeforeGeneration() {
    var ctx = Fixtures.planningContext();
    ctx.set("stage_rules", Fixtures.object("{\"required\":[\"重复验证\"]}"));
    when(backend.context(any())).thenReturn(ctx);
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, never()).generate(any(), any(), any());
    verify(backend)
        .finalizeRun(
            any(), argThat(body -> body.at("/result/outcome").asText().equals("needs_input")));
  }

  @Test
  void baselineAlsoStopsWhenStageRulesHaveNoAuthorizedSource() {
    var ctx = Fixtures.planningContext();
    ctx.set("stage_rules", Fixtures.object("{\"required\":[\"重复验证\"]}"));
    when(backend.context(any())).thenReturn(ctx);
    workflow(false, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, never()).generate(any(), any(), any());
    verify(backend)
        .finalizeRun(
            any(), argThat(body -> body.at("/result/outcome").asText().equals("needs_input")));
  }

  @Test
  void unsupportedRepetitionRequirementProducesNoClaimsOrActions() {
    var raw = Fixtures.answer();
    raw.withObject("/result").put("summary", "至少3次重复实验才能完成验证。");
    when(model.generate(any(), any(), any())).thenReturn(raw);
    enforceBackendEventIdempotency();
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                body ->
                    "needs_input".equals(body.at("/result/outcome").asText())
                        && body.at("/result/claims").isEmpty()
                        && body.path("proposal_drafts").isEmpty()));
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void regenerationAlsoDropsAnUnsupportedRequirement() {
    var invalid = Fixtures.answer();
    invalid.put("unknown", true);
    var unsupported = Fixtures.answer();
    unsupported.withObject("/result").put("summary", "至少3次重复实验才能完成验证。");
    when(model.generate(any(), any(), any())).thenReturn(invalid, unsupported);
    enforceBackendEventIdempotency();
    workflow(false, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                body ->
                    "needs_input".equals(body.at("/result/outcome").asText())
                        && body.at("/result/claims").isEmpty()
                        && body.path("proposal_drafts").isEmpty()));
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void permissionFailureDuringPlanMustNotDegradeOrGenerate() {
    when(model.plan(any())).thenThrow(new AgentFailure("LEASE_EXPIRED", "失去权限", false, true));
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend, never()).search(any(), any());
    verify(backend, never()).finalizeRun(any(), any());
    verify(model, never()).generate(any(), any(), any());
  }

  @Test
  void lexicalFallbackIsAResultLimitationNotAFakeFailure() {
    var search = Fixtures.JSON.createObjectNode().put("retrieval_mode", "lexical_fallback");
    search.set("chunks", Fixtures.evidence());
    when(backend.search(any(), any())).thenReturn(search);
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend)
        .finalizeRun(
            any(), argThat(body -> body.at("/result/limitations").toString().contains("语义检索暂不可用")));
  }

  private void enforceBackendEventIdempotency() {
    var events = new HashMap<String, List<String>>();
    doAnswer(
            inv -> {
              String key = inv.getArgument(1);
              var value =
                  List.of(
                      (String) inv.getArgument(2),
                      (String) inv.getArgument(3),
                      (String) inv.getArgument(4));
              var existing = events.putIfAbsent(key, value);
              if (existing != null && !existing.equals(value))
                throw new AgentFailure("IDEMPOTENCY_CONFLICT", "同一事件键对应不同内容", false);
              return null;
            })
        .when(backend)
        .event(any(), anyString(), anyString(), anyString(), anyString());
  }

  BackendClient backend = mock(BackendClient.class);
  ModelGateway model = mock(ModelGateway.class);
  ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1);
  ExecutorService models = Executors.newFixedThreadPool(1),
      planners = Executors.newFixedThreadPool(1);
  Clock clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);

  RunWorkflow workflow(boolean enabled, Duration softBudget) {
    return workflow(enabled, softBudget, true);
  }

  RunWorkflow workflow(boolean enabled, Duration softBudget, boolean validationEnabled) {
    return new RunWorkflow(
        backend,
        model,
        new OutputGuard(Fixtures.JSON, clock, validationEnabled),
        new BusinessPolicy(Fixtures.JSON, clock),
        Fixtures.JSON,
        heartbeats,
        models,
        Duration.ofSeconds(25),
        Duration.ofSeconds(3),
        "test-worker",
        new SimpleMeterRegistry(),
        enabled,
        softBudget,
        planners);
  }

  @BeforeEach
  void setup() {
    when(backend.claim(anyString(), anyString()))
        .thenReturn(
            Fixtures.object("{\"status\":\"running\",\"attempt\":1,\"lease_token\":\"private\"}"));
    when(backend.context(any())).thenReturn(Fixtures.context("research_qa"));
    var search = Fixtures.JSON.createObjectNode();
    search.set("chunks", Fixtures.evidence());
    when(backend.search(any(), any())).thenReturn(search);
    when(model.plan(any()))
        .thenReturn(new ResearchPlan("核对资料", List.of(new ResearchPlan.Query("针对性 基线", "找材料"))));
    when(model.generate(any(), any(), any())).thenReturn(Fixtures.answer());
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenReturn(
            Fixtures.object(
                "{\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1,\"excerpt\":\"实验结果为10，尚未重复验证。\"}"));
  }

  @AfterEach
  void close() {
    heartbeats.shutdownNow();
    models.shutdownNow();
    planners.shutdownNow();
  }

  @Test
  void enabledPlansAndUsesQueriesWithoutLeakingIdentity() {
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model)
        .plan(
            argThat(
                ctx ->
                    !ctx.has("student_id") && !ctx.has("run_id") && !ctx.has("source_versions")));
    verify(backend).search(any(), argThat(body -> body.path("query").asText().equals("针对性 基线")));
    verify(backend).finalizeRun(any(), any());
  }

  @Test
  void plannedGenerationFailureNeverUsesThirdModelCall() {
    when(model.generate(any(), any(), any())).thenReturn(Fixtures.object("{\"bad\":true}"));
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, times(1)).plan(any());
    verify(model, times(1)).generate(any(), any(), any());
    verify(backend).fail(any(), eq("MODEL_OUTPUT_INVALID"), anyString(), eq(false));
  }

  @Test
  void plannerFailureDegradesButStillConsumesCallBudget() {
    when(model.plan(any()))
        .thenThrow(new AgentFailure("PLANNER_OUTPUT_INVALID", "资料中有私密文本", false));
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(backend)
        .event(
            any(),
            eq("plan-degraded"),
            eq("plan"),
            eq("completed"),
            argThat(text -> !text.contains("私密")));
    verify(model, times(1)).generate(any(), any(), any());
  }

  @Test
  void plannerTimeoutCancelsFutureAndLeavesGenerationExecutorAvailable() {
    when(model.plan(any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(500);
              return new ResearchPlan("x", List.of());
            });
    workflow(true, Duration.ofMillis(30)).execute(Fixtures.RUN);
    verify(backend).finalizeRun(any(), any());
    verify(model).generate(any(), any(), any());
  }

  @Test
  void emptySearchDoesNotCallGeneration() {
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, never()).generate(any(), any(), any());
    verify(backend, times(2)).search(any(), any());
  }

  @Test
  void disabledPreservesOldSingleGenerationPath() {
    workflow(false, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, never()).plan(any());
    verify(backend).finalizeRun(any(), any());
  }

  @Test
  void constraintFeedbackRepairsPlanWithinTwoCalls() {
    var context = ConstraintAwarePlanningTest.context();
    when(backend.context(any())).thenReturn(context);
    var wrong = Fixtures.answer();
    wrong.withObject("/result").putArray("next_steps").addObject().put("title", "改进模型");
    var repaired = Fixtures.answer();
    repaired.withObject("/result").putArray("next_steps").addObject().put("title", "基线实验");
    repaired.withObject("/result").withArray("/next_steps").addObject().put("title", "改进模型");
    when(model.generate(any(), any(), any())).thenReturn(wrong, repaired);
    workflow(false, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, times(2)).generate(any(), any(), any());
    verify(model)
        .generate(
            argThat(
                c ->
                    c.path("generation_feedback")
                        .asText()
                        .contains("DEPENDENCY_PRECONDITION_UNMET")),
            any(),
            any());
    verify(backend).finalizeRun(any(), any());
  }

  @Test
  void exhaustedPlanningBudgetReturnsNeedsInputRatherThanSpendingThirdCall() {
    when(backend.context(any())).thenReturn(ConstraintAwarePlanningTest.context());
    var wrong = Fixtures.answer();
    wrong.withObject("/result").putArray("next_steps").addObject().put("title", "改进模型");
    when(model.generate(any(), any(), any())).thenReturn(wrong);
    workflow(true, Duration.ofSeconds(1)).execute(Fixtures.RUN);
    verify(model, times(1)).generate(any(), any(), any());
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                result ->
                    result.at("/result/outcome").asText().equals("needs_input")
                        && result.at("/result/next_steps").isEmpty()));
  }
}
