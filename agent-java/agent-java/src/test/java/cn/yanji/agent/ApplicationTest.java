package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.address=127.0.0.1",
      "agent.receiver-key=test-receiver-key-32-characters-long",
      "spring.ai.openai.api-key=test-key-no-external-calls"
    })
@AutoConfigureObservability(metrics = true, tracing = false)
class ApplicationTest {
  @Autowired TestRestTemplate http;

  @Test
  void startsIndependentServiceAndExposesMinimalHealth() {
    var response = http.getForEntity("/actuator/health", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("UP").doesNotContain("api-key");
  }

  @Test
  void realMetricsEndpointRequiresInternalIdentity() {
    assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void authorizedMetricsActuallyExposeWorkerMeasurements() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Agent-Key", "test-receiver-key-32-characters-long");
    var response =
        http.exchange(
            "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("yanji_agent_queue_size");
  }

  @Test
  void forbidsCallerSuppliedIdentity() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Agent-Key", "test-receiver-key-32-characters-long");
    headers.setContentType(MediaType.APPLICATION_JSON);
    var body =
        Fixtures.object(
            "{\"schema_version\":1,\"run_id\":\""
                + Fixtures.RUN
                + "\",\"event_id\":\""
                + Fixtures.RUN
                + "\",\"student_id\":\"attacker\"}");
    assertThat(
            http.postForEntity("/internal/v1/tasks", new HttpEntity<>(body, headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
