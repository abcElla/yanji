package cn.yanji.agent.workflow;

import cn.yanji.agent.backend.*;
import cn.yanji.agent.contract.*;
import cn.yanji.agent.model.ModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One attempt owns one scope and one lease. No business writes or user approval tools. */
public final class RunWorkflow {
  private static final Logger LOG = LoggerFactory.getLogger(RunWorkflow.class);
  private final BackendClient backend;
  private final ModelGateway model;
  private final OutputGuard guard;
  private final BusinessPolicy policy;
  private final ObjectMapper json;
  private final ScheduledExecutorService heartbeats;
  private final ExecutorService models;
  private final Duration heartbeatInterval, budget;
  private final String workerId;
  private final MeterRegistry metrics;
  private final boolean researchLoopEnabled;
  private final Duration planningBudget;
  private final ExecutorService planners;

  public RunWorkflow(
      BackendClient backend,
      ModelGateway model,
      OutputGuard guard,
      BusinessPolicy policy,
      ObjectMapper json,
      ScheduledExecutorService heartbeats,
      ExecutorService models,
      Duration heartbeatInterval,
      Duration budget,
      String workerId,
      MeterRegistry metrics) {
    this(
        backend,
        model,
        guard,
        policy,
        json,
        heartbeats,
        models,
        heartbeatInterval,
        budget,
        workerId,
        metrics,
        false,
        Duration.ofSeconds(10),
        models);
  }

  public RunWorkflow(
      BackendClient backend,
      ModelGateway model,
      OutputGuard guard,
      BusinessPolicy policy,
      ObjectMapper json,
      ScheduledExecutorService heartbeats,
      ExecutorService models,
      Duration heartbeatInterval,
      Duration budget,
      String workerId,
      MeterRegistry metrics,
      boolean researchLoopEnabled,
      Duration planningBudget,
      ExecutorService planners) {
    this.backend = backend;
    this.model = model;
    this.guard = guard;
    this.policy = policy;
    this.json = json;
    this.heartbeats = heartbeats;
    this.models = models;
    this.heartbeatInterval = heartbeatInterval;
    this.budget = budget;
    this.workerId = workerId;
    this.metrics = metrics;
    this.researchLoopEnabled = researchLoopEnabled;
    this.planningBudget = planningBudget;
    this.planners = planners;
    if (planningBudget.isNegative()
        || planningBudget.isZero()
        || planningBudget.compareTo(Duration.ofSeconds(12)) > 0)
      throw new IllegalArgumentException("Planning budget must be (0,12s]");
  }

