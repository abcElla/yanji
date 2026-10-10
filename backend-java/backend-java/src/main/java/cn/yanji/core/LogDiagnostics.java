package cn.yanji.core;

/** Safe diagnostic labels: never expose exception messages, request payloads or credentials. */
public final class LogDiagnostics {
  private LogDiagnostics() {}

  public static String reason(Throwable error) {
    if (error instanceof AppError app) return app.code;
    Throwable cause = root(error);
    if (error instanceof java.net.http.HttpTimeoutException
        || cause instanceof java.net.SocketTimeoutException) return "HTTP_TIMEOUT";
    if (cause instanceof java.net.ConnectException) return "CONNECTION_FAILED";
    if (cause instanceof java.net.UnknownHostException) return "DNS_FAILED";
    if (cause instanceof javax.net.ssl.SSLException) return "TLS_FAILED";
    if (cause instanceof java.sql.SQLException) return "DATABASE_ERROR";
    if (cause instanceof InterruptedException) return "INTERRUPTED";
    if (cause instanceof java.io.IOException) return "IO_ERROR";
    return "UNEXPECTED_ERROR";
  }

  public static String exceptionType(Throwable error) {
    return root(error).getClass().getSimpleName();
  }

  private static Throwable root(Throwable error) {
    Throwable current = error;
    for (int i = 0; i < 20 && current.getCause() != null && current.getCause() != current; i++) {
      current = current.getCause();
    }
    return current;
  }
}
