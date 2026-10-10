package cn.yanji.agent;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.model.ModelGateway;
import cn.yanji.agent.model.SpringAiModelGateway;
import cn.yanji.agent.workflow.ConstraintAwarePlanning;
import cn.yanji.agent.workflow.GroundedGuidance;
import cn.yanji.agent.workflow.SemanticFeedback;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Opt-in billable model evaluation. Produces observations for independent rubric scoring, never a
 * claimed gain.
 */
@EnabledIfSystemProperty(named = "liveFeedbackEval", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveFeedbackLearningEvaluationTest {
  @Autowired ModelGateway model;
  @Autowired Environment environment;
  @Autowired MeterRegistry metrics;

  @Test
  void exportPairedObservations() throws Exception {
    var cases =
        Fixtures.JSON.readTree(
            getClass().getResourceAsStream("/evals/feedback-learning-cases.synthetic.json"));
    var output = Path.of(System.getProperty("feedbackEvalOutput", "target/feedback-evaluation"));
    Files.createDirectories(output);
    int repeats = Integer.getInteger("feedbackEvalRepeats", 3);
    if (repeats < 1 || repeats > 10)
      throw new IllegalArgumentException("repeats must be between 1 and 10");
    var records = Fixtures.JSON.createArrayNode();
    Random order = new Random(42);
    for (int repeat = 0; repeat < repeats; repeat++)
      for (var example : cases) {
        String selection = System.getProperty("feedbackEvalCases", "");
        if (!selection.isBlank()
            && !Arrays.asList(selection.split(",")).contains(example.path("id").asText())) continue;
        var variants = new ArrayList<>(List.of("baseline", "constrained", "feedback"));
        Collections.shuffle(variants, order);
        for (String variant : variants) {
          var context = Fixtures.context("research_followup");
          context.putObject("input").put("question", example.path("question").asText());
          context.putObject("stage").put("title", example.path("stage").asText());
          context.putObject("stage_rules").put("requirements", example.path("rules").asText());
          var constraints = context.putArray("mentor_constraints");
          if (!example.path("current_feedback").asText().isBlank())
            constraints.addObject().put("content", example.path("current_feedback").asText());
          context
              .putObject("learning_policy")
              .put("feedback_learning_enabled", variant.equals("feedback"))
              .put("constrained_reasoning_enabled", !variant.equals("baseline"))
              .put("structured_planning_enabled", !variant.equals("baseline"))
              .put("grounded_guidance_enabled", !variant.equals("baseline"));
          if (example.has("completed_task"))
            context
                .withArray("/tasks")
                .addObject()
                .put("id", "completed-1")
                .put("title", example.path("completed_task").asText())
                .put("status", "completed");
          context
              .putArray("feedback_memories")
              .addObject()
              .put("lesson", example.path("history").asText())
              .put("origin_kind", "synthetic_history");
          if (example.path("tasks").isArray())
            context.set("tasks", example.path("tasks").deepCopy());
          context = ConstraintAwarePlanning.prepare(context);
          context = GroundedGuidance.prepare(context, Fixtures.JSON.createArrayNode());
          var row =
              records
                  .addObject()
                  .put("case_id", example.path("id").asText())
                  .put("repeat", repeat)
                  .put("variant", variant)
                  .put("dataset_type", "synthetic")
                  .put("prompt_version", SpringAiModelGateway.PROMPT_VERSION)
                  .put(
                      "temperature",
                      environment.getProperty(
                          "spring.ai.openai.chat.options.temperature", "unknown"))
                  .put(
                      "dataset_sha256",
                      java.util.HexFormat.of()
                          .formatHex(
                              java.security.MessageDigest.getInstance("SHA-256")
                                  .digest(
                                      cases
                                          .toString()
                                          .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                  .put(
                      "model",
                      environment.getProperty("spring.ai.openai.chat.options.model", "unknown"));
          row.set("rubric", example.path("rubric"));
          row.putObject(
              "scores"); // Fill only after independent review; absent scores cannot produce an
          // accuracy.
          long start = System.nanoTime();
          double before = tokens();
          int calls = 0;
          try {
            if (variant.equals("feedback")) {
              calls++;
              SemanticFeedback.attach(context, model.extractFeedback(context));
              row.set(
                  "extracted_feedback_interpretations",
                  context.path("feedback_interpretations").deepCopy());
            }
            calls++;
            var answer =
                model.generate(
                    context, Fixtures.JSON.createArrayNode(), Fixtures.JSON.createArrayNode());
            row.set("initial_answer", answer.deepCopy());
            row.set(
                "initial_guidance_violations",
                Fixtures.JSON.valueToTree(GroundedGuidance.violations(context, answer)));
            answer = GroundedGuidance.publish(context, answer);
            var judge = context.deepCopy();
            judge.withObject("/learning_policy").put("constrained_reasoning_enabled", true);
            judge = ConstraintAwarePlanning.prepare(judge);
            row.set(
                "initial_constraint_violations",
                Fixtures.JSON.valueToTree(ConstraintAwarePlanning.violations(judge, answer)));
            var violations = new ArrayList<>(ConstraintAwarePlanning.violations(context, answer));
            violations.addAll(GroundedGuidance.violations(context, answer));
            if (!violations.isEmpty() && calls < 2) {
              calls++;
              answer =
                  model.generate(
                      ConstraintAwarePlanning.repairContext(context, violations),
                      Fixtures.JSON.createArrayNode(),
                      Fixtures.JSON.createArrayNode());
              answer = GroundedGuidance.publish(context, answer);
              violations = new ArrayList<>(ConstraintAwarePlanning.violations(context, answer));
              violations.addAll(GroundedGuidance.violations(context, answer));
            }
            if (!violations.isEmpty()) answer = GroundedGuidance.fallback(context, violations);
            row.set(
                "final_constraint_violations",
                Fixtures.JSON.valueToTree(ConstraintAwarePlanning.violations(judge, answer)));
            row.set("answer", answer);
            row.set(
                "validated_feedback_interpretations",
                SemanticFeedback.accept(context, answer.at("/result/feedback_interpretations")));
            row.set(
                "final_guidance_violations",
                Fixtures.JSON.valueToTree(GroundedGuidance.violations(context, answer)));
            row.put("status", "succeeded");
          } catch (RuntimeException error) {
            row.put("status", "failed").put("error_type", error.getClass().getSimpleName());
            if (error instanceof AgentFailure failure) row.put("error_code", failure.code());
          }
          row.put("latency_ms", (System.nanoTime() - start) / 1_000_000)
              .put("tokens", tokens() - before)
              .put("token_usage_observed", tokens() > before)
              .put("model_calls", calls)
              .put("max_model_calls", 2);
          Fixtures.JSON
              .writerWithDefaultPrettyPrinter()
              .writeValue(output.resolve("observations.synthetic.json").toFile(), records);
        }
      }
  }

  private double tokens() {
    return metrics.find("yanji.agent.tokens").counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }
}
