package cn.yanji.agent.workflow;

import cn.yanji.agent.contract.AgentFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.*;

/** Bounded in-memory admission. Backend leases, not this set, provide cross-process exclusion. */
public final class TaskDispatcher implements AutoCloseable {
  public enum Acceptance {
    ACCEPTED,
    DUPLICATE
  }

  private final ThreadPoolExecutor executor;
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private final RunWorkflow workflow;

  public TaskDispatcher(
      RunWorkflow workflow, int concurrency, int queueCapacity, MeterRegistry metrics) {
    this.workflow = workflow;
    executor =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().name("agent-run-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    metrics.gauge("yanji.agent.queue.size", executor, e -> e.getQueue().size());
    metrics.gauge("yanji.agent.workers.active", executor, ThreadPoolExecutor::getActiveCount);
  }

  public Acceptance submit(String runId) {
    if (executor.isShutdown()) throw new AgentFailure("WORKER_STOPPING", "服务正在关闭，请重新投递", true);
    if (!active.add(runId)) return Acceptance.DUPLICATE;
    try {
      executor.execute(
          () -> {
            try {
              workflow.execute(runId);
            } finally {
              active.remove(runId);
            }
          });
      return Acceptance.ACCEPTED;
    } catch (RejectedExecutionException e) {
      active.remove(runId);
      throw new AgentFailure("WORKER_OVERLOADED", "Agent队列已满，请稍后重投", true);
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    active.clear();
  }
}
