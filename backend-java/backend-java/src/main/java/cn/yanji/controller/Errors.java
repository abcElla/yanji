package cn.yanji.controller;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(AppError.class)
  public ResponseEntity<?> app(AppError e) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      attrs.getRequest().setAttribute(RequestIdFilter.ERROR_CODE, e.code);
    }
    return ResponseEntity.status(e.status)
        .header("Cache-Control", "no-store")
        .body(
            map(
                "code",
                e.status * 100,
                "data",
                map("error_code", e.code, "retryable", e.retryable, "details", e.details),
                "message",
                e.getMessage()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<?> conflict(Exception e) {
    return app(new AppError(409, "VERSION_CONFLICT", "数据已变化或存在重复记录，请刷新后重试"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> upload(Exception e) {
    return app(new AppError(413, "FILE_TOO_LARGE", "文件超出大小限制"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(Exception e) {
    return app(new AppError(422, "VALIDATION_ERROR", "请求字段格式无效"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error(
            "Unhandled request failure reason={} exception={}",
            LogDiagnostics.reason(e),
            LogDiagnostics.exceptionType(e));
    return app(new AppError(500, "INTERNAL_ERROR", "服务器内部错误"));
  }
}