  public void execute(String runId) {
    long start = System.nanoTime(), deadline = start + budget.toNanos();
    Lease lease = null;
    ScheduledFuture<?> heartbeat = null;
    AtomicReference<AgentFailure> leaseFailure = new AtomicReference<>();
    boolean finalizing = false;
    String kind = "unknown", outcome = "failed";
    try {
      ObjectNode claim = backend.claim(runId, workerId);
      if (!"running".equals(claim.path("status").asText())) {
        outcome = "skipped";
        return;
      }
      if (claim.path("attempt").asInt() < 1 || claim.path("lease_token").asText().isBlank())
        throw new AgentFailure("BACKEND_PROTOCOL_INVALID", "认领响应缺少有效租约", false);
      lease = new Lease(runId, claim.path("attempt").asInt(), claim.path("lease_token").asText());
      Lease owned = lease;
      heartbeat =
          heartbeats.scheduleWithFixedDelay(
              () -> {
                try {
                  backend.heartbeat(owned);
                } catch (AgentFailure e) {
                  leaseFailure.compareAndSet(null, e);
                } catch (RuntimeException e) {
                  leaseFailure.compareAndSet(
                      null, new AgentFailure("HEARTBEAT_FAILED", "无法确认运行租约", true, true));
                }
              },
              heartbeatInterval.toMillis(),
              heartbeatInterval.toMillis(),
              TimeUnit.MILLISECONDS);
      ObjectNode context = ConstraintAwarePlanning.prepare(backend.context(lease));
      if (!runId.equals(context.path("run_id").asText()))
        throw new AgentFailure("BACKEND_PROTOCOL_INVALID", "上下文运行ID不匹配", false);
      kind =
          BusinessPolicy.KINDS.contains(context.path("kind").asText())
              ? context.path("kind").asText()
              : "unknown";
      backend.event(lease, "context", "context", "completed", "已读取授权上下文");
      check(deadline, leaseFailure);
      boolean adviceMode = !guard.contentValidationEnabled();
      if (adviceMode) context.put("advice_mode", true);
      ObjectNode draft = policy.preflight(context, adviceMode);
      ArrayNode ruleRisks = policy.risks(context);
      boolean loop =
          researchLoopEnabled
              && Set.of("research_qa", "stage_plan", "research_followup").contains(kind);
      ModelCallBudget calls = new ModelCallBudget(2);
      boolean retrievalDegraded = false;
      boolean generated = false;
      boolean taskStateOnly = false;
      ArrayNode evidence = json.createArrayNode();
      if (draft == null) {
        if (loop) {
          ResearchPlan plan;
          calls.reserve();
          backend.event(lease, "plan", "plan", "running", "正在选择受限证据检索需求");
          try {
            plan = plan(context, deadline, leaseFailure);
            SemanticFeedback.attach(context, plan.feedbackInterpretations());
            check(deadline, leaseFailure);
            backend.event(
                lease,
                "plan-completed",
                "plan",
                "completed",
                "已确定" + plan.queries().size() + "类证据检索需求");
          } catch (AgentFailure failure) {
            check(deadline, leaseFailure);
            if (failure.ownershipLost()
                || Set.of("MODEL_AUTH_FAILED", "MODEL_ACCESS_DENIED", "WORKER_INTERRUPTED")
                    .contains(failure.code())) throw failure;
            plan = fallbackPlan(context);
            backend.event(lease, "plan-degraded", "plan", "completed", "规划未完成，已降级为受限关键词检索");
            metrics.counter("yanji.agent.planning.fallback", "kind", kind).increment();
          }
          var found =
              new EvidenceRetriever(json, policy, backend)
                  .retrieve(plan, context, lease, () -> check(deadline, leaseFailure));
          evidence = found.chunks();
          retrievalDegraded = found.lexicalFallback();
          metrics.counter("yanji.agent.search.calls", "kind", kind).increment(found.searches());
        } else evidence = retrieve(lease, context, deadline, leaseFailure);
        backend.event(lease, "retrieve", "retrieve", "completed", "检索并筛选授权证据");
        if (!adviceMode
            && evidence.isEmpty()
            && "risk_brief".equals(kind)
            && context.at("/input/report_id").asText().isBlank()
            && !ruleRisks.isEmpty()) {
          draft = policy.taskRiskSummary(context);
          taskStateOnly = true;
        } else if (!adviceMode
            && evidence.isEmpty()
            && !"research_followup".equals(kind)
            && !(loop && "stage_plan".equals(kind)))
          draft =
              policy.fallback(
                  "insufficient_evidence", "当前授权资料未检索到足够证据，暂无法给出可核验结论。", "与当前问题相关的已授权来源");
        else if (guard.contentValidationEnabled()
            && "stage_plan".equals(kind)
            && !ResearchOutputPolicy.rulesLocated(context, evidence)) {
          draft =
              policy.fallback(
                  "needs_input",
                  "导师阶段规则缺少当前可引用来源，暂不生成任务建议。",
                  "导师规则的source_id/source_revision与可检索规则原文");
        } else {
          if (!loop
              && SemanticFeedback.enabled(context)
              && !context.path("feedback_memories").isEmpty()) {
            calls.reserve();
            backend.event(lease, "feedback-extract", "plan", "running", "正在核对反馈原文并整理修改要求");
            try {
              SemanticFeedback.attach(context, interpretFeedback(context, deadline, leaseFailure));
            } catch (AgentFailure failure) {
              if (failure.ownershipLost()
                  || Set.of("MODEL_AUTH_FAILED", "MODEL_ACCESS_DENIED", "WORKER_INTERRUPTED")
                      .contains(failure.code())) throw failure;
              context.putArray("feedback_interpretations");
              context.withObject("/learning_policy").put("semantic_feedback_status", "unavailable");
              backend.event(
                  lease, "feedback-extract-degraded", "plan", "completed", "反馈语义提取未完成，本次仅保留原文指导");
            }
          }
          backend.event(lease, "generate", "generate", "running", "正在生成带来源的科研建议");
          calls.reserve();
          draft =
              generate(
                  context,
                  loop ? EvidenceRetriever.modelView(evidence) : evidence,
                  ruleRisks,
                  deadline,
                  leaseFailure);
          generated = true;
        }
      }
      check(deadline, leaseFailure);
      context = GroundedGuidance.prepare(context, evidence);
      draft = GroundedGuidance.publish(context, draft);
      var preconditionErrors =
          new java.util.ArrayList<>(ConstraintAwarePlanning.violations(context, draft));
      preconditionErrors.addAll(GroundedGuidance.violations(context, draft));
      if (!preconditionErrors.isEmpty()) {
        metrics
            .counter("yanji.agent.constraint.violations", "kind", kind)
            .increment(preconditionErrors.size());
        backend.event(lease, "constraint-feedback", "validate", "running", "计划前置条件未满足，正在调整任务顺序");
        if (generated && calls.remaining() > 0) {
          calls.reserve();
          draft =
              generate(
                  ConstraintAwarePlanning.repairContext(context, preconditionErrors),
                  evidence,
                  ruleRisks,
                  deadline,
                  leaseFailure);
          check(deadline, leaseFailure);
          draft = GroundedGuidance.publish(context, draft);
          preconditionErrors =
              new java.util.ArrayList<>(ConstraintAwarePlanning.violations(context, draft));
          preconditionErrors.addAll(GroundedGuidance.violations(context, draft));
        }
        if (!preconditionErrors.isEmpty()) {
          draft =
              adviceMode
                  ? GuidancePublication.forReview(context, draft, preconditionErrors)
                  : GroundedGuidance.fallback(context, preconditionErrors);
          generated = false;
          metrics.counter("yanji.agent.constraint.fallback", "kind", kind).increment();
        }
      }
      ObjectNode result;
      try {
        result = guard.prepare(draft, context, evidence, lease.attempt());
        if (loop && guard.contentValidationEnabled())
          ResearchOutputPolicy.validate(result, context);
      } catch (AgentFailure invalid) {
        if (invalid instanceof UnsupportedRequirementEvidence) {
          result = requirementNeedsInput(context, evidence, lease);

        } else {
          if (!generated
              || !"MODEL_OUTPUT_INVALID".equals(invalid.code())
              || calls.remaining() == 0) throw invalid;
          // One fresh generation, using only the original authorized inputs. Never invent refs,
          // replay rejected model text, reset the deadline, or retry business callbacks here.
          check(deadline, leaseFailure);
          backend.event(lease, "regenerate", "generate", "running", "输出未通过校验，重新生成一次");
          metrics.counter("yanji.agent.regeneration", "kind", kind).increment();
          calls.reserve();
          ObjectNode repairContext = context.deepCopy();
          repairContext.put(
              "generation_feedback",
              "上次输出被程序拒绝："
                  + invalid.getMessage()
                  + "。重新生成完整JSON，不复用上次输出。每个quote必须是evidence.text中的连续原文，"
                  + "最多120字符且绝不超过300字符；source_id、source_revision、chunk_id、locator"
                  + "均逐字照抄同一evidence对象。不能提供有效引用的claims或proposal直接省略，"
                  + "不得补造来源或更改授权范围。建议外层source_refs不可为空。");
          draft = generate(repairContext, evidence, ruleRisks, deadline, leaseFailure);
          check(deadline, leaseFailure);
          draft = GroundedGuidance.publish(context, draft);
          var repairedErrors =
              new java.util.ArrayList<>(ConstraintAwarePlanning.violations(context, draft));
          repairedErrors.addAll(GroundedGuidance.violations(context, draft));
          if (!repairedErrors.isEmpty())
            draft =
                adviceMode
                    ? GuidancePublication.forReview(context, draft, repairedErrors)
                    : GroundedGuidance.fallback(context, repairedErrors);
          try {
            result = guard.prepare(draft, context, evidence, lease.attempt());
          } catch (UnsupportedRequirementEvidence unsupported) {
            result = requirementNeedsInput(context, evidence, lease);
          }
          if (loop && guard.contentValidationEnabled())
            ResearchOutputPolicy.validate(result, context);
        }
      }
      // The display guard replaces free-form model summaries. Restore only program-generated
      // task dependency text after that guard; never restore a model's unverified explanation.
      if (taskStateOnly)
        ((ObjectNode) result.path("result"))
            .put("summary", policy.taskRiskSummary(context).at("/result/summary").asText());
      policy.retainRuleObservations(result, ruleRisks);
      if (loop && retrievalDegraded)
        ((ObjectNode) result.path("result"))
            .withArray("limitations")
            .add("语义检索暂不可用，本次使用后端提供的关键词检索证据。");
      revalidateSources(result, lease, deadline, leaseFailure);
      backend.event(
          lease,
          "validate",
          "validate",
          "completed",
          guard.contentValidationEnabled() ? "输出结构、引用与建议范围已校验" : "模型建议已整理，可以查看");
      check(deadline, leaseFailure);
      backend.heartbeat(lease); // final ownership check, including cancellation
      check(deadline, leaseFailure);
      finalizing = true;
      backend.finalizeRun(lease, result);
      outcome = result.at("/result/outcome").asText();
      LOG.info(
          "run={} attempt={} kind={} outcome={} model_calls_reserved={} research_loop={}",
          runId,
          lease.attempt(),
          kind,
          outcome,
          2 - calls.remaining(),
          loop);
    } catch (AgentFailure failure) {
      outcome = failure.ownershipLost() ? "ownership_lost" : "failed";
      LOG.warn(
          "run={} attempt={} code={}", runId, lease == null ? 0 : lease.attempt(), failure.code());
      // A transient finalize failure is ambiguous: never replace its potentially committed result
      // with fail.
      if (lease != null
          && !failure.ownershipLost()
          && leaseFailure.get() == null
          && !(finalizing && failure.retryable())) {
        try {
          backend.fail(lease, failure.code(), failure.getMessage(), failure.retryable());
        } catch (AgentFailure callback) {
          LOG.warn("run={} failure_callback_code={}", runId, callback.code());
        }
      }
    } catch (RuntimeException failure) {
      LOG.error("run={} code=AGENT_EXECUTION_FAILED", runId);
      if (lease != null && !finalizing && leaseFailure.get() == null) {
        try {
          backend.fail(lease, "AGENT_EXECUTION_FAILED", "Agent内部处理失败", true);
        } catch (RuntimeException ignored) {
          LOG.warn("run={} code=FAIL_CALLBACK_UNAVAILABLE", runId);
        }
      }
    } finally {
      if (heartbeat != null) heartbeat.cancel(false);
      metrics
          .timer("yanji.agent.run", "kind", kind, "outcome", outcome)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  private ObjectNode requirementNeedsInput(ObjectNode context, ArrayNode evidence, Lease lease) {
    backend.event(
        lease, "requirement-needs-input", "validate", "completed", "数量要求缺少可核验来源，请先补充导师要求或原文");
    return guard.prepare(
        policy.fallback(
            "needs_input", "暂时无法确认这项数量要求。请先补充导师的明确要求或对应原文，再继续分析。", "支持数量或重复次数要求的导师指导或原文"),
        context,
        evidence,
        lease.attempt());
  }

  private ObjectNode generate(
      ObjectNode context,
      ArrayNode evidence,
      ArrayNode ruleRisks,
      long deadline,
      AtomicReference<AgentFailure> leaseFailure) {
    check(deadline, leaseFailure);
    ObjectNode modelContext = GroundedGuidance.prepare(context, evidence);
    for (String field :
        List.of(
            "requested_by",
            "lab_id",
            "student_id",
            "run_id",
            "snapshot_id",
            "business_versions",
            "source_versions")) modelContext.remove(field);
    Future<ObjectNode> generation =
        models.submit(
            () -> {
              try (var scope =
                  org.slf4j.MDC.putCloseable("run_id", context.path("run_id").asText())) {
                return model.generate(modelContext, evidence, ruleRisks);
              }
            });
    try {
      while (true) {
        check(deadline, leaseFailure);
        try {
          return generation.get(
              Math.min(
                  100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))),
              TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          /* Poll ownership and the original deadline on both generations. */
        }
      }
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AgentFailure failure) throw failure;
      throw new AgentFailure("MODEL_UNAVAILABLE", "模型生成失败", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AgentFailure("WORKER_INTERRUPTED", "运行已中断", true, true);
    } finally {
      if (!generation.isDone()) generation.cancel(true);
    }
  }

  private ResearchPlan plan(
      ObjectNode context, long deadline, AtomicReference<AgentFailure> failures) {
    check(deadline, failures);
    long softDeadline = Math.min(deadline, System.nanoTime() + planningBudget.toNanos());
    Future<ResearchPlan> future;
    try {
      future =
          planners.submit(
              () -> {
                try (var scope =
                    org.slf4j.MDC.putCloseable("run_id", context.path("run_id").asText())) {
                  return model.plan(PlanningContext.build(context, json));
                }
              });
    } catch (RejectedExecutionException busy) {
      throw new AgentFailure("PLANNER_BUSY", "规划通道繁忙", true);
    }
    try {
      while (true) {
        check(deadline, failures);
        long remaining = softDeadline - System.nanoTime();
        if (remaining <= 0) throw new AgentFailure("PLANNER_TIMEOUT", "规划超过短时预算", true);
        try {
          return new ResearchPlanGuard()
              .parse(
                  json.valueToTree(
                      future.get(
                          Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))),
                          TimeUnit.MILLISECONDS)));
        } catch (TimeoutException waiting) {
          /* Keep checking ownership. */
        }
      }
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof AgentFailure failure) throw failure;
      throw new AgentFailure("PLANNER_UNAVAILABLE", "规划未完成", true);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AgentFailure("WORKER_INTERRUPTED", "运行已中断", true, true);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  private ResearchPlan fallbackPlan(ObjectNode context) {
    String query = context.at("/input/question").asText();
    if (query.isBlank()) query = context.at("/stage/title").asText();
    if (query.isBlank()) query = "实验 结果 方法 问题 研究";
    // A code-controlled fallback is data for search, never a tool instruction.
    return new ResearchPlan(
        "受限关键词降级",
        List.of(
            new ResearchPlan.Query(query.substring(0, Math.min(120, query.length())), "原问题关键词")));
  }

  private ArrayNode retrieve(
      Lease lease, ObjectNode context, long deadline, AtomicReference<AgentFailure> leaseFailure) {
    if ("weekly_report".equals(context.path("kind").asText()))
      return retrieveWeeklyFacts(lease, context, deadline, leaseFailure);
    if ("risk_brief".equals(context.path("kind").asText())
        && !context.at("/input/report_id").asText().isBlank())
      return retrieveReportFacts(lease, context, deadline, leaseFailure);
    if ("risk_brief".equals(context.path("kind").asText())) {
      Set<String> riskyTasks = new HashSet<>();
      for (JsonNode risk : policy.risks(context)) riskyTasks.add(risk.path("task_id").asText());
      ObjectNode scoped = context.deepCopy();
      for (String group : List.of("confirmed_experiments", "confirmed_notes")) {
        ArrayNode records = json.createArrayNode();
        for (JsonNode row : context.path(group)) {
          if (riskyTasks.contains(row.path("task_id").asText())) records.add(row.deepCopy());
        }
        scoped.set(group, records);
      }
      return retrieveWeeklyFacts(lease, scoped, deadline, leaseFailure);
    }
    ArrayNode chunks = json.createArrayNode();
    Set<String> seen = new HashSet<>();
    String query = context.at("/input/question").asText();
    if (query.isBlank()) query = context.at("/stage/title").asText();
    if (query.isBlank()) query = context.at("/paper/title").asText();
    if (query.isBlank()) query = "实验 结果 方法 问题 研究";
    List<String> queries = new ArrayList<>();
    boolean paperGuide = "paper_guide".equals(context.path("kind").asText());
    boolean statsAvailable = true, coverageComplete = true;
    int eligibleChunks = 0;
    queries.add(query.substring(0, Math.min(500, query.length())));
    // Current backend is lexical. One bounded broad query helps Chinese full-sentence inputs
    // without changing ACL.
    if (!query.equals("实验 结果 方法 问题 研究")) queries.add("实验 结果 方法 问题 研究");
    // English papers may have an irrelevant title-only lexical hit. Include body terms,
    // retaining the same per-search and aggregate evidence limits and source scope.
    if ("paper_guide".equals(context.path("kind").asText()))
      queries.add("Research question Method input Result Limitation");
    for (String searchText : queries) {
      check(deadline, leaseFailure);
      ObjectNode request =
          json.createObjectNode()
              .put("query", searchText)
              .put("top_k", Math.max(1, Math.min(6, context.at("/limits/search_top_k").asInt(6))));
      if (context.at("/subject/stage_id").isTextual())
        request.set("stage_id", context.at("/subject/stage_id"));
      ObjectNode result = backend.search(lease, request);
      if (paperGuide) {
        boolean validStats =
            result.path("eligible_chunks").isIntegralNumber()
                && result.path("eligible_chunks").asInt(-1) >= 0
                && result.path("coverage_complete").isBoolean();
        statsAvailable &= validStats;
        coverageComplete &= validStats && result.path("coverage_complete").asBoolean();
        if (validStats)
          eligibleChunks = Math.max(eligibleChunks, result.path("eligible_chunks").asInt());
      }
      if (!result.path("chunks").isArray())
        throw new AgentFailure("BACKEND_PROTOCOL_INVALID", "检索响应缺少chunks数组", false);
      for (JsonNode row : result.path("chunks")) {
        String key =
            row.path("source_id").asText()
                + ":"
                + row.path("source_revision").asInt()
                + ":"
                + row.path("chunk_id").asText();
        if (chunks.size() < 10 && policy.evidenceInBusinessScope(row, context) && seen.add(key))
          chunks.add(row.deepCopy());
      }
    }
    if (paperGuide) {
      ObjectNode stats = context.putObject("retrieval_coverage");
      stats
          .put("included_chunks", chunks.size())
          .put("eligible_chunks", eligibleChunks)
          .put("stats_available", statsAvailable)
          .put(
              "coverage_complete",
              statsAvailable && coverageComplete && chunks.size() == eligibleChunks);
    }
    return chunks;
  }

  private ArrayNode retrieveReportFacts(
      Lease lease, ObjectNode context, long deadline, AtomicReference<AgentFailure> failures) {
    ArrayNode chunks = json.createArrayNode();
    String sourceId = context.at("/weekly_report/source_id").asText();
    int revision = context.at("/weekly_report/source_revision").asInt();
    for (String field : List.of("done_items", "issues", "next_plan", "help_needed")) {
      check(deadline, failures);
      ObjectNode locator = json.createObjectNode().put("kind", "record_field").put("field", field);
      ObjectNode probe =
          json.createObjectNode()
              .put("source_id", sourceId)
              .put("source_revision", revision)
              .put("text", "scope-check");
      probe.set("locator", locator);
      if (!policy.evidenceInBusinessScope(probe, context))
        throw new AgentFailure("BACKEND_PROTOCOL_INVALID", "当前周报不在授权来源快照内", false);
      ObjectNode source = backend.source(lease, sourceId, revision, locator);
      if (!sourceId.equals(source.path("source_id").asText())
          || revision != source.path("source_revision").asInt()
          || !locator.equals(source.path("locator")))
        throw new AgentFailure("SOURCE_STALE", "周报内容或修订无法核验", false);
      ObjectNode chunk = probe.deepCopy().put("text", source.path("excerpt").asText());
      chunk.putNull("chunk_id");
      if (policy.evidenceInBusinessScope(chunk, context)) chunks.add(chunk);
    }
    return chunks;
  }

  private ArrayNode retrieveWeeklyFacts(
      Lease lease, ObjectNode context, long deadline, AtomicReference<AgentFailure> failures) {
    ArrayNode chunks = json.createArrayNode();
    if (context.path("confirmed_experiments").size() + context.path("confirmed_notes").size() > 10)
      throw new AgentFailure("CONTEXT_TOO_LARGE", "本周记录超过单次证据预算，请缩小任务范围", false);
    Set<String> seen = new HashSet<>();
    for (String group : List.of("confirmed_experiments", "confirmed_notes")) {
      String field = group.equals("confirmed_experiments") ? "results" : "claims";
      for (JsonNode row : context.path(group)) {
        String sourceId = row.path("source_id").asText();
        int revision = row.path("source_revision").asInt();
        if (!seen.add(sourceId + ":" + revision + ":" + field)) continue;
        check(deadline, failures);
        ObjectNode locator =
            json.createObjectNode().put("kind", "record_field").put("field", field);
        ObjectNode scopeProbe =
            json.createObjectNode()
                .put("source_id", sourceId)
                .put("source_revision", revision)
                .put("text", "scope-check");
        scopeProbe.set("locator", locator);
        if (!policy.evidenceInBusinessScope(scopeProbe, context))
          throw new AgentFailure("BACKEND_PROTOCOL_INVALID", "本周已确认记录不在授权来源快照内", false);
        ObjectNode source = backend.source(lease, sourceId, revision, locator);
        if (!sourceId.equals(source.path("source_id").asText())
            || revision != source.path("source_revision").asInt()
            || !locator.equals(source.path("locator")))
          throw new AgentFailure("SOURCE_STALE", "已确认记录的来源或版本无法核验", false);
        ObjectNode chunk =
            json.createObjectNode()
                .put("source_id", sourceId)
                .put("source_revision", revision)
                .put("text", source.path("excerpt").asText());
        chunk.set("locator", locator);
        chunk.putNull("chunk_id");
        if (policy.evidenceInBusinessScope(chunk, context)) chunks.add(chunk);
      }
    }
    return chunks;
  }

  private void revalidateSources(
      JsonNode node, Lease lease, long deadline, AtomicReference<AgentFailure> failures) {
    Set<String> checked = new HashSet<>();
    revalidateSources(node, lease, deadline, failures, checked);
  }

  private void revalidateSources(
      JsonNode node,
      Lease lease,
      long deadline,
      AtomicReference<AgentFailure> failures,
      Set<String> checked) {
    if (node.isObject() && node.has("source_id") && node.has("quote")) {
      String key =
          node.path("source_id").asText()
              + ":"
              + node.path("source_revision").asInt()
              + ":"
              + node.path("locator")
              + ":"
              + node.path("quote").asText();
      if (checked.add(key)) {
        check(deadline, failures);
        ObjectNode source =
            backend.source(
                lease,
                node.path("source_id").asText(),
                node.path("source_revision").asInt(),
                (ObjectNode) node.path("locator"));
        if (!source.path("source_id").equals(node.path("source_id"))
            || !source.path("source_revision").equals(node.path("source_revision"))
            || !source.path("excerpt").asText().contains(node.path("quote").asText()))
          throw new AgentFailure("SOURCE_STALE", "引用来源已变化或引文无法回查", false);
      }
    }
    if (node.isContainerNode())
      for (JsonNode child : node) revalidateSources(child, lease, deadline, failures, checked);
  }

  private ArrayNode interpretFeedback(
      ObjectNode context, long deadline, AtomicReference<AgentFailure> failures) {
    var input = json.createObjectNode();
    input.putObject("learning_policy").put("feedback_learning_enabled", true);
    var memories = input.putArray("feedback_memories");
    for (var memory : context.path("feedback_memories")) {
      if (memories.size() >= 5) break;
      memories
          .addObject()
          .put("lesson", memory.path("lesson").asText())
          .put("reuse_scope", memory.path("reuse_scope").asText("project_local"));
    }
    Future<ArrayNode> future = models.submit(() -> model.extractFeedback(input));
    try {
      while (true) {
        check(deadline, failures);
        try {
          return future.get(
              Math.min(
                  100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))),
              TimeUnit.MILLISECONDS);
        } catch (TimeoutException waiting) {
          /* Recheck lease and deadline. */
        }
      }
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof AgentFailure failure) throw failure;
      throw new AgentFailure("FEEDBACK_EXTRACTION_UNAVAILABLE", "反馈提取未完成", true);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AgentFailure("WORKER_INTERRUPTED", "运行已中断", true, true);
    } finally {
      future.cancel(true);
    }
  }

  private void check(long deadline, AtomicReference<AgentFailure> failures) {
    if (failures.get() != null)
      throw new AgentFailure(
          failures.get().code(), "续租失败，停止旧attempt", failures.get().retryable(), true);
    if (Thread.currentThread().isInterrupted())
      throw new AgentFailure("WORKER_INTERRUPTED", "运行已中断", true, true);
    if (System.nanoTime() >= deadline)
      throw new AgentFailure("RUN_BUDGET_EXCEEDED", "运行超过时间预算", true);
  }
}
