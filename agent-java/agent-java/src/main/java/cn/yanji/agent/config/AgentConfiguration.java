package cn.yanji.agent.config;

import cn.yanji.agent.backend.*;
import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.*;
import cn.yanji.agent.web.InternalAuthFilter;
import cn.yanji.agent.web.InternalAuthInterceptor;
import cn.yanji.agent.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class AgentConfiguration implements WebMvcConfigurer {
  private final AgentProperties properties;

  public AgentConfiguration(AgentProperties properties) {
    this.properties = properties;
    if (properties.heartbeatInterval().isNegative()
        || properties.heartbeatInterval().isZero()
        || properties.heartbeatInterval().compareTo(Duration.ofSeconds(30)) > 0
        || properties.runBudget().isNegative()
        || properties.runBudget().isZero())
      throw new IllegalArgumentException("Heartbeat must be (0,30s], budget must be positive");
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new InternalAuthInterceptor(properties)).addPathPatterns("/**");
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  BackendClient backend(ObjectMapper json) {
    var b = properties.backend();
    return new HttpBackendClient(
        b.baseUrl(),
        b.serviceKey(),
        b.connectTimeout(),
        b.readTimeout(),
        b.maxAttempts(),
        b.retryBackoff(),
        json);
  }

  @Bean
  OutputGuard outputGuard(
      ObjectMapper json,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${yanji.agent.content-validation-enabled:true}")
          boolean enabled) {
    return new OutputGuard(json, clock, enabled);
  }

  @Bean
  BusinessPolicy policy(ObjectMapper json, Clock clock) {
    return new BusinessPolicy(json, clock);
  }

  @Bean
  ModelGateway model(ChatClient.Builder builder, ObjectMapper json, MeterRegistry metrics) {
    return new SpringAiModelGateway(builder.build(), json, metrics, properties.maxInputChars());
  }

  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService heartbeats() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            properties.concurrency(), Thread.ofPlatform().name("agent-heartbeat-", 0).factory());
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService modelCalls() {
    return new ThreadPoolExecutor(
        properties.concurrency(),
        properties.concurrency(),
        0,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(properties.concurrency()),
        Thread.ofPlatform().name("agent-model-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService plannerCalls() {
    return new ThreadPoolExecutor(
        properties.concurrency(),
        properties.concurrency(),
        0,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        Thread.ofPlatform().name("agent-planner-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  RunWorkflow workflow(
      BackendClient backend,
      ModelGateway model,
      OutputGuard guard,
      BusinessPolicy policy,
      ObjectMapper json,
      ScheduledExecutorService heartbeats,
      ExecutorService modelCalls,
      MeterRegistry metrics,
      ExecutorService plannerCalls,
      @org.springframework.beans.factory.annotation.Value(
              "${yanji.agent.research-loop-enabled:false}")
          boolean researchLoopEnabled,
      @org.springframework.beans.factory.annotation.Value("${yanji.agent.planning-budget:10s}")
          Duration planningBudget) {
    return new RunWorkflow(
        backend,
        model,
        guard,
        policy,
        json,
        heartbeats,
        modelCalls,
        properties.heartbeatInterval(),
        properties.runBudget(),
        properties.workerId(),
        metrics,
        researchLoopEnabled,
        planningBudget,
        plannerCalls);
  }

  @Bean(destroyMethod = "close")
  TaskDispatcher dispatcher(RunWorkflow workflow, MeterRegistry metrics) {
    return new TaskDispatcher(
        workflow, properties.concurrency(), properties.queueCapacity(), metrics);
  }

  @Bean
  RestClientCustomizer modelHttpTimeouts() {
    return builder -> {
      HttpClient client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
      factory.setReadTimeout(Duration.ofSeconds(55));
      JdkClientHttpRequestFactory planning = new JdkClientHttpRequestFactory(client);
      planning.setReadTimeout(Duration.ofSeconds(10));
      builder.requestFactory(
          (uri, method) ->
              (ModelRequestTimeout.isPlanning() ? planning : factory).createRequest(uri, method));
    };
  }

  @Bean
  org.springframework.web.client.ResponseErrorHandler responseErrorHandler() {
    return new ProviderErrorHandler();
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<InternalAuthFilter>
      internalAuthFilter(ObjectMapper json) {
    var registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new InternalAuthFilter(properties, json));
    registration.addUrlPatterns("/*");
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
