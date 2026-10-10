package cn.yanji.core;

import java.util.Map;

public class AppError extends RuntimeException {
  public final int status;
  public final String code;
  public final Map<String, Object> details;
  public final boolean retryable;

  public AppError(int status, String code, String message) {
    this(status, code, message, Data.map(), status == 503);
  }

  public AppError(
      int status, String code, String message, Map<String, Object> details, boolean retryable) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
    this.retryable = retryable;
  }
}
