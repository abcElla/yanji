package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.agent.backend.*;
import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.workflow.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceRetrieverTest {
  BackendClient backend = mock(BackendClient.class);
  EvidenceRetriever retriever =
      new EvidenceRetriever(
          Fixtures.JSON, new BusinessPolicy(Fixtures.JSON, Clock.systemUTC()), backend);
  Lease lease = new Lease(Fixtures.RUN, 1, "private");
  ResearchPlan plan =
      new ResearchPlan(
          "目标",
          List.of(new ResearchPlan.Query("重复 验证", "验证"), new ResearchPlan.Query("方法 参数", "方法")));

  ObjectNode result(ArrayNode chunks) {
    var value = Fixtures.JSON.createObjectNode();
    value.set("chunks", chunks);
    return value;
  }

  @Test
  void chineseFallbackUsesSearchTermsInsteadOfWholeQuestion() {
    var context = Fixtures.context("research_qa");
    ((ObjectNode) context.path("input")).put("question", "请检查三次重复实验以及数据划分是否满足要求？");
    when(backend.search(any(), any())).thenReturn(result(Fixtures.evidence()));
    retriever.retrieve(new ResearchPlan("检查", List.of()), context, lease, () -> {});
    var request = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
    verify(backend).search(eq(lease), request.capture());
    assertThat(request.getValue().path("query").asText()).isEqualTo("重复 数据 划分 实验");
  }

  @Test
  void searchesModelQueriesAndMergesOnlySnapshotEvidence() {
    when(backend.search(any(), any()))
        .thenReturn(result(Fixtures.JSON.createArrayNode()), result(Fixtures.evidence()));
    var context = Fixtures.context("research_qa");
    ((ObjectNode) context.path("subject")).put("stage_id", Fixtures.STAGE);
    var evidence = retriever.retrieve(plan, context, lease, () -> {});
    assertThat(evidence.chunks()).hasSize(1);
    assertThat(evidence.searches()).isEqualTo(2);
    verify(backend)
        .search(
            any(),
            argThat(
                body ->
                    body.path("query").asText().equals("重复 验证")
                        && body.path("stage_id").asText().equals(Fixtures.STAGE)
                        && body.path("top_k").asInt() == 4));
  }

  @Test
  void emptyEvidenceAllowsOnlyOneKeywordFallbackAndStops() {
    when(backend.search(any(), any())).thenReturn(result(Fixtures.JSON.createArrayNode()));
    assertThat(
            retriever.retrieve(plan, Fixtures.context("research_qa"), lease, () -> {}).searches())
        .isEqualTo(3);
    verify(backend, times(3)).search(any(), any());
  }

  @Test
  void duplicatesAndOutOfSnapshotAreExcludedAndFallbackIsReported() {
    var rows = Fixtures.evidence();
    rows.add(rows.get(0).deepCopy());
    var alien = (ObjectNode) rows.get(0).deepCopy();
    alien.put("source_revision", 99);
    rows.add(alien);
    var response = result(rows);
    response.put("retrieval_mode", "lexical_fallback");
    when(backend.search(any(), any())).thenReturn(response);
    var found = retriever.retrieve(plan, Fixtures.context("research_qa"), lease, () -> {});
    assertThat(found.chunks()).hasSize(1);
    assertThat(found.lexicalFallback()).isTrue();
    var original = Fixtures.evidence();
    ((ObjectNode) original.get(0)).put("text", "a".repeat(2000));
    assertThat(EvidenceRetriever.modelView(original).get(0).path("text").asText()).hasSize(1500);
    assertThat(original.get(0).path("text").asText()).hasSize(2000);
  }

  @Test
  void cancellationAfterSearchDoesNotContinueWithEvidence() {
    when(backend.search(any(), any())).thenReturn(result(Fixtures.evidence()));
    var counter = new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(
            () ->
                retriever.retrieve(
                    plan,
                    Fixtures.context("research_qa"),
                    lease,
                    () -> {
                      if (counter.incrementAndGet() == 2)
                        throw new AgentFailure("LEASE_EXPIRED", "取消", false, true);
                    }))
        .isInstanceOf(AgentFailure.class);
    verify(backend, times(1)).search(any(), any());
  }
}
