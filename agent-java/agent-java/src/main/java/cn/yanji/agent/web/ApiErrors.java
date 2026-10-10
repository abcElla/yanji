package cn.yanji.agent.web;

import cn.yanji.agent.contract.AgentFailure;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(AgentFailure.class)
  public ResponseEntity<?> failure(AgentFailure e) {
    int status = e.code().equals("AGENT_UNAUTHORIZED") ? 401 : e.retryable() ? 503 : 422;
    return response(status, e.code(), e.getMessage(), e.retryable());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
  public ResponseEntity<?> invalid(Exception e) {
    return response(400, "VALIDATION_ERROR", "请求字段、类型或契约版本无效", false);
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<?> notFound(Exception e) {
    return response(404, "NOT_FOUND", "接口不存在", false);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    return response(500, "AGENT_INTERNAL_ERROR", "Agent服务内部异常", true);
  }

  private ResponseEntity<?> response(int status, String code, String message, boolean retryable) {
    var builder = ResponseEntity.status(status);
    if (retryable) builder.header("Retry-After", "5");
    return builder.body(
        Map.of(
            "code",
            status * 100,
            "message",
            message,
            "data",
            Map.of("error_code", code, "retryable", retryable, "details", Map.of())));
  }
}
