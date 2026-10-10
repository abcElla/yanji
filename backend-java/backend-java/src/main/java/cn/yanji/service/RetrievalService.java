package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import java.util.*;
import org.springframework.core.env.Environment;

@org.springframework.stereotype.Service
public class RetrievalService {
  final EmbeddingService embeddings;
  final SearchCache cache;
  final Environment env;

  public RetrievalService(EmbeddingService embeddings, SearchCache cache, Environment env) {
    this.embeddings = embeddings;
    this.cache = cache;
    this.env = env;
  }

  public Map<String, Object> search(
      String actor,
      Map<String, Object> run,
      Map<String, Object> snapshot,
      List<Map<String, Object>> eligible,
      Map<String, Object> query) {
    String mode = env.getProperty("yanji.retrieval-mode", "lexical");
    int top = Integer.parseInt(query.getOrDefault("top_k", 6).toString());
    // Called only after live ACL, scope, lease and snapshot/version validation.
    // Include authorization scope, immutable content/index versions and model identity in the key.
    String key =
        "yanji:search:v2:"
            + hash(
                json(
                    map(
                        "actor",
                        actor,
                        "lab",
                        run.get("lab_id"),
                        "student",
                        run.get("student_id"),
                        "snapshot",
                        snapshot.get("snapshot_id"),
                        "query",
                        query,
                        "mode",
                        mode,
                        "model",
                        embeddings.model(),
                        "chunks",
                        eligible.stream()
                            .sorted(Comparator.comparing(c -> str(c, "id")))
                            .map(
                                c ->
                                    map(
                                        "id",
                                        c.get("id"),
                                        "revision",
                                        c.get("source_revision"),
                                        "text_hash",
                                        hash(str(c, "text")),
                                        "index",
                                        c.get("index_version"),
                                        "expected_index",
                                        embeddings.indexVersion(str(c, "text")),
                                        "model",
                                        c.get("model_id")))
                            .toList())));
    var cached = cache.get(key);
    if (cached.value() != null
        && (eq(cached.value().get("retrieval_mode"), "hybrid") || mode.equals("lexical"))) {
      var result = new LinkedHashMap<>(cached.value());
      result.put("cache_status", "hit");
      return result;
    }
    Set<String> terms = InternalAgentService.terms(str(query, "query"));
    var lexical =
        eligible.stream()
            .filter(c -> LexicalSearch.score(str(c, "text"), terms) > 0)
            .sorted(
                Comparator.<Map<String, Object>>comparingLong(
                        c -> LexicalSearch.score(str(c, "text"), terms))
                    .reversed()
                    .thenComparing(c -> str(c, "source_id"))
                    .thenComparing(c -> str(c, "chunk_id")))
            .toList();
    var ranked = new ArrayList<Map<String, Object>>(lexical);
    var scores = new HashMap<String, Double>();
    for (var c : lexical)
      scores.put(str(c, "id"), (double) LexicalSearch.score(str(c, "text"), terms));
    String actual = "lexical", fallback = null;
    int indexed = (int) eligible.stream().filter(embeddings::indexed).count();
    if (mode.equals("hybrid") && !eligible.isEmpty()) {
      if (indexed == 0) fallback = "INDEX_NOT_READY";
      else
        try {
          var vector = embeddings.embed(List.of(str(query, "query"))).get(0);
          require(
              eligible.stream()
                  .filter(embeddings::indexed)
                  .allMatch(c -> list(c.get("embedding")).size() == vector.size()),
              503,
              "EMBEDDING_DIMENSION_MISMATCH",
              "向量索引维度不一致");
          var semantic = new ArrayList<Map<String, Object>>();
          var similarity = new HashMap<String, Double>();
          for (var c : eligible)
            if (embeddings.indexed(c)) {
              double cosine = cosine(vector, list(c.get("embedding")));
              if (cosine >= env.getProperty("yanji.vector-min-similarity", Double.class, 0.25)) {
                semantic.add(c);
                similarity.put(str(c, "id"), cosine);
              }
            }
          semantic.sort(
              Comparator.<Map<String, Object>>comparingDouble(c -> similarity.get(str(c, "id")))
                  .reversed()
                  .thenComparing(c -> str(c, "id")));
          scores.clear();
          var all = new LinkedHashMap<String, Map<String, Object>>();
          for (var list : List.of(lexical, semantic))
            for (int i = 0; i < list.size(); i++) {
              var c = list.get(i);
              all.put(str(c, "id"), c);
              scores.merge(str(c, "id"), 1.0 / (60 + i + 1), Double::sum);
            }
          ranked = new ArrayList<>(all.values());
          ranked.sort(
              Comparator.<Map<String, Object>>comparingDouble(c -> scores.get(str(c, "id")))
                  .reversed()
                  .thenComparing(c -> str(c, "id")));
          actual = "hybrid";
        } catch (Exception error) {
          fallback = LogDiagnostics.reason(error);
          if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    var hits = new ArrayList<Map<String, Object>>();
    var selected = new LinkedHashMap<String, Map<String, Object>>();
    boolean paperCoverage =
        eq(run.get("kind"), "paper_guide")
            && (list(query.get("source_kinds")).isEmpty()
                || list(query.get("source_kinds")).equals(List.of("paper")));
    if (paperCoverage) {
      // Include opening body positions even when a generic guide query only matches the title.
      // These are coverage samples, not assertions that the whole paper has been read.
      eligible.stream()
          .sorted(
              Comparator.<Map<String, Object>>comparingInt(
                      c -> {
                        var locator = obj(c.get("locator"));
                        Object position =
                            locator.getOrDefault("page", locator.getOrDefault("paragraph", 0));
                        return position instanceof Number n ? n.intValue() : 0;
                      })
                  .thenComparing(c -> str(c, "chunk_id")))
          .limit(Math.min(3, top))
          .forEach(c -> selected.put(str(c, "id"), c));
    }
    for (var c : ranked) {
      if (selected.size() >= top) break;
      selected.putIfAbsent(str(c, "id"), c);
    }
    for (var c : selected.values()) {
      var hit = pick(c, "source_id", "source_revision", "chunk_id", "text", "locator");
      hit.put("score", scores.getOrDefault(str(c, "id"), 0.0));
      hit.put(
          "selection_basis", paperCoverage ? "paper_coverage_and_relevance" : "query_relevance");
      hits.add(hit);
    }
    var result =
        map(
            "chunks",
            hits,
            "retrieval_mode",
            actual,
            "fallback_reason",
            fallback,
            "indexed_chunks",
            indexed,
            "eligible_chunks",
            eligible.size(),
            "returned_chunks",
            hits.size(),
            "coverage_complete",
            paperCoverage && !eligible.isEmpty() && hits.size() == eligible.size(),
            "cache_status",
            cached.status());
    if (fallback == null && !cached.status().equals("unavailable")) cache.put(key, result);
    org.slf4j.LoggerFactory.getLogger(RetrievalService.class)
        .info(
            "RAG search run_id={} mode={} indexed={} eligible={} cache={} fallback={}",
            run.get("id"),
            actual,
            indexed,
            eligible.size(),
            cached.status(),
            fallback == null ? "-" : fallback);
    return result;
  }

  static double cosine(List<Double> query, List<Object> values) {
    if (query.size() != values.size()) return -1;
    double dot = 0, a = 0, b = 0;
    for (int i = 0; i < query.size(); i++) {
      if (!(values.get(i) instanceof Number)) return -1;
      double x = query.get(i), y = ((Number) values.get(i)).doubleValue();
      if (!Double.isFinite(y)) return -1;
      dot += x * y;
      a += x * x;
      b += y * y;
    }
    return a > 0 && b > 0 ? dot / Math.sqrt(a * b) : -1;
  }
}
