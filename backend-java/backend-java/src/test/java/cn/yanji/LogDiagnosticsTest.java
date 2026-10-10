package cn.yanji;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.core.*;
import org.junit.jupiter.api.Test;

class LogDiagnosticsTest {
  @Test
  void classifiesFailuresWithoutExposingExceptionMessages() {
    var timeout = new java.net.http.HttpTimeoutException("secret-timeout-value");
    assertThat(LogDiagnostics.reason(timeout)).isEqualTo("HTTP_TIMEOUT");
    var connection = new RuntimeException(new java.net.ConnectException("secret-connection-value"));
    assertThat(LogDiagnostics.reason(connection)).isEqualTo("CONNECTION_FAILED");
    assertThat(LogDiagnostics.exceptionType(connection)).isEqualTo("ConnectException");
    var database = new RuntimeException(new java.sql.SQLException("password=secret-db-value"));
    assertThat(LogDiagnostics.reason(database)).isEqualTo("DATABASE_ERROR");
    assertThat(LogDiagnostics.exceptionType(database)).isEqualTo("SQLException");
    assertThat(
            LogDiagnostics.reason(new AppError(503, "AGENT_DISPATCH_RESPONSE_INVALID", "secret")))
        .isEqualTo("AGENT_DISPATCH_RESPONSE_INVALID");
  }
}
