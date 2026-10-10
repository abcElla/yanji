package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.model.ModelGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "agent.receiver-key=test-receiver-key-32-characters-long",
      "spring.ai.openai.api-key=test-synthetic-key"
    })
class ModelHttpErrorTest {
  static HttpServer provider;
  static volatile int status = 401;
  static volatile boolean paused;
  static java.util.concurrent.CountDownLatch headersSent =
      new java.util.concurrent.CountDownLatch(1);
  static java.util.concurrent.CountDownLatch releaseBody =
      new java.util.concurrent.CountDownLatch(1);

  static {
    try {
      provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      provider.createContext(
          "/v1/chat/completions",
          ex -> {
            if (paused) {
              ex.getResponseHeaders().set("Content-Type", "application/json");
              ex.sendResponseHeaders(200, 0);
              ex.getResponseBody().flush();
              headersSent.countDown();
              try {
                releaseBody.await(3, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                ex.close();
              }
              return;
            }
            byte[] body = "{\"error\":{\"message\":\"private-provider-response\"}}".getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
          });
      provider.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.ai.openai.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
  }

  @Autowired ModelGateway model;

  @AfterAll
  static void stop() {
    provider.stop(0);
  }

  @Test
  void cancelledPlannerReleasesHttpBodyReaderAndWorker() throws Exception {
    paused = true;
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var future =
          executor.submit(
              () ->
                  model.plan(
                      Fixtures.JSON
                          .createObjectNode()
                          .put("kind", "research_qa")
                          .put("question", "测试")));
      assertThat(headersSent.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      future.cancel(true);
      executor.shutdown();
      assertThat(executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseBody.countDown();
      paused = false;
      executor.shutdownNow();
    }
  }

  @Test
  void authenticationFailureIsNonRetryableAndSanitized() {
    status = 401;
    assertThatThrownBy(
            () ->
                model.generate(
                    Fixtures.context("research_qa"),
                    Fixtures.evidence(),
                    Fixtures.JSON.createArrayNode()))
        .isInstanceOf(AgentFailure.class)
        .hasMessageNotContaining("private-provider-response")
        .satisfies(
            e -> {
              assertThat(((AgentFailure) e).code()).isEqualTo("MODEL_AUTH_FAILED");
              assertThat(((AgentFailure) e).retryable()).isFalse();
            });
  }

  @Test
  void rateLimitHasStableRetryableCode() {
    status = 429;
    assertThatThrownBy(
            () ->
                model.generate(
                    Fixtures.context("research_qa"),
                    Fixtures.evidence(),
                    Fixtures.JSON.createArrayNode()))
        .satisfies(
            e -> {
              assertThat(((AgentFailure) e).code()).isEqualTo("MODEL_RATE_LIMITED");
              assertThat(((AgentFailure) e).retryable()).isTrue();
            });
  }
}
