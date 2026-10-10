package cn.yanji.controller;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingTest {
  final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
  ListAppender<ILoggingEvent> appender;
  Level previousLevel;

  @BeforeEach
  void attach() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
    appender.stop();
    MDC.clear();
  }

  @Test
  void logsResponseAndCorrelationWithoutQueryHeadersOrBody() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setQueryString("token=private-query-value");
    request.addHeader("Authorization", "Bearer private-header-value");
    request.setContent("password=private-body-value".getBytes());
    var response = new MockHttpServletResponse();
    new RequestIdFilter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
              response.setStatus(401);
              request.setAttribute(RequestIdFilter.ERROR_CODE, "INVALID_CREDENTIALS");
            });
    var event = appender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains(
            "method=POST",
            "path=/api/v1/auth/login",
            "status=401",
            "duration_ms=",
            "error_code=INVALID_CREDENTIALS",
            response.getHeader("X-Request-Id"))
        .doesNotContain("private-query-value", "private-header-value", "private-body-value");
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void recordsUnexpectedFailureAndRestoresThreadContext() {
    MDC.put("requestId", "previous-context");
    var request = new MockHttpServletRequest("GET", "/health/live");
    var response = new MockHttpServletResponse();
    assertThatThrownBy(
            () ->
                new RequestIdFilter()
                    .doFilter(
                        request,
                        response,
                        (req, res) -> {
                          throw new ServletException("private-exception-value");
                        }))
        .isInstanceOf(ServletException.class);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("status=500", "error_code=UNHANDLED_ERROR")
        .doesNotContain("private-exception-value");
    assertThat(MDC.get("requestId")).isEqualTo("previous-context");
  }

  @Test
  void successfulRequestUsesInfo() throws Exception {
    new RequestIdFilter()
        .doFilter(
            new MockHttpServletRequest("GET", "/health/live"),
            new MockHttpServletResponse(),
            (req, res) -> {});
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("status=200", "error_code=-");
  }
}
