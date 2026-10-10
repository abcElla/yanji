package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class RetrievalTest {
  @Test
  void paperGuideIncludesBodyEvenWhenOnlyTitleMatchesAndRemainsBounded() {
    var embedding = mock(EmbeddingService.class);
    var cache = mock(SearchCache.class);
    when(embedding.model()).thenReturn("qa-model");
    when(cache.get(anyString())).thenReturn(new SearchCache.Lookup(null, "disabled"));
    var service = new RetrievalService(embedding, cache, new MockEnvironment());
    var chunks = new ArrayList<Map<String, Object>>();
    for (int i = 1; i <= 5; i++)
      chunks.add(
          map(
              "id",
              "c" + i,
              "source_id",
              "paper",
              "source_revision",
              1,
              "chunk_id",
              "paragraph:" + i,
              "locator",
              map("kind", "docx_paragraph", "paragraph", i),
              "text",
              i == 1 ? "Guide Method title" : "正文原文段落" + i));
    Collections.reverse(chunks);
    var result =
        service.search(
            "actor",
            map("kind", "paper_guide"),
            map(),
            chunks,
            map("query", "Guide Method", "top_k", 3, "source_kinds", List.of("paper")));
    assertThat(list(result.get("chunks")).stream().map(c -> str(obj(c), "text")))
        .containsExactly("Guide Method title", "正文原文段落2", "正文原文段落3");
    assertThat(result.get("coverage_complete")).isEqualTo(false);
    assertThat(result.get("retrieval_mode")).isEqualTo("lexical");
    var regular =
        service.search(
            "actor",
            map("kind", "research_qa"),
            map(),
            chunks,
            map("query", "Guide Method", "top_k", 3));
    assertThat(list(regular.get("chunks"))).hasSize(1);
  }

  @Test
  void vectorOnlyHitsAreReturnedAndCacheKeysChangeWithScopeAndVersions() throws Exception {
    var embedding = mock(EmbeddingService.class);
    var cache = mock(SearchCache.class);
    when(embedding.model()).thenReturn("qa-model");
    when(embedding.indexed(anyMap())).thenReturn(true);
    when(embedding.embed(anyList())).thenReturn(List.of(List.of(1.0, 0.0)));
    when(cache.get(anyString())).thenReturn(new SearchCache.Lookup(null, "miss"));
    var service =
        new RetrievalService(
            embedding, cache, new MockEnvironment().withProperty("yanji.retrieval-mode", "hybrid"));
    var chunk =
        map(
            "id",
            "one",
            "source_id",
            "source",
            "source_revision",
            1,
            "chunk_id",
            "field:1",
            "text",
            "完全无词面匹配的材料",
            "embedding",
            List.of(1.0, 0.0),
            "index_version",
            "v1");
    var run = map("id", "run", "lab_id", "lab", "student_id", "student");
    var context = map("snapshot_id", "snapshot");
    var query = map("query", "quantum photon", "top_k", 1);
    var result = service.search("student", run, context, List.of(chunk), query);
    assertThat(result.get("retrieval_mode")).isEqualTo("hybrid");
    assertThat(list(result.get("chunks"))).hasSize(1);
    service.search("mentor", run, context, List.of(chunk), query);
    chunk.put("source_revision", 2);
    chunk.put("index_version", "v2");
    service.search("student", run, context, List.of(chunk), query);
    var keys = ArgumentCaptor.forClass(String.class);
    verify(cache, times(3)).get(keys.capture());
    assertThat(new HashSet<>(keys.getAllValues())).hasSize(3);
  }

  @Test
  void embeddingFailureReturnsExplicitLexicalFallback() throws Exception {
    var embedding = mock(EmbeddingService.class);
    var cache = mock(SearchCache.class);
    when(embedding.model()).thenReturn("qa-model");
    when(embedding.indexed(anyMap())).thenReturn(true);
    when(embedding.embed(anyList()))
        .thenThrow(new java.net.http.HttpTimeoutException("private-value"));
    when(cache.get(anyString())).thenReturn(new SearchCache.Lookup(null, "unavailable"));
    var service =
        new RetrievalService(
            embedding, cache, new MockEnvironment().withProperty("yanji.retrieval-mode", "hybrid"));
    var c =
        map(
            "id",
            "one",
            "source_id",
            "source",
            "source_revision",
            1,
            "chunk_id",
            "field:1",
            "text",
            "实验参数",
            "embedding",
            List.of(1.0));
    var result = service.search("actor", map(), map(), List.of(c), map("query", "实验"));
    assertThat(result.get("retrieval_mode")).isEqualTo("lexical");
    assertThat(result.get("fallback_reason")).isEqualTo("HTTP_TIMEOUT");
    assertThat(result.get("cache_status")).isEqualTo("unavailable");
    assertThat(list(result.get("chunks"))).hasSize(1);
    verify(cache, never()).put(anyString(), anyMap());
  }

  @Test
  void missingIndexDoesNotClaimHybridMode() {
    var embedding = mock(EmbeddingService.class);
    var cache = mock(SearchCache.class);
    when(embedding.model()).thenReturn("qa-model");
    when(cache.get(anyString())).thenReturn(new SearchCache.Lookup(null, "disabled"));
    var service =
        new RetrievalService(
            embedding, cache, new MockEnvironment().withProperty("yanji.retrieval-mode", "hybrid"));
    var result =
        service.search(
            "actor",
            map(),
            map(),
            List.of(
                map(
                    "id",
                    "one",
                    "source_id",
                    "source",
                    "source_revision",
                    1,
                    "chunk_id",
                    "field:1",
                    "text",
                    "实验")),
            map("query", "实验"));
    assertThat(result.get("retrieval_mode")).isEqualTo("lexical");
    assertThat(result.get("fallback_reason")).isEqualTo("INDEX_NOT_READY");
  }
}
