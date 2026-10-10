package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.agent.backend.BackendClient;
import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.ModelGateway;
import cn.yanji.agent.workflow.BusinessPolicy;
import cn.yanji.agent.workflow.RunWorkflow;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class WorkflowTest {
  @Test
  void reportFeedbackReadsEveryReportSectionEvenWhenSearchHasNoKeywordHits() {
    var c = Fixtures.context("risk_brief");
    c.withObject("/input").put("report_id", Fixtures.STAGE);
    c.putObject("weekly_report")
        .put("id", Fixtures.STAGE)
        .put("status", "submitted")
        .put("source_id", Fixtures.SOURCE)
        .put("source_revision", 1)
        .put("current_revision", 2);
    when(backend.context(any())).thenReturn(c);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              var response =
                  Fixtures.JSON
                      .createObjectNode()
                      .put("source_id", Fixtures.SOURCE)
                      .put("source_revision", 1)
                      .put("excerpt", "导师希望先核对量纲");
              response.set("locator", inv.getArgument(3));
              return response;
            });
    var answer = Fixtures.answer();
    answer.withObject("/result").withArray("claims").removeAll();
    when(model.generate(any(), any(), any())).thenReturn(answer);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model).generate(any(), argThat(e -> e.size() == 4), any());
    for (String field : java.util.List.of("done_items", "issues", "next_plan", "help_needed"))
      verify(backend)
          .source(
              any(),
              eq(Fixtures.SOURCE),
              eq(1),
              argThat(l -> l.path("field").asText().equals(field)));
    verify(backend, never()).search(any(), any());
  }

  @Test
  void staleTaskSnapshotStopsBeforeModelAndCannotFinalize() {
    when(backend.context(any())).thenThrow(new AgentFailure("CONTEXT_STALE", "任务已经更新", false));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend, never()).search(any(), any());
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend).fail(any(), eq("CONTEXT_STALE"), anyString(), eq(false));
  }

  @Test
  void paperSearchStatisticsReachModelWithoutCountingRepeatedChunksTwice() {
    var context = Fixtures.context("paper_guide");
    context.set(
        "paper",
        Fixtures.object("{\"source_id\":\"" + Fixtures.SOURCE + "\",\"title\":\"fixture\"}"));
    when(backend.context(any())).thenReturn(context);
    var search =
        Fixtures.object(
            "{\"eligible_chunks\":20,\"returned_chunks\":1,\"coverage_complete\":false}");
    search.set("chunks", Fixtures.evidence());
    when(backend.search(any(), any())).thenReturn(search);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model)
        .generate(
            argThat(
                c ->
                    c.at("/retrieval_coverage/included_chunks").asInt() == 1
                        && c.at("/retrieval_coverage/eligible_chunks").asInt() == 20
                        && !c.at("/retrieval_coverage/coverage_complete").asBoolean()),
            any(),
            any());
  }

  @Test
  void paperGuideSearchesEnglishBodyBeforeGenerating() {
    var context = Fixtures.context("paper_guide");
    context.set(
        "paper",
        Fixtures.object("{\"source_id\":\"" + Fixtures.SOURCE + "\",\"title\":\"QA fixture\"}"));
    ((ObjectNode) context.path("input")).removeAll();
    when(backend.context(any())).thenReturn(context);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(backend)
        .search(
            any(),
            argThat(
                q ->
                    q.path("query")
                        .asText()
                        .equals("Research question Method input Result Limitation")));
  }

  private ObjectNode weeklyContext() {
    var context = Fixtures.context("weekly_report");
    ((ObjectNode) context.path("input")).put("week_start", "2026-10-05");
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1,\"confirmed_at\":\"2026-10-05T08:00:00+08:00\"}"));
    return context;
  }

  @Test
  void weeklyRejectsSourceLocatorMismatchBeforeProviderCall() {
    when(backend.context(any())).thenReturn(weeklyContext());
    // Default mock returns a pdf_page instead of the requested record_field.
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend).fail(any(), eq("SOURCE_STALE"), anyString(), eq(false));
    verify(backend, never()).finalizeRun(any(), any());
  }

  @Test
  void weeklyRejectsSourceOutsideSnapshotBeforeReadingOrCharging() {
    var context = weeklyContext();
    context.withArray("source_versions").removeAll();
    when(backend.context(any())).thenReturn(context);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend, never()).source(any(), anyString(), anyInt(), any());
    verify(backend).fail(any(), eq("BACKEND_PROTOCOL_INVALID"), anyString(), eq(false));
  }

  @Test
  void weeklyOversizeIsExplicitRatherThanSilentlyDroppingRecords() {
    var context = weeklyContext();
    var row = context.path("confirmed_experiments").get(0).deepCopy();
    for (int i = 0; i < 10; i++) context.withArray("confirmed_experiments").add(row.deepCopy());
    when(backend.context(any())).thenReturn(context);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend, never()).source(any(), anyString(), anyInt(), any());
    verify(backend).fail(any(), eq("CONTEXT_TOO_LARGE"), anyString(), eq(false));
  }

  @Test
  void weeklyReadsConfirmedRecordFieldEvenWhenBroadSearchMissesIt() {
    var context = Fixtures.context("weekly_report");
    ((ObjectNode) context.path("input")).put("week_start", "2026-10-05");
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1,\"confirmed_at\":\"2026-10-05T08:00:00+08:00\"}"));
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              var source =
                  Fixtures.JSON
                      .createObjectNode()
                      .put("source_id", Fixtures.SOURCE)
                      .put("source_revision", 1)
                      .put("excerpt", "实验结果为10，尚未重复验证。");
              source.set("locator", inv.getArgument(3));
              return source;
            });
    var answer = Fixtures.answer();
    var ref = (ObjectNode) answer.at("/result/claims/0/source_refs/0");
    ref.putNull("chunk_id");
    ref.set("locator", Fixtures.object("{\"kind\":\"record_field\",\"field\":\"results\"}"));
    when(model.generate(any(), any(), any())).thenReturn(answer);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model)
        .generate(
            any(),
            argThat(
                rows ->
                    rows.size() == 1
                        && rows.get(0).at("/locator/field").asText().equals("results")),
            any());
    verify(backend, times(2)).source(any(), eq(Fixtures.SOURCE), eq(1), any());
    verify(backend)
        .finalizeRun(any(), argThat(body -> body.at("/result/outcome").asText().equals("ok")));
    verify(backend, never()).search(any(), any());
  }

  @Test
  void riskRulesRemainVisibleWhenSearchHasNoScientificEvidence() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"task-a\",\"version\":2,\"status\":\"blocked\",\"due_at\":\"2026-10-04T00:00:00Z\"}"));
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                body ->
                    body.at("/result/summary").asText().contains("已逾期")
                        && body.at("/result/summary").asText().contains("遇到困难")
                        && !body.at("/result/summary").asText().contains("task-a")
                        && body.at("/result/claims").isEmpty()
                        && body.at("/result/outcome").asText().equals("ok")));
  }

  @Test
  void blockedTasksExplainOnlyVisibleUnfinishedDependenciesWithoutLiterature() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"task-a\",\"title\":\"撰写结论\",\"version\":2,\"status\":\"blocked\","
                    + "\"dependency_ids\":[\"pending\",\"done\",\"hidden\"]}"));
    context
        .withArray("tasks")
        .add(Fixtures.object("{\"id\":\"pending\",\"title\":\"核对参数\",\"status\":\"submitted\"}"));
    context
        .withArray("tasks")
        .add(Fixtures.object("{\"id\":\"done\",\"title\":\"已验收阅读\",\"status\":\"completed\"}"));
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                body -> {
                  String summary = body.at("/result/summary").asText();
                  return summary.contains("核对参数")
                      && summary.contains("验收")
                      && summary.contains("不在当前可查看范围")
                      && !summary.contains("已验收阅读")
                      && !summary.contains("hidden")
                      && body.at("/result/outcome").asText().equals("ok")
                      && body.path("proposals").isEmpty();
                }));
  }

  @Test
  void riskReadsOnlyConfirmedRecordsAttachedToRiskyTasks() {
    var context = Fixtures.context("risk_brief");
    context
        .withArray("tasks")
        .add(
            Fixtures.object(
                "{\"id\":\"blocked-task\",\"title\":\"台灯费用\",\"version\":2,\"status\":\"blocked\"}"));
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"task_id\":\"blocked-task\",\"source_id\":\""
                    + Fixtures.SOURCE
                    + "\",\"source_revision\":1}"));
    context
        .withArray("confirmed_experiments")
        .add(
            Fixtures.object(
                "{\"task_id\":\"unrelated-task\",\"source_id\":\"other-source\",\"source_revision\":1}"));
    when(backend.context(any())).thenReturn(context);
    when(backend.search(any(), any())).thenReturn(Fixtures.object("{\"chunks\":[]}"));
    when(backend.source(any(), eq(Fixtures.SOURCE), eq(1), any()))
        .thenAnswer(
            inv -> {
              var source =
                  Fixtures.JSON
                      .createObjectNode()
                      .put("source_id", Fixtures.SOURCE)
                      .put("source_revision", 1)
                      .put("excerpt", "费用为0.72元，真实电价尚未确认。");
              source.set("locator", inv.getArgument(3));
              return source;
            });
    when(model.generate(any(), any(), any())).thenReturn(Fixtures.answer());
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model, atLeastOnce())
        .generate(
            any(),
            argThat(
                rows -> rows.size() == 1 && rows.get(0).path("text").asText().contains("真实电价尚未确认")),
            any());
    verify(backend, never()).source(any(), eq("other-source"), anyInt(), any());
  }

  BackendClient backend = mock(BackendClient.class);
  ModelGateway model = mock(ModelGateway.class);
  ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1);
  ExecutorService models = Executors.newFixedThreadPool(1);
  Clock clock = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

  RunWorkflow workflow(Duration interval) {
    return workflow(interval, Duration.ofSeconds(3));
  }

  RunWorkflow workflow(Duration interval, Duration budget) {
    return new RunWorkflow(
        backend,
        model,
        new OutputGuard(Fixtures.JSON, clock),
        new BusinessPolicy(Fixtures.JSON, clock),
        Fixtures.JSON,
        heartbeats,
        models,
        interval,
        budget,
        "test-worker",
        new SimpleMeterRegistry());
  }

  @BeforeEach
  void setup() {
    when(backend.claim(Fixtures.RUN, "test-worker"))
        .thenReturn(
            Fixtures.object("{\"status\":\"running\",\"attempt\":1,\"lease_token\":\"secret\"}"));
    when(backend.context(any())).thenReturn(Fixtures.context("research_qa"));
    ObjectNode search = Fixtures.JSON.createObjectNode();
    search.set("chunks", Fixtures.evidence());
    search.put("retrieval_mode", "lexical");
    when(backend.search(any(), any())).thenReturn(search);
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              ObjectNode source =
                  Fixtures.JSON
                      .createObjectNode()
                      .put("source_id", Fixtures.SOURCE)
                      .put("source_revision", 1)
                      .put("excerpt", "实验结果为10，尚未重复验证。");
              source.set("locator", Fixtures.reference().path("locator"));
              return source;
            });
    when(model.generate(any(), any(), any())).thenReturn(Fixtures.answer());
  }

  @AfterEach
  void cleanup() {
    heartbeats.shutdownNow();
    models.shutdownNow();
  }

  @Test
  void terminalClaimSkipsModelAndCallbacks() {
    when(backend.claim(anyString(), anyString()))
        .thenReturn(Fixtures.object("{\"status\":\"succeeded\",\"attempt\":1}"));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend, never()).finalizeRun(any(), any());
  }

  @Test
  void noEvidenceFinalizesWithoutChargingModel() {
    when(backend.search(any(), any()))
        .thenReturn(Fixtures.object("{\"chunks\":[],\"retrieval_mode\":\"lexical\"}"));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend)
        .finalizeRun(
            any(),
            argThat(body -> "insufficient_evidence".equals(body.at("/result/outcome").asText())));
  }

  @Test
  void checksEvidenceAgainstCurrentSourceBeforeFinalize() {
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(backend).source(any(), eq(Fixtures.SOURCE), eq(1), any());
    verify(backend).finalizeRun(any(), argThat(body -> body.path("attempt").asInt() == 1));
  }

  @Test
  void lostLeaseDuringModelStopsOldAttempt() throws Exception {
    doThrow(new AgentFailure("LEASE_EXPIRED", "过期", false, true)).when(backend).heartbeat(any());
    when(model.generate(any(), any(), any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(1000);
              return Fixtures.answer();
            });
    workflow(Duration.ofMillis(20)).execute(Fixtures.RUN);
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void invalidModelOutputFailsWithStableCode() {
    when(model.generate(any(), any(), any())).thenReturn(Fixtures.object("{\"bad\":true}"));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(backend).fail(any(), eq("MODEL_OUTPUT_INVALID"), anyString(), eq(false));
  }

  @Test
  void invalidFirstGenerationRegeneratesOnceAndOnlyFinalizesValidatedOutput() {
    ObjectNode invalid = Fixtures.answer();
    ((ObjectNode) invalid.at("/result/claims/0")).putArray("source_refs");
    when(model.generate(any(), any(), any())).thenReturn(invalid, Fixtures.answer());
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model, times(2)).generate(any(), any(), any());
    var inputs = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
    verify(model, times(2)).generate(inputs.capture(), any(), any());
    assertThat(inputs.getAllValues().get(0).has("generation_feedback")).isFalse();
    assertThat(inputs.getAllValues().get(1).path("generation_feedback").asText())
        .contains("事实条目缺少引用", "300", "source_id");
    verify(backend)
        .finalizeRun(any(), argThat(b -> !b.at("/result/claims/0/source_refs").isEmpty()));
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void persistentlyInvalidGenerationStopsAfterOneRegeneration() {
    ObjectNode invalid = Fixtures.answer();
    ((ObjectNode) invalid.at("/result/claims/0")).putArray("source_refs");
    when(model.generate(any(), any(), any())).thenReturn(invalid);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model, times(2)).generate(any(), any(), any());
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend).fail(any(), eq("MODEL_OUTPUT_INVALID"), anyString(), eq(false));
  }

  @Test
  void regenerationSharesOriginalTimeBudget() {
    ObjectNode invalid = Fixtures.answer();
    ((ObjectNode) invalid.at("/result/claims/0")).putArray("source_refs");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(model.generate(any(), any(), any()))
        .thenAnswer(
            inv -> {
              int call = calls.incrementAndGet();
              Thread.sleep(300);
              return call == 1 ? invalid : Fixtures.answer();
            });
    workflow(Duration.ofSeconds(30), Duration.ofMillis(500)).execute(Fixtures.RUN);
    verify(model, times(2)).generate(any(), any(), any());
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend).fail(any(), eq("RUN_BUDGET_EXCEEDED"), anyString(), eq(true));
  }

  @Test
  void lostLeaseDuringRegenerationPreventsAllCallbacks() {
    ObjectNode invalid = Fixtures.answer();
    ((ObjectNode) invalid.at("/result/claims/0")).putArray("source_refs");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(model.generate(any(), any(), any()))
        .thenAnswer(
            inv -> {
              if (calls.incrementAndGet() == 1) return invalid;
              Thread.sleep(1000);
              return Fixtures.answer();
            });
    doAnswer(
            inv -> {
              if (calls.get() >= 2) throw new AgentFailure("LEASE_EXPIRED", "过期", false, true);
              return null;
            })
        .when(backend)
        .heartbeat(any());
    workflow(Duration.ofMillis(20)).execute(Fixtures.RUN);
    verify(model, times(2)).generate(any(), any(), any());
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void providerAuthenticationFailureDoesNotRegenerate() {
    when(model.generate(any(), any(), any()))
        .thenThrow(new AgentFailure("MODEL_AUTH_FAILED", "认证失败", false));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(model, times(1)).generate(any(), any(), any());
    verify(backend).fail(any(), eq("MODEL_AUTH_FAILED"), anyString(), eq(false));
  }

  @Test
  void weeklyContextWithoutPeriodTimestampsNeedsInput() {
    ObjectNode context = Fixtures.context("weekly_report");
    ((ObjectNode) context.path("input")).put("week_start", "2026-10-05");
    context.withArray("confirmed_experiments").add(Fixtures.object("{\"results\":\"历史结果\"}"));
    when(backend.context(any())).thenReturn(context);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend)
        .finalizeRun(
            any(), argThat(body -> "needs_input".equals(body.at("/result/outcome").asText())));
  }

  @Test
  void missingStageRulesNeedsInput() {
    when(backend.context(any())).thenReturn(Fixtures.context("stage_plan"));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend)
        .finalizeRun(any(), argThat(b -> "needs_input".equals(b.at("/result/outcome").asText())));
  }

  @Test
  void ambiguousFinalizeDoesNotOverwriteWithFail() {
    doThrow(new AgentFailure("BACKEND_UNAVAILABLE", "响应丢失", true))
        .when(backend)
        .finalizeRun(any(), any());
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void sourceRevocationPreventsResultWrite() {
    when(backend.source(any(), anyString(), anyInt(), any()))
        .thenThrow(new AgentFailure("SCOPE_REVOKED", "撤销", false));
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verify(backend, never()).finalizeRun(any(), any());
    verify(backend).fail(any(), eq("SCOPE_REVOKED"), anyString(), eq(false));
  }

  @Test
  void outOfSnapshotSearchResultsNeverReachExternalModel() {
    var c = Fixtures.context("research_qa");
    c.withArray("source_versions").removeAll();
    when(backend.context(any())).thenReturn(c);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend)
        .finalizeRun(
            any(), argThat(b -> "insufficient_evidence".equals(b.at("/result/outcome").asText())));
  }

  @Test
  void healthyRiskSnapshotFinalizesWithoutSearchOrModelCalls() {
    var c = Fixtures.context("risk_brief");
    c.withArray("tasks")
        .add(Fixtures.object("{\"id\":\"healthy\",\"status\":\"in_progress\",\"due_at\":null}"));
    when(backend.context(any())).thenReturn(c);
    workflow(Duration.ofSeconds(30)).execute(Fixtures.RUN);
    verifyNoInteractions(model);
    verify(backend, never()).search(any(), any());
    verify(backend)
        .finalizeRun(
            any(),
            argThat(
                body ->
                    body.at("/result/outcome").asText().equals("ok")
                        && body.at("/result/summary").asText().contains("没有发现任务卡住或超过截止日期")));
    verify(backend, never()).fail(any(), anyString(), anyString(), anyBoolean());
  }
}
