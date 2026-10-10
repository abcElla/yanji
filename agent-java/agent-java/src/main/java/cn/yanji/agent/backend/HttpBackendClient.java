package cn.yanji.agent.backend;

import cn.yanji.agent.contract.AgentFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Fixed-origin HTTP tools. Serialization occurs once so callback replays are byte-identical. */
public final class HttpBackendClient implements BackendClient {
  private final String baseUrl, serviceKey;
  private final Duration timeout, backoff;
  private final int attempts;
  private final ObjectMapper json;
  private final HttpClient http;

  public HttpBackendClient(
      String baseUrl,
      String serviceKey,
      Duration connectTimeout,
      Duration timeout,
      int attempts,
      Duration backoff,
      ObjectMapper json) {
    URI uri = URI.create(baseUrl);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) throw new IllegalArgumentException("Invalid backend origin");
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.serviceKey = serviceKey;
    this.timeout = timeout;
    this.attempts = attempts;
    this.backoff = backoff;
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            // The current Uvicorn entry point rejects cleartext HTTP/2 upgrade requests.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public ObjectNode claim(String runId, String workerId) {
    return request(runId, "claim", null, json.createObjectNode().put("worker_id", workerId));
  }

  @Override
  public ObjectNode context(Lease lease) {
    return request(lease.runId(), "context", lease, null);
  }

  @Override
  public ObjectNode search(Lease lease, ObjectNode query) {
    return request(lease.runId(), "search", lease, query);
  }

  @Override
  public ObjectNode source(Lease lease, String sourceId, int revision, ObjectNode locator) {
    UUID.fromString(sourceId);
    String position = java.net.URLEncoder.encode(locator.toString(), StandardCharsets.UTF_8);
    return request(
        lease.runId(),
        "sources/" + sourceId + "?revision=" + revision + "&locator=" + position,
        lease,
        null);
  }

  @Override
  public void heartbeat(Lease lease) {
    request(lease.runId(), "heartbeat", lease, body(lease));
  }

  @Override
  public void event(Lease lease, String key, String step, String status, String message) {
    request(
        lease.runId(),
        "events",
        lease,
        body(lease)
            .put("event_key", key)
            .put("step", step)
            .put("status", status)
            .put("message", message));
  }

  @Override
  public void finalizeRun(Lease lease, ObjectNode body) {
    request(lease.runId(), "finalize", lease, body);
  }

  @Override
  public void fail(Lease lease, String code, String message, boolean retryable) {
    request(
        lease.runId(),
        "fail",
        lease,
        body(lease).put("code", code).put("message", message).put("retryable", retryable));
  }

  private ObjectNode body(Lease lease) {
    return json.createObjectNode().put("attempt", lease.attempt());
  }

  private ObjectNode request(String runId, String operation, Lease lease, ObjectNode data) {
    UUID.fromString(runId);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/runs/" + runId + "/" + operation))
            .timeout(timeout)
            .header("X-Service-Key", serviceKey)
            .header("Accept", "application/json");
    if (lease != null)
      builder
          .header("X-Run-Lease", lease.token())
          .header("X-Run-Attempt", Integer.toString(lease.attempt()));
    if (data == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(data.toString(), StandardCharsets.UTF_8));
    HttpRequest request = builder.build();
    for (int index = 0; index < attempts; index++) {
      try {
        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode envelope;
        try {
          envelope = json.readTree(response.body());
        } catch (Exception e) {
          envelope = json.createObjectNode();
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          if (envelope == null
              || !envelope.path("code").isIntegralNumber()
              || envelope.path("code").asInt(-1) != 0
              || !envelope.path("data").isObject())
            throw new AgentFailure("BACKEND_PROTOCOL_INVALID", "后端响应不符合Result契约", false);
          return (ObjectNode) envelope.path("data");
        }
        String code = envelope == null ? "" : envelope.at("/data/error_code").asText();
        if (!code.matches("[A-Z][A-Z0-9_]{0,79}")) code = "BACKEND_HTTP_" + status;
        boolean lost =
            Set.of("RUN_BUSY", "LEASE_INVALID", "LEASE_EXPIRED", "RUN_CANCELLED", "INVALID_STATE")
                .contains(code);
        boolean retry = status == 429 || status >= 500;
        if ("CONTEXT_STALE".equals(code))
          throw new AgentFailure(code, "任务或材料已更新，本次快照失效。请重新发起分析以读取最新状态。", false);
        throw new AgentFailure(
            code, lost ? "运行已取消、租约失效或由其他Worker处理" : "后端内部接口调用失败（HTTP " + status + "）", retry, lost);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AgentFailure("WORKER_INTERRUPTED", "运行已中断", true, true);
      } catch (AgentFailure e) {
        if (!e.retryable() || index + 1 == attempts) throw e;
      } catch (java.io.IOException e) {
        if (index + 1 == attempts)
          throw new AgentFailure("BACKEND_UNAVAILABLE", "后端暂不可用或响应超时", true);
      }
      try {
        Thread.sleep(Math.min(backoff.toMillis() * (1L << index), 2000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AgentFailure("WORKER_INTERRUPTED", "运行已中断", true, true);
      }
    }
    throw new AgentFailure("BACKEND_UNAVAILABLE", "后端暂不可用", true);
  }
}
