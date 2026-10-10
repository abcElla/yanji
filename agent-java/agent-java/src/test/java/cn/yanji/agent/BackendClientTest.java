package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.backend.HttpBackendClient;
import cn.yanji.agent.backend.Lease;
import cn.yanji.agent.contract.AgentFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackendClientTest {
  @Test
  void staleSnapshotRequiresNewRunAndDoesNotRetryOldLease() {
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/internal/v1/runs/" + Fixtures.RUN + "/context",
        ex -> {
          calls.incrementAndGet();
          byte[] data =
              "{\"code\":40900,\"data\":{\"error_code\":\"CONTEXT_STALE\"},\"message\":\"private backend text\"}"
                  .getBytes();
          ex.sendResponseHeaders(409, data.length);
          ex.getResponseBody().write(data);
          ex.close();
        });
    assertThatThrownBy(() -> client.context(new Lease(Fixtures.RUN, 1, "secret")))
        .isInstanceOf(AgentFailure.class)
        .hasMessageContaining("重新发起")
        .hasMessageNotContaining("private backend text")
        .satisfies(
            e -> {
              assertThat(((AgentFailure) e).code()).isEqualTo("CONTEXT_STALE");
              assertThat(((AgentFailure) e).retryable()).isFalse();
            });
    assertThat(calls).hasValue(1);
  }

  HttpServer server;
  HttpBackendClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    client =
        new HttpBackendClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "s".repeat(32),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            2,
            Duration.ofMillis(1),
            Fixtures.JSON);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsRunScopedHeadersAndUnwrapsEnvelope() {
    server.createContext(
        "/internal/v1/runs/" + Fixtures.RUN + "/context",
        ex -> {
          assertThat(ex.getRequestHeaders().getFirst("X-Service-Key")).isEqualTo("s".repeat(32));
          assertThat(ex.getRequestHeaders().getFirst("X-Run-Lease")).isEqualTo("lease-secret");
          assertThat(ex.getRequestHeaders().getFirst("X-Run-Attempt")).isEqualTo("2");
          byte[] data =
              "{\"code\":0,\"data\":{\"schema_version\":2},\"message\":\"success\"}".getBytes();
          ex.getResponseHeaders().set("Content-Type", "application/json");
          ex.sendResponseHeaders(200, data.length);
          ex.getResponseBody().write(data);
          ex.close();
        });
    assertThat(
            client
                .context(new Lease(Fixtures.RUN, 2, "lease-secret"))
                .path("schema_version")
                .asInt())
        .isEqualTo(2);
  }

  @Test
  void callbackRetryUsesIdenticalBody() {
    List<String> bodies = new ArrayList<>();
    AtomicInteger count = new AtomicInteger();
    server.createContext(
        "/internal/v1/runs/" + Fixtures.RUN + "/finalize",
        ex -> {
          bodies.add(new String(ex.getRequestBody().readAllBytes()));
          byte[] data =
              (count.incrementAndGet() == 1
                      ? "{\"code\":50300,\"data\":{\"error_code\":\"UNAVAILABLE\"}}"
                      : "{\"code\":0,\"data\":{\"status\":\"succeeded\"}}")
                  .getBytes();
          ex.getResponseHeaders().set("Content-Type", "application/json");
          ex.sendResponseHeaders(count.get() == 1 ? 503 : 200, data.length);
          ex.getResponseBody().write(data);
          ex.close();
        });
    client.finalizeRun(
        new Lease(Fixtures.RUN, 1, "lease-secret"),
        Fixtures.object("{\"attempt\":1,\"result\":{\"summary\":\"你好\"}}"));
    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
  }

  @Test
  void leaseLossIsNotRetriedOrExposedAsProviderBody() {
    AtomicInteger count = new AtomicInteger();
    server.createContext(
        "/internal/v1/runs/" + Fixtures.RUN + "/heartbeat",
        ex -> {
          count.incrementAndGet();
          byte[] data =
              "{\"code\":40900,\"data\":{\"error_code\":\"LEASE_EXPIRED\"},\"message\":\"secret-body\"}"
                  .getBytes();
          ex.getResponseHeaders().set("Content-Type", "application/json");
          ex.sendResponseHeaders(409, data.length);
          ex.getResponseBody().write(data);
          ex.close();
        });
    assertThatThrownBy(() -> client.heartbeat(new Lease(Fixtures.RUN, 1, "secret")))
        .isInstanceOf(AgentFailure.class)
        .hasMessageNotContaining("secret-body")
        .satisfies(e -> assertThat(((AgentFailure) e).ownershipLost()).isTrue());
    assertThat(count).hasValue(1);
  }

  @Test
  void allInternalOperationsAvoidHttp2Upgrade() {
    List<String> upgrades = new ArrayList<>();
    List<String> settings = new ArrayList<>();
    server.createContext(
        "/internal/v1/runs/",
        ex -> {
          upgrades.add(ex.getRequestHeaders().getFirst("Upgrade"));
          settings.add(ex.getRequestHeaders().getFirst("HTTP2-Settings"));
          ex.getRequestBody().readAllBytes();
          byte[] data = "{\"code\":0,\"data\":{}}".getBytes();
          ex.sendResponseHeaders(200, data.length);
          ex.getResponseBody().write(data);
          ex.close();
        });
    Lease lease = new Lease(Fixtures.RUN, 1, "lease-secret");
    client.claim(Fixtures.RUN, "test-worker");
    client.context(lease);
    client.heartbeat(lease);
    client.search(lease, Fixtures.object("{\"query\":\"test\",\"top_k\":1}"));
    client.source(lease, Fixtures.RUN, 1, Fixtures.object("{\"kind\":\"pdf_page\",\"page\":1}"));
    client.event(lease, "event", "context", "completed", "test");
    client.finalizeRun(lease, Fixtures.object("{\"attempt\":1}"));
    client.fail(lease, "TEST_FAILURE", "test", false);
    assertThat(upgrades).hasSize(8).containsOnlyNulls();
    assertThat(settings).hasSize(8).containsOnlyNulls();
  }

  @Test
  void leaseToStringRedactsToken() {
    assertThat(new Lease(Fixtures.RUN, 1, "secret").toString()).doesNotContain("secret");
  }
}
