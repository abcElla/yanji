package cn.yanji.agent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agent")
public record AgentProperties(
    @NotBlank @Size(min = 32, max = 256) String receiverKey,
    @NotBlank @Size(max = 100) String workerId,
    @Min(1) @Max(8) int concurrency,
    @Min(1) @Max(100) int queueCapacity,
    @NotNull Duration heartbeatInterval,
    @NotNull Duration runBudget,
    @Min(1000) @Max(100000) int maxInputChars,
    @Valid @NotNull Backend backend) {
  public record Backend(
      @NotBlank String baseUrl,
      @NotBlank @Size(min = 32, max = 256) String serviceKey,
      @NotNull Duration connectTimeout,
      @NotNull Duration readTimeout,
      @Min(1) @Max(3) int maxAttempts,
      @NotNull Duration retryBackoff) {
    @Override
    public String toString() {
      return "Backend[baseUrl=" + baseUrl + ", serviceKey=<redacted>]";
    }
  }

  @Override
  public String toString() {
    return "AgentProperties[receiverKey=<redacted>, workerId="
        + workerId
        + ", backend="
        + backend
        + "]";
  }
}
