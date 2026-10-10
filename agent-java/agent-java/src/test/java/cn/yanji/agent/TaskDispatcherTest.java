package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.workflow.RunWorkflow;
import cn.yanji.agent.workflow.TaskDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TaskDispatcherTest {
  @Test
  void boundedQueueRejectsOverloadAndDeduplicatesRunId() throws Exception {
    RunWorkflow workflow = mock(RunWorkflow.class);
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            inv -> {
              started.countDown();
              release.await();
              return null;
            })
        .when(workflow)
        .execute("a");
    try (TaskDispatcher dispatcher =
        new TaskDispatcher(workflow, 1, 1, new SimpleMeterRegistry())) {
      assertThat(dispatcher.submit("a")).isEqualTo(TaskDispatcher.Acceptance.ACCEPTED);
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(dispatcher.submit("a")).isEqualTo(TaskDispatcher.Acceptance.DUPLICATE);
      dispatcher.submit("b");
      assertThatThrownBy(() -> dispatcher.submit("c"))
          .isInstanceOf(AgentFailure.class)
          .hasMessageContaining("队列");
      release.countDown();
    } finally {
      release.countDown();
    }
    verify(workflow, times(1)).execute("a");
  }

  @Test
  void stoppedWorkerDoesNotAcknowledgeNewTasks() {
    TaskDispatcher dispatcher =
        new TaskDispatcher(mock(RunWorkflow.class), 1, 1, new SimpleMeterRegistry());
    dispatcher.close();
    assertThatThrownBy(() -> dispatcher.submit("a")).isInstanceOf(AgentFailure.class);
  }
}
