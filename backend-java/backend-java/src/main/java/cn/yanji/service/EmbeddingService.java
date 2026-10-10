package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import cn.yanji.mapper.Repository;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

@org.springframework.stereotype.Service
public class EmbeddingService {
  final Environment env;
  final Repository db;
  final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(3))
          .build();

  public EmbeddingService(Environment env, Repository db) {
    this.env = env;
    this.db = db;
  }

  public String model() {
    return env.getProperty("yanji.embedding-model", "BAAI/bge-small-zh-v1.5");
  }

  public String indexVersion(String text) {
    // Existing deployed MySQL uses VARCHAR(50); 160-bit truncated SHA-256 fits without a schema
    // migration.
    return hash(model() + ":" + env.getProperty("yanji.embedding-version", "1") + ":" + text)
        .substring(0, 40);
  }

  public List<List<Double>> embed(List<String> input) throws Exception {
    URI uri =
        URI.create(env.getProperty("yanji.embedding-url", "http://127.0.0.1:8092/v1/embeddings"));
    require(
        Set.of("http", "https").contains(uri.getScheme())
            && uri.getHost() != null
            && uri.getUserInfo() == null
            && uri.getQuery() == null
            && uri.getFragment() == null,
        503,
        "EMBEDDING_NOT_CONFIGURED",
        "Embedding 地址无效");
    require(
        uri.getScheme().equals("https")
            || Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()),
        503,
        "EMBEDDING_NOT_CONFIGURED",
        "远程 Embedding 必须使用 HTTPS");
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json");
    String key = env.getProperty("yanji.embedding-key", "");
    if (!key.isBlank()) builder.header("Authorization", "Bearer " + key);
    var response =
        client.send(
            builder
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json(map("model", model(), "input", input))))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      require(response.statusCode() == 200, 503, "EMBEDDING_UNAVAILABLE", "Embedding 服务暂不可用");
      byte[] bytes = body.readNBytes(4 * 1024 * 1024 + 1);
      require(bytes.length <= 4 * 1024 * 1024, 503, "EMBEDDING_RESPONSE_INVALID", "Embedding 响应过大");
      var rows =
          list(obj(parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))).get("data"));
      require(rows.size() == input.size(), 503, "EMBEDDING_RESPONSE_INVALID", "Embedding 数量不匹配");
      var output = new ArrayList<List<Double>>(Collections.nCopies(input.size(), null));
      int dimensions = -1;
      for (Object row : rows) {
        var data = obj(row);
        int index = num(data, "index");
        var values = list(data.get("embedding"));
        require(
            index >= 0
                && index < input.size()
                && output.get(index) == null
                && values.size() > 0
                && values.size() <= 4096,
            503,
            "EMBEDDING_RESPONSE_INVALID",
            "Embedding 结构无效");
        var vector = new ArrayList<Double>();
        double norm = 0;
        for (Object value : values) {
          require(value instanceof Number, 503, "EMBEDDING_RESPONSE_INVALID", "Embedding 数值无效");
          double v = ((Number) value).doubleValue();
          require(Double.isFinite(v), 503, "EMBEDDING_RESPONSE_INVALID", "Embedding 数值无效");
          vector.add(v);
          norm += v * v;
        }
        require(
            norm > 0 && (dimensions < 0 || dimensions == vector.size()),
            503,
            "EMBEDDING_RESPONSE_INVALID",
            "Embedding 维度无效");
        dimensions = vector.size();
        output.set(index, vector);
      }
      return output;
    }
  }

  public boolean indexed(Map<String, Object> chunk) {
    return eq(chunk.get("model_id"), model())
        && eq(chunk.get("index_version"), indexVersion(str(chunk, "text")))
        && !list(chunk.get("embedding")).isEmpty();
  }

  @Scheduled(fixedDelay = 15000)
  public void indexPending() {
    if (!env.getProperty("yanji.workers-enabled", Boolean.class, true)
        || !"hybrid".equals(env.getProperty("yanji.retrieval-mode", "lexical"))) return;
    try {
      var pending = new ArrayList<Map<String, Object>>();
      // Read immutable chunks; only current, non-revoked source revisions are indexed.
      for (var chunk : db.query("chunks", "ORDER BY created_at,id")) {
        var source = db.get("sources", str(chunk, "source_id"));
        if (source != null
            && source.get("revoked_at") == null
            && eq(source.get("active_revision"), chunk.get("source_revision"))
            && !indexed(chunk)) pending.add(chunk);
        if (pending.size() == 16) break;
      }
      if (pending.isEmpty()) return;
      var vectors = embed(pending.stream().map(c -> str(c, "text")).toList());
      for (int i = 0; i < pending.size(); i++) {
        var c = pending.get(i);
        db.update(
            "chunks",
            map(
                "id",
                c.get("id"),
                "embedding",
                vectors.get(i),
                "model_id",
                model(),
                "index_version",
                indexVersion(str(c, "text"))));
      }
      org.slf4j.LoggerFactory.getLogger(EmbeddingService.class)
          .info("RAG index batch completed chunks={} model={}", pending.size(), model());
    } catch (Exception error) {
      org.slf4j.LoggerFactory.getLogger(EmbeddingService.class)
          .warn(
              "RAG indexing deferred reason={} exception={}",
              LogDiagnostics.reason(error),
              LogDiagnostics.exceptionType(error));
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
    }
  }
}
