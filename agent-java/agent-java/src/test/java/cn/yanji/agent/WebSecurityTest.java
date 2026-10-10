package cn.yanji.agent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.yanji.agent.config.AgentProperties;
import cn.yanji.agent.web.ApiErrors;
import cn.yanji.agent.web.InternalAuthInterceptor;
import cn.yanji.agent.web.TaskController;
import cn.yanji.agent.workflow.TaskDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebSecurityTest {
  MockMvc mvc;
  TaskDispatcher dispatcher = mock(TaskDispatcher.class);

  @BeforeEach
  void setup() {
    AgentProperties properties = mock(AgentProperties.class);
    when(properties.receiverKey()).thenReturn("r".repeat(32));
    when(dispatcher.submit(anyString())).thenReturn(TaskDispatcher.Acceptance.ACCEPTED);
    mvc =
        MockMvcBuilders.standaloneSetup(new TaskController(dispatcher), new TestMetrics())
            .setControllerAdvice(new ApiErrors())
            .addInterceptors(new InternalAuthInterceptor(properties))
            .build();
  }

  String request() {
    return "{\"schema_version\":1,\"run_id\":\""
        + Fixtures.RUN
        + "\",\"event_id\":\""
        + Fixtures.RUN
        + "\"}";
  }

  @Test
  void missingKeyCannotSubmitOrTriggerModel() throws Exception {
    mvc.perform(post("/internal/v1/tasks").contentType("application/json").content(request()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void acceptsOnlyAuthenticatedIdEnvelope() throws Exception {
    mvc.perform(
            post("/internal/v1/tasks")
                .header("X-Agent-Key", "r".repeat(32))
                .contentType("application/json")
                .content(request()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.run_id").value(Fixtures.RUN));
  }

  @Test
  void invalidIdCannotEnterWorker() throws Exception {
    mvc.perform(
            post("/internal/v1/tasks")
                .header("X-Agent-Key", "r".repeat(32))
                .contentType("application/json")
                .content(request().replace(Fixtures.RUN, "not-a-uuid")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void wrongKeyCannotReadMetrics() throws Exception {
    mvc.perform(get("/actuator/prometheus").header("X-Agent-Key", "wrong"))
        .andExpect(status().isUnauthorized());
  }

  @org.springframework.web.bind.annotation.RestController
  static class TestMetrics {
    @org.springframework.web.bind.annotation.GetMapping("/actuator/prometheus")
    public String metrics() {
      return "test-metrics";
    }
  }
}
