package cn.yanji.agent.web;

import cn.yanji.agent.config.AgentProperties;
import cn.yanji.agent.contract.AgentFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Servlet-level authentication also covers Actuator's separate HandlerMapping. */
public final class InternalAuthFilter extends OncePerRequestFilter {
  private final InternalAuthInterceptor auth;
  private final ObjectMapper json;

  public InternalAuthFilter(AgentProperties properties, ObjectMapper json) {
    auth = new InternalAuthInterceptor(properties);
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      auth.preHandle(request, response, null);
    } catch (AgentFailure failure) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      json.writeValue(
          response.getOutputStream(),
          Map.of(
              "code",
              40100,
              "message",
              "内部服务身份无效",
              "data",
              Map.of("error_code", "AGENT_UNAUTHORIZED", "retryable", false, "details", Map.of())));
      return;
    }
    chain.doFilter(request, response);
  }
}
