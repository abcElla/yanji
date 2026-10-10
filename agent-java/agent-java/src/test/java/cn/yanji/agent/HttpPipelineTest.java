package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.agent.model.ModelGateway;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.address=127.0.0.1",
      "agent.receiver-key=test-receiver-key-32-characters-long",
      "spring.ai.openai.api-key=test-no-live-calls"
    })
class HttpPipelineTest {
  static HttpServer backend;
  static AtomicInteger finalizations = new AtomicInteger(), events = new AtomicInteger();
  static AtomicReference<ObjectNode> result = new AtomicReference<>();
  static CountDownLatch finished = new CountDownLatch(1);

  static {
    try {
      backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      backend.createContext(
          "/internal/v1/runs/",
          exchange -> {
            ObjectNode data;
            if (!"test-backend-service-key-32-characters-long"
                .equals(exchange.getRequestHeaders().getFirst("X-Service-Key")))
              throw new AssertionError("missing backend identity");
            String op = exchange.getRequestURI().getPath();
            if (op.endsWith("/claim"))
              data =
                  Fixtures.object(
                      result.get() == null
                          ? "{\"status\":\"running\",\"attempt\":1,\"lease_token\":\"lease-secret\"}"
                          : "{\"status\":\"succeeded\",\"attempt\":1}");
            else {
              if (!"lease-secret".equals(exchange.getRequestHeaders().getFirst("X-Run-Lease")))
                throw new AssertionError("missing lease");
              if (op.endsWith("/context")) data = Fixtures.context("research_qa");
              else if (op.endsWith("/search")) {
                data = Fixtures.JSON.createObjectNode().put("retrieval_mode", "lexical");
                data.set("chunks", Fixtures.evidence());
              } else if (op.contains("/sources/")) {
                data =
                    Fixtures.JSON
                        .createObjectNode()
                        .put("source_id", Fixtures.SOURCE)
                        .put("source_revision", 1)
                        .put("excerpt", "实验结果为10，尚未重复验证。");
              } else if (op.endsWith("/finalize")) {
                result.set((ObjectNode) Fixtures.JSON.readTree(exchange.getRequestBody()));
                finalizations.incrementAndGet();
                data = Fixtures.object("{\"status\":\"succeeded\",\"proposal_ids\":[]}");
              } else {
                if (op.endsWith("/events")) events.incrementAndGet();
                data = Fixtures.JSON.createObjectNode();
              }
            }
            ObjectNode response =
                Fixtures.JSON.createObjectNode().put("code", 0).put("message", "success");
            response.set("data", data);
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            if (op.endsWith("/finalize")) finished.countDown();
          });
      backend.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "agent.backend.base-url", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
    registry.add("agent.backend.service-key", () -> "test-backend-service-key-32-characters-long");
  }

  @MockitoBean ModelGateway model;
  @Autowired TestRestTemplate http;

  @AfterAll
  static void stop() {
    backend.stop(0);
  }

  @Test
  void authenticatedSubmissionRunsFullHttpClaimContextSearchEventsSourceFinalizeChain()
      throws Exception {
    when(model.generate(any(), any(), any())).thenReturn(Fixtures.answer());
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Agent-Key", "test-receiver-key-32-characters-long");
    headers.setContentType(MediaType.APPLICATION_JSON);
    ObjectNode request =
        Fixtures.JSON
            .createObjectNode()
            .put("schema_version", 1)
            .put("run_id", Fixtures.RUN)
            .put("event_id", Fixtures.RUN);
    assertThat(
            http.postForEntity(
                    "/internal/v1/tasks", new HttpEntity<>(request, headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(finalizations).hasValue(1);
    assertThat(events.get()).isGreaterThanOrEqualTo(3);
    assertThat(result.get().at("/result/outcome").asText()).isEqualTo("ok");
    assertThat(result.get().path("attempt").asInt()).isEqualTo(1);
    verify(model, times(1)).generate(any(), any(), any());
  }
}
