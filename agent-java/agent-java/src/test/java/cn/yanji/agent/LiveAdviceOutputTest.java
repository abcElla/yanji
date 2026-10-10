package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.ModelGateway;
import cn.yanji.agent.model.SpringAiModelGateway;
import cn.yanji.agent.workflow.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Synthetic-only, opt-in live model checks for the actual advice publication path. */
@EnabledIfSystemProperty(named = "liveAdviceEval", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveAdviceOutputTest {
  @Autowired ModelGateway model;

  @Test
  void sparseContextStillProducesReadableAnswersForAllFeatures() throws Exception {
    var rows = Fixtures.JSON.createArrayNode();
    Path output = Path.of(System.getProperty("adviceEvalOutput", "target/advice-output-live"));
    Files.createDirectories(output);
    String selectedKinds = System.getProperty("adviceEvalKinds", "");
    for (String kind :
        List.of(
            "research_followup",
            "research_qa",
            "risk_brief",
            "stage_plan",
            "weekly_report",
            "paper_guide")) {
      if (!selectedKinds.isBlank() && !List.of(selectedKinds.split(",")).contains(kind)) continue;
      var context = Fixtures.context(kind);
      context.put("advice_mode", true);
      context
          .withObject("/input")
          .put("question", "我准备比较基线模型和改进模型，但还没有整理实验记录，下一步怎么做？")
          .put("week_start", "2026-10-05");
      if (!List.of("research_followup", "research_qa").contains(kind))
        context.withObject("/input").remove("question");
      context.putObject("stage").put("title", "实验验证").put("goal", "比较基线与改进方案");
      context.putObject("paper").put("title", "比较基线与改进模型的实验设计");
      context.putArray("stage_rules");
      context
          .putArray("tasks")
          .addObject()
          .put("id", "baseline")
          .put("title", "完成基线对照")
          .put("status", "todo")
          .putNull("due_at")
          .putArray("dependency_ids");
      context
          .putObject("learning_policy")
          .put("constrained_reasoning_enabled", true)
          .put("structured_planning_enabled", true)
          .put("grounded_guidance_enabled", true);
      context = ConstraintAwarePlanning.prepare(context);
      var policy = new BusinessPolicy(Fixtures.JSON, Clock.systemUTC());
      assertThat(policy.preflight(context, true)).isNull();
      var evidence = Fixtures.JSON.createArrayNode();
      context = GroundedGuidance.prepare(context, evidence);
      var row =
          rows.addObject()
              .put("kind", kind)
              .put("prompt_version", SpringAiModelGateway.PROMPT_VERSION);
      try {
        long start = System.nanoTime();
        var raw = model.generate(context, evidence, policy.risks(context));
        row.set("raw_answer", raw.deepCopy());
        var answer = GroundedGuidance.publish(context, raw);
        var errors = new java.util.ArrayList<>(ConstraintAwarePlanning.violations(context, answer));
        errors.addAll(GroundedGuidance.violations(context, answer));
        if (!errors.isEmpty()) answer = GuidancePublication.forReview(context, answer, errors);
        var published =
            new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
                .prepare(answer, context, evidence, 1);
        row.set("published", published);
        row.set("review_issues", Fixtures.JSON.valueToTree(errors));
        row.put("latency_ms", (System.nanoTime() - start) / 1_000_000);
        assertThat(published.at("/result/summary").asText()).hasSizeGreaterThan(40);
        row.put("status", "succeeded");
      } finally {
        Fixtures.JSON
            .writerWithDefaultPrettyPrinter()
            .writeValue(output.resolve("observations.json").toFile(), rows);
      }
    }
  }
}
