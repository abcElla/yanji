package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.mapper.Repository;
import java.util.*;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

@EnabledIfSystemProperty(named = "verify.local.rag", matches = "true")
class LocalRagVerificationTest {
  @Test
  void realChineseEmbeddingsRedisTtlAndRecovery() throws Exception {
    var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379),
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
    factory.afterPropertiesSet();
    factory.start();
    var redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
    var keys = new HashSet<String>();
    var env =
        new MockEnvironment()
            .withProperty("yanji.retrieval-mode", "hybrid")
            .withProperty("yanji.redis-enabled", "true");
    var embeddings = new EmbeddingService(env, mock(Repository.class));
    var cache =
        new SearchCache(redis, env) {
          @Override
          public void put(String key, Map<String, Object> value) {
            keys.add(key);
            super.put(key, value);
          }
        };
    try {
      String text = "控制实验重复性需要固定参数并进行多轮测试";
      var vector = embeddings.embed(List.of(text)).get(0);
      assertThat(vector).hasSize(512);
      var chunk =
          map(
              "id",
              uid(),
              "source_id",
              uid(),
              "source_revision",
              1,
              "chunk_id",
              "field:1",
              "text",
              text,
              "embedding",
              vector,
              "model_id",
              embeddings.model(),
              "index_version",
              embeddings.indexVersion(text));
      var context = map("snapshot_id", uid());
      var run = map("id", uid(), "lab_id", uid(), "student_id", uid());
      var query = map("query", "怎么使研究更稳定", "top_k", 1);
      var service = new RetrievalService(embeddings, cache, env);
      var first = service.search("qa-actor", run, context, List.of(chunk), query);
      assertThat(first.get("retrieval_mode")).isEqualTo("hybrid");
      assertThat(first.get("cache_status")).isEqualTo("miss");
      assertThat(list(first.get("chunks"))).hasSize(1);
      assertThat(
              service.search("qa-actor", run, context, List.of(chunk), query).get("cache_status"))
          .isEqualTo("hit");
      for (String key : keys) assertThat(redis.getExpire(key)).isBetween(1L, 60L);
      assertThat(
              service
                  .search("other-actor", run, context, List.of(chunk), query)
                  .get("cache_status"))
          .isEqualTo("miss");
      String phaseDirectory = System.getProperty("verify.local.redis.outage.phases");
      if (phaseDirectory != null) {
        Path phases = Path.of(phaseDirectory);
        Files.createDirectories(phases);
        Files.writeString(phases.resolve("ready"), "ready");
        awaitPhase(phases.resolve("down"));
        var offline = service.search("qa-actor", run, context, List.of(chunk), query);
        assertThat(offline.get("cache_status")).isEqualTo("unavailable");
        assertThat(offline.get("retrieval_mode")).isEqualTo("hybrid");
        assertThat(list(offline.get("chunks"))).hasSize(1);
        Files.writeString(phases.resolve("offline-passed"), "passed");
        awaitPhase(phases.resolve("up"));
        // Reuse the same connection factory and retrieval service after the real restart.
        long reconnectDeadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
          try (var connection = redis.getConnectionFactory().getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); break; }
          catch (Exception unavailableYet) {
            if (System.nanoTime() >= reconnectDeadline) throw unavailableYet;
            Thread.sleep(200);
          }
        }
        var recoveredContext = map("snapshot_id", uid());
        var recovered = service.search("qa-actor", run, recoveredContext, List.of(chunk), query);
        assertThat(recovered.get("cache_status")).isEqualTo("miss");
        assertThat(list(recovered.get("chunks"))).hasSize(1);
        assertThat(service.search("qa-actor", run, recoveredContext, List.of(chunk), query).get("cache_status"))
            .isEqualTo("hit");
        Files.writeString(phases.resolve("recovery-passed"), "passed");
      }
      var down =
          new MockEnvironment()
              .withProperty("yanji.retrieval-mode", "hybrid")
              .withProperty("yanji.embedding-url", "http://127.0.0.1:1/v1/embeddings");
      var fallback =
          new RetrievalService(
                  new EmbeddingService(down, mock(Repository.class)),
                  new SearchCache(redis, down),
                  down)
              .search("qa-actor", run, context, List.of(chunk), map("query", "实验"));
      assertThat(fallback.get("retrieval_mode")).isEqualTo("lexical");
      assertThat(fallback.get("fallback_reason")).isNotNull();
      assertThat(list(fallback.get("chunks"))).hasSize(1);
      var unavailable = mock(StringRedisTemplate.class);
      when(unavailable.opsForValue()).thenThrow(new IllegalStateException("private-value"));
      var noCache =
          new RetrievalService(embeddings, new SearchCache(unavailable, env), env)
              .search("qa-actor", run, context, List.of(chunk), query);
      assertThat(noCache.get("cache_status")).isEqualTo("unavailable");
      assertThat(noCache.get("retrieval_mode")).isEqualTo("hybrid");
    } finally {
      if (!keys.isEmpty()) redis.delete(keys);
      factory.destroy();
    }
  }
  private void awaitPhase(Path signal) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
    while (!Files.exists(signal)) {
      if (System.nanoTime() >= deadline) throw new IllegalStateException("Redis outage phase timed out: " + signal.getFileName());
      Thread.sleep(200);
    }
  }
}
