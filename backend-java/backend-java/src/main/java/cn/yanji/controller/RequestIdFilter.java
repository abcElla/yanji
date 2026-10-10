package cn.yanji.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

@org.springframework.stereotype.Component
public class RequestIdFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);
  public static final String ERROR_CODE = RequestIdFilter.class.getName() + ".errorCode";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = java.util.UUID.randomUUID().toString();
    String previousId = MDC.get("requestId");
    response.setHeader("X-Request-Id", requestId);
    MDC.put("requestId", requestId);
    long started = System.nanoTime();
    boolean failed = false;
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException e) {
      failed = true;
      throw e;
    } finally {
      try {
        int status = failed ? 500 : response.getStatus();
        String path = request.getRequestURI().replaceAll("[\\p{Cntrl}\\s]", "_");
        if (path.length() > 512) path = path.substring(0, 512);
        String errorCode = String.valueOf(request.getAttribute(ERROR_CODE));
        if (errorCode.equals("null")) errorCode = failed ? "UNHANDLED_ERROR" : "-";
        String template =
            "HTTP method={} path={} status={} duration_ms={} error_code={} request_id={}";
        Object[] fields = {
          request.getMethod(),
          path,
          status,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
          errorCode,
          requestId
        };
        if (status >= 500) LOG.error(template, fields);
        else if (status >= 400) LOG.warn(template, fields);
        else LOG.info(template, fields);
      } finally {
        if (previousId == null) MDC.remove("requestId");
        else MDC.put("requestId", previousId);
      }
    }
  }
}
