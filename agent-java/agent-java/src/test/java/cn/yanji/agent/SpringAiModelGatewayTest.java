package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.model.SpringAiModelGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiModelGatewayTest {
  @Test
  void adviceModeRecoversPlainAnswerButKeepsPlannerStrict() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("先固定数据划分，再比较基线。")))));
    var context = Fixtures.context("research_qa").put("advice_mode", true);
    var answer =
        gateway(45000)
            .generate(context, Fixtures.JSON.createArrayNode(), Fixtures.JSON.createArrayNode());
    assertThat(answer.at("/result/summary").asText()).contains("固定数据划分");
    assertThat(answer.path("proposal_drafts")).isEmpty();
  }

  @Test
  void plannerUsesOwnProtocolSmallOutputBudgetAndRestoresTransportScope() {
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            inv -> {
              Prompt prompt = inv.getArgument(0);
              assertThat(cn.yanji.agent.model.ModelRequestTimeout.isPlanning()).isTrue();
              assertThat(prompt.getOptions().getMaxTokens()).isEqualTo(350);
              return new ChatResponse(
                  List.of(
                      new Generation(
                          new AssistantMessage(
                              "{\"goal\":\"核对重复材料\",\"queries\":[{\"query\":\"重复次数 数据划分\",\"reason\":\"核对条件\"}]}"))));
            });
    var plan = gateway(45000).plan(Fixtures.JSON.createObjectNode().put("question", "材料是否齐备"));
    assertThat(plan.queries()).hasSize(1);
    assertThat(cn.yanji.agent.model.ModelRequestTimeout.isPlanning()).isFalse();
  }

  @Test
  void nonJsonPlannerResponseCannotBecomeAPlan() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("private provider body")))));
    assertThatThrownBy(() -> gateway(45000).plan(Fixtures.context("research_qa")))
        .isInstanceOfSatisfying(
            AgentFailure.class,
            e -> {
              assertThat(e.code()).isEqualTo("MODEL_RESPONSE_INVALID");
              assertThat(e.getMessage()).doesNotContain("private");
            });
  }

  @Test
  void connectTimeoutIsNotMisclassifiedAsReadTimeout() {
    when(chatModel.call(any(Prompt.class)))
        .thenThrow(
            new org.springframework.web.client.ResourceAccessException(
                "private endpoint",
                new java.net.http.HttpConnectTimeoutException("private endpoint")));
    assertThatThrownBy(
            () ->
                gateway(45000)
                    .generate(
                        Fixtures.context("research_qa"),
                        Fixtures.evidence(),
                        Fixtures.JSON.createArrayNode()))
        .isInstanceOfSatisfying(
            AgentFailure.class, e -> assertThat(e.code()).isEqualTo("MODEL_CONNECT_TIMEOUT"));
  }

  @Test
  void cancellationIsSanitizedAndNotRetryable() {
    when(chatModel.call(any(Prompt.class)))
        .thenThrow(new java.util.concurrent.CancellationException("private body"));
    assertThatThrownBy(
            () ->
                gateway(45000)
                    .generate(
                        Fixtures.context("research_qa"),
                        Fixtures.evidence(),
                        Fixtures.JSON.createArrayNode()))
        .isInstanceOfSatisfying(
            AgentFailure.class,
            e -> {
              assertThat(e.code()).isEqualTo("MODEL_CALL_CANCELLED");
              assertThat(e.retryable()).isFalse();
            });
  }

  @Test
  void wrappedReadTimeoutHasSpecificSanitizedCode() {
    when(chatModel.call(any(Prompt.class)))
        .thenThrow(
            new org.springframework.web.client.ResourceAccessException(
                "private body", new java.net.http.HttpTimeoutException("private endpoint")));
    assertThatThrownBy(
            () ->
                gateway(45000)
                    .generate(
                        Fixtures.context("research_qa"),
                        Fixtures.evidence(),
                        Fixtures.JSON.createArrayNode()))
        .isInstanceOfSatisfying(
            AgentFailure.class,
            e -> {
              assertThat(e.code()).isEqualTo("MODEL_READ_TIMEOUT");
              assertThat(e.getMessage()).doesNotContain("private");
            });
  }

  ChatModel chatModel = mock(ChatModel.class);

  SpringAiModelGateway gateway(int limit) {
    return new SpringAiModelGateway(
        ChatClient.create(chatModel), Fixtures.JSON, new SimpleMeterRegistry(), limit);
  }

  @Test
  void parsesJsonObjectThroughActualChatClient() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(Fixtures.answer().toString())))));
    assertThat(
            gateway(45000)
                .generate(
                    Fixtures.context("research_qa"),
                    Fixtures.evidence(),
                    Fixtures.JSON.createArrayNode())
                .path("result")
                .isObject())
        .isTrue();
  }

  @Test
  void malformedOutputIsSanitized() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("secret-provider-body")))));
    assertThatThrownBy(
            () ->
                gateway(45000)
                    .generate(
                        Fixtures.context("research_qa"),
                        Fixtures.evidence(),
                        Fixtures.JSON.createArrayNode()))
        .isInstanceOf(AgentFailure.class)
        .hasMessageNotContaining("secret-provider-body");
  }

  @Test
  void inputBudgetFailsBeforeModelCall() {
    assertThatThrownBy(
            () ->
                gateway(10)
                    .generate(
                        Fixtures.context("research_qa"),
                        Fixtures.evidence(),
                        Fixtures.JSON.createArrayNode()))
        .isInstanceOf(AgentFailure.class);
    verify(chatModel, never()).call(any(Prompt.class));
  }
}
