package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.SpringAiModelGateway;
import cn.yanji.agent.workflow.BusinessPolicy;
import com.fasterxml.jackson.databind.node.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

/** Billable authorized-snapshot comparison. Fixtures and outputs stay outside version control. */
@EnabledIfSystemProperty(named = "liveWeeklyInput", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LiveWeeklyInputTest {
  @Autowired ChatClient.Builder builder;
  @Autowired OutputGuard guard;

  @Test
  void compareFullInputAndThreeCompactedReplays() throws Exception {
    Path directory = Path.of(System.getProperty("weeklyFixtureDir"));
    ObjectNode context =
        (ObjectNode)
            Fixtures.JSON.readTree(directory.resolve("weekly-context.private.json").toFile());
    BusinessPolicy policy = new BusinessPolicy(Fixtures.JSON, Clock.systemUTC());
    assertThat(policy.preflight(context)).isNull();
    ArrayNode evidence = Fixtures.JSON.createArrayNode();
    for (var chunk :
        Fixtures.JSON
            .readTree(
                directory
                    .resolve(System.getProperty("weeklyEvidenceFile", "weekly-facts.private.json"))
                    .toFile())
            .path("chunks"))
      if (policy.evidenceInBusinessScope(chunk, context)) evidence.add(chunk);
    assertThat(evidence).isNotEmpty();
    ObjectNode modelContext = context.deepCopy();
    for (String field :
        List.of(
            "requested_by",
            "lab_id",
            "student_id",
            "run_id",
            "snapshot_id",
            "business_versions",
            "source_versions")) modelContext.remove(field);
    var records = Fixtures.JSON.createArrayNode();
    var full = Fixtures.JSON.createObjectNode();
    full.set("context", modelContext);
    full.set("evidence", evidence);
    full.putArray("rule_risks");
    String system =
        new ClassPathResource("prompts/system-v1.txt").getContentAsString(StandardCharsets.UTF_8);
    String business =
        new ClassPathResource("prompts/weekly_report-v1.txt")
            .getContentAsString(StandardCharsets.UTF_8);
    long started = System.nanoTime();
    ObjectNode baseline =
        records.addObject().put("variant", "full").put("input_chars", full.toString().length());
    try {
      var response =
          builder
              .build()
              .prompt()
              .system(system + "\n" + business)
              .user(full.toString())
              .options(
                  OpenAiChatOptions.builder()
                      .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                      .build())
              .call()
              .chatResponse();
      baseline.put("input_tokens", response.getMetadata().getUsage().getPromptTokens());
      baseline.put("output_tokens", response.getMetadata().getUsage().getCompletionTokens());
      var validated =
          guard.prepare(
              (ObjectNode) Fixtures.JSON.readTree(response.getResult().getOutput().getText()),
              context,
              evidence,
              1);
      baseline.put("outcome", validated.at("/result/outcome").asText());
    } catch (Exception error) {
      baseline.put("outcome", "failed").put("error_class", error.getClass().getSimpleName());
    }
    baseline.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
    int valid = 0;
    int replays = Integer.getInteger("weeklyReplays", 3);
    for (int i = 0; i < replays; i++) {
      var metrics = new SimpleMeterRegistry();
      var gateway = new SpringAiModelGateway(builder.build(), Fixtures.JSON, metrics, 45000);
      ObjectNode record = records.addObject().put("variant", "compact").put("replay", i + 1);
      started = System.nanoTime();
      try {
        var raw = gateway.generate(modelContext, evidence, Fixtures.JSON.createArrayNode());
        Fixtures.JSON.writeValue(directory.resolve("weekly-output.private.json").toFile(), raw);
        var result = guard.prepare(raw, context, evidence, 1);
        record.put("outcome", result.at("/result/outcome").asText());
        record.put("proposal_count", result.path("proposal_drafts").size());
        record.put(
            "input_tokens",
            metrics.get("yanji.agent.tokens").tag("direction", "input").counter().count());
        record.put(
            "output_tokens",
            metrics.get("yanji.agent.tokens").tag("direction", "output").counter().count());
        if ("ok".equals(result.at("/result/outcome").asText())
            && !result.path("proposal_drafts").isEmpty()) valid++;
      } catch (cn.yanji.agent.contract.AgentFailure error) {
        record.put("outcome", "failed").put("code", error.code()).put("reason", error.getMessage());
      }
      record.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
      Fixtures.JSON
          .writerWithDefaultPrettyPrinter()
          .writeValue(directory.resolve("weekly-comparison.safe.json").toFile(), records);
    }
    assertThat(valid).isEqualTo(replays);
  }
}
