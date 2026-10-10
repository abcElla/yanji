package cn.yanji.agent.web;

import cn.yanji.agent.config.AgentProperties;
import cn.yanji.agent.contract.AgentFailure;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.servlet.HandlerInterceptor;

public final class InternalAuthInterceptor implements HandlerInterceptor {
  private final byte[] key;

  public InternalAuthInterceptor(AgentProperties properties) {
    key = properties.receiverKey().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String path = request.getRequestURI();
    if (path.equals("/actuator/health")
        || path.equals("/actuator/health/liveness")
        || path.equals("/error")) return true;
    String supplied = request.getHeader("X-Agent-Key");
    if (supplied == null
        || supplied.length() > 256
        || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8)))
      throw new AgentFailure("AGENT_UNAUTHORIZED", "内部服务身份无效", false);
    return true;
  }
}
