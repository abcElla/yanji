package cn.yanji.agent;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.model.ModelGateway;
import cn.yanji.agent.model.SpringAiModelGateway;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Same 85 frozen ARIES labels; labels and GPT edits never enter model requests. */
@EnabledIfSystemProperty(named = "liveAriesEval", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PublicSemanticFeedbackEvaluationTest {
  @Autowired ModelGateway model;
  @Autowired Environment environment;
  @Autowired MeterRegistry metrics;

  @Test
  void evaluateOriginalExpertLabelsWithoutFitting() throws Exception {
    var path = Path.of(System.getProperty("ariesDatasetPath"));
    var output = Path.of(System.getProperty("ariesEvalOutput", "target/aries-semantic"));
    Files.createDirectories(output);
    var examples = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    for (String line : Files.readAllLines(path)) {
      var row = Fixtures.JSON.readTree(line);
      if (row.at("/annotations/comment_is_direct").isNumber()) examples.add(row);
    }
    var predictions = Fixtures.JSON.createArrayNode();
    double before = tokens();
    long start = System.nanoTime();
    int calls = 0;
    for (int offset = 0; offset < examples.size(); ) {
      var context = Fixtures.JSON.createObjectNode();
      context.putObject("learning_policy").put("feedback_learning_enabled", true);
      var memories = context.putArray("feedback_memories");
      int end = offset;
      while (end < examples.size() && end - offset < 5) {
        String comment = examples.get(end).path("comment").asText();
        if (end > offset && context.toString().length() + comment.length() > 10000) break;
        memories.addObject().put("lesson", comment).put("reuse_scope", "project_local");
        end++;
      }
      var interpreted = Fixtures.JSON.createArrayNode();
      String failure = "";
      calls++;
      try {
        interpreted = model.extractFeedback(context);
      } catch (RuntimeException error) {
        failure = error instanceof AgentFailure f ? f.code() : error.getClass().getSimpleName();
      }
      for (int i = offset; i < end; i++) {
        var example = examples.get(i);
        var row =
            predictions
                .addObject()
                .put("doc_id", example.path("doc_id").asText())
                .put("comment_id", example.path("comment_id").asInt())
                .put("gold_direct", example.at("/annotations/comment_is_direct").asInt());
        com.fasterxml.jackson.databind.JsonNode found = null;
        for (var item : interpreted)
          if (item.path("memory_index").asInt(-1) == i - offset) {
            found = item;
            break;
          }
        if (found == null)
          row.put("status", "unclassified")
              .put("error_code", failure.isBlank() ? "MISSING_VALID_INTERPRETATION" : failure);
        else {
          row.put("status", "classified")
              .put("predicted_direct", found.path("direct_request").asBoolean());
          row.set("interpretation", found);
        }
      }
      offset = end;
      Fixtures.JSON
          .writerWithDefaultPrettyPrinter()
          .writeValue(output.resolve("predictions.json").toFile(), predictions);
    }
    int tp = 0, fp = 0, tn = 0, fn = 0, missing = 0, missingPositive = 0;
    for (var r : predictions) {
      boolean gold = r.path("gold_direct").asInt() == 1;
      if (!r.has("predicted_direct")) {
        missing++;
        if (gold) missingPositive++;
        continue;
      }
      boolean pred = r.path("predicted_direct").asBoolean();
      if (gold && pred) tp++;
      else if (gold) fn++;
      else if (pred) fp++;
      else tn++;
    }
    double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp),
        recall = tp + fn + missingPositive == 0 ? 0 : (double) tp / (tp + fn + missingPositive);
    var report =
        Fixtures.JSON
            .createObjectNode()
            .put("dataset", "ARIES expert directness, frozen 85 labels")
            .put("samples", examples.size())
            .put("model", environment.getProperty("spring.ai.openai.chat.options.model", "unknown"))
            .put("prompt_version", SpringAiModelGateway.PROMPT_VERSION)
            .put(
                "source_sha256",
                HexFormat.of()
                    .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path))))
            .put("accuracy", (double) (tp + tn) / examples.size())
            .put("precision", precision)
            .put("recall", recall)
            .put("f1", precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall))
            .put("unclassified", missing)
            .put("prediction_coverage", (double) (examples.size() - missing) / examples.size())
            .put("model_calls", calls)
            .put("observed_tokens", tokens() - before)
            .put("total_latency_ms", (System.nanoTime() - start) / 1000000);
    report
        .putObject("classified_confusion")
        .put("tp", tp)
        .put("fp", fp)
        .put("tn", tn)
        .put("fn", fn);
    report
        .putArray("limitations")
        .add(
            "Missing predictions count as incorrect in accuracy; recall includes unclassified positives")
        .add("Not full agent accuracy; no scientific truth or edit quality measured")
        .add(
            "No training or parameter tuning on this test set; only comments enter model requests");
    Fixtures.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("metrics.json").toFile(), report);
  }

  private double tokens() {
    return metrics.find("yanji.agent.tokens").counters().stream().mapToDouble(c -> c.count()).sum();
  }
}
