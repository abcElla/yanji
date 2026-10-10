package cn.yanji.agent;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.model.ModelGateway;
import cn.yanji.agent.model.SpringAiModelGateway;
import cn.yanji.agent.workflow.*;
import com.fasterxml.jackson.databind.node.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Opt-in, billable public-data generation-module pilot; never writes backend business records. */
@EnabledIfSystemProperty(named = "livePublicScienceEval", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LivePublicScienceEvaluationTest {
  @Autowired ModelGateway model;
  @Autowired Environment environment;
  @Autowired MeterRegistry metrics;
  @Autowired ChatModel provider;

  @Test
  void exportPairedPublicScienceObservations() throws Exception {
    // Identical format-only adapter in all three arms. Production prompts remain unchanged.
    // The UI prompt otherwise suppresses machine-facing answer labels as technical identifiers.
    model =
        new SpringAiModelGateway(
            ChatClient.builder(
                    new ChatModel() {
                      @Override
                      public ChatResponse call(Prompt prompt) {
                        var messages = new ArrayList<Message>(prompt.getInstructions());
                        messages.add(
                            new SystemMessage(
                                "本轮是公开数据集的离线机器评分。仅调整输出格式："
                                    + "当问题要求FINAL_LABEL、FINAL_ANSWER、FINAL_EVIDENCE时，必须在result.summary末尾"
                                    + "按要求逐行保留这些标记，英文答案用英文；这些是本次评测结果，不是软件内部编号。"
                                    + "不要另设顶层字段，不改变证据范围、任务约束或判断规则。其他JSON字段保持现有协议。"));
                        return provider.call(new Prompt(messages, prompt.getOptions()));
                      }
                    })
                .build(),
            Fixtures.JSON,
            metrics,
            45000);
    Path root = Path.of(System.getProperty("publicScienceEvalRoot"));
    byte[] inputBytes = Files.readAllBytes(root.resolve("inputs.json"));
    var dataset = Fixtures.JSON.readTree(inputBytes);
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(inputBytes));
    String selectedDataset = System.getProperty("publicScienceEvalDataset", "");
    if (!selectedDataset.isBlank() && !List.of("SciFact", "QASPER").contains(selectedDataset))
      throw new IllegalArgumentException("Unsupported dataset selection");
    int shardCount = Integer.getInteger("publicScienceEvalShardCount", 1);
    int shardIndex = Integer.getInteger("publicScienceEvalShardIndex", 0);
    if (shardCount < 1 || shardCount > 3 || shardIndex < 0 || shardIndex >= shardCount)
      throw new IllegalArgumentException("Invalid evaluation shard");
    Path output =
        root.resolve(
            selectedDataset.isBlank()
                ? "observations.json"
                : "observations."
                    + selectedDataset
                    + (shardCount == 1 ? "" : ".shard" + shardIndex)
                    + ".json");
    Path parentOutput = root.resolve("observations." + selectedDataset + ".json");
    Path resume =
        Files.exists(output)
            ? output
            : Files.exists(parentOutput) ? parentOutput : root.resolve("observations.json");
    var records =
        Files.exists(resume)
            ? (ArrayNode) Fixtures.JSON.readTree(Files.readAllBytes(resume))
            : Fixtures.JSON.createArrayNode();
    if (!selectedDataset.isBlank()) {
      var owned = Fixtures.JSON.createArrayNode();
      for (var previous : records)
        if (previous.path("dataset").asText().equals(selectedDataset)) owned.add(previous);
      records = owned;
    }
    var feedbackPath = root.resolve("feedback-preparation.json");
    var preparations =
        Files.exists(feedbackPath)
            ? (ObjectNode) Fixtures.JSON.readTree(Files.readAllBytes(feedbackPath))
            : Fixtures.JSON.createObjectNode();
    // Learn bounded interpretations once per dataset, with held-out questions and gold absent.
    for (String name : List.of("SciFact", "QASPER")) {
      if (preparations.has(name)) continue;
      var c = Fixtures.context("research_followup");
      c.putObject("learning_policy").put("feedback_learning_enabled", true);
      c.set("feedback_memories", dataset.path("training_feedback").path(name).deepCopy());
      double before = tokens();
      long start = System.nanoTime();
      var row = preparations.putObject(name);
      try {
        row.set("interpretations", model.extractFeedback(c));
        row.put("status", "succeeded");
      } catch (RuntimeException error) {
        row.put("status", "failed").putArray("interpretations");
        if (error instanceof AgentFailure f) row.put("error_code", f.code());
      }
      row.put("tokens", tokens() - before)
          .put("latency_ms", (System.nanoTime() - start) / 1_000_000);
      Fixtures.JSON
          .writerWithDefaultPrettyPrinter()
          .writeValue(feedbackPath.toFile(), preparations);
    }
    Random order = new Random(20261010);
    int selectedIndex = 0;
    int limit = Integer.getInteger("publicScienceEvalLimit", Integer.MAX_VALUE);
    int index = 0;
    for (var example : dataset.path("cases")) {
      if (index++ >= limit) break;
      var variants = new ArrayList<>(List.of("baseline", "constrained", "feedback"));
      Collections.shuffle(variants, order);
      if (!selectedDataset.isBlank() && !example.path("dataset").asText().equals(selectedDataset))
        continue;
      if ((selectedIndex++ % shardCount) != shardIndex) continue;
      for (String variant : variants) {
        String id = example.path("id").asText();
        boolean done = false;
        for (var previous : records)
          if (previous.path("case_id").asText().equals(id)
              && previous.path("variant").asText().equals(variant)) {
            if (!previous.path("input_sha256").asText().equals(hash))
              throw new IllegalArgumentException(
                  "Frozen input hash changed; use a new output directory");
            done = true;
          }
        if (done) continue;
        String name = example.path("dataset").asText();
        var c = Fixtures.context("research_followup");
        c.put("advice_mode", true);
        String instruction =
            name.equals("SciFact")
                ? "核验以下科学陈述与所给摘要的关系。只判断所给材料，不使用外部知识。\nClaim: "
                    + example.path("question").asText()
                    + "\n在summary末尾独立一行写 FINAL_LABEL: SUPPORT 或 CONTRADICT 或 NOT_ENOUGH_INFO。"
                    + "SUPPORT为原文支持，CONTRADICT为原文反驳，NOT_ENOUGH_INFO为原文证据不足。"
                : "根据所给论文片段回答以下问题，简短英文答案尽量沿用原文措辞。\nQuestion: "
                    + example.path("question").asText()
                    + "\n在summary末尾独立一行写 FINAL_ANSWER: 英文短答案。"
                    + "是非回答用Yes或No，片段无法回答时用Unanswerable，不自行补充论文未提供的信息。";
        instruction +=
            "\n另用独立一行写 FINAL_EVIDENCE: 实际支持答案的片段编号，以逗号分隔，"
                + "没有支持证据则写NONE。这些编号见每个片段开头，不是引用编号；不要写占位符。"
                + "\n给出简短理由即可；此为阅读与证据核对，不创建任务，next_steps和proposal_drafts为空。";
        c.putObject("input").put("question", instruction);
        c.putObject("stage").put("title", "论文证据核对");
        c.putObject("learning_policy")
            .put("feedback_learning_enabled", variant.equals("feedback"))
            .put("constrained_reasoning_enabled", !variant.equals("baseline"))
            .put("structured_planning_enabled", !variant.equals("baseline"))
            .put("grounded_guidance_enabled", !variant.equals("baseline"));
        c.set("feedback_memories", dataset.path("training_feedback").path(name).deepCopy());
        var evidence = Fixtures.JSON.createArrayNode();
        for (var p : example.path("paragraphs")) {
          var e =
              evidence
                  .addObject()
                  .put("source_id", Fixtures.SOURCE)
                  .put("source_revision", 1)
                  .put("chunk_id", p.path("id").asText())
                  .put("text", "[" + p.path("id").asText() + "] " + p.path("text").asText());
          e.putObject("locator").put("kind", "pdf_page").put("page", 1);
        }
        c = ConstraintAwarePlanning.prepare(c);
        c = GroundedGuidance.prepare(c, evidence);
        if (variant.equals("feedback"))
          SemanticFeedback.attach(c, preparations.path(name).path("interpretations"));
        var row =
            records
                .addObject()
                .put("case_id", id)
                .put("dataset", name)
                .put("variant", variant)
                .put("input_sha256", hash)
                .put("prompt_version", SpringAiModelGateway.PROMPT_VERSION)
                .put(
                    "model",
                    environment.getProperty("spring.ai.openai.chat.options.model", "unknown"))
                .put(
                    "temperature",
                    environment.getProperty("spring.ai.openai.chat.options.temperature", "unknown"))
                .put("scope", "generation_module_not_backend_end_to_end")
                .put("max_generation_calls", 2);
        long start = System.nanoTime();
        double before = tokens();
        int calls = 0;
        try {
          calls++;
          var answer = model.generate(c, evidence, Fixtures.JSON.createArrayNode());
          row.set("initial_answer", answer.deepCopy());
          var errors = new ArrayList<>(ConstraintAwarePlanning.violations(c, answer));
          errors.addAll(GroundedGuidance.violations(c, answer));
          row.set("initial_violations", Fixtures.JSON.valueToTree(errors));
          if (!errors.isEmpty()) {
            calls++;
            answer =
                model.generate(
                    ConstraintAwarePlanning.repairContext(c, errors),
                    evidence,
                    Fixtures.JSON.createArrayNode());
            errors = new ArrayList<>(ConstraintAwarePlanning.violations(c, answer));
            errors.addAll(GroundedGuidance.violations(c, answer));
          }
          row.set("final_violations", Fixtures.JSON.valueToTree(errors));
          answer = GroundedGuidance.publish(c, answer);
          if (!errors.isEmpty()) answer = GuidancePublication.forReview(c, answer, errors);
          row.set("answer", answer);
          row.put("status", "succeeded");
        } catch (RuntimeException error) {
          row.put("status", "failed").put("error_type", error.getClass().getSimpleName());
          if (error instanceof AgentFailure f) row.put("error_code", f.code());
        }
        row.put("model_calls", calls)
            .put("tokens", tokens() - before)
            .put("latency_ms", (System.nanoTime() - start) / 1_000_000);
        Fixtures.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), records);
        System.out.println(
            "PUBLIC_SCIENCE_PROGRESS "
                + records.size()
                + "/180 "
                + id
                + " "
                + variant
                + " "
                + row.path("status").asText());
      }
    }
  }

  private double tokens() {
    return metrics.find("yanji.agent.tokens").counters().stream().mapToDouble(c -> c.count()).sum();
  }
}
