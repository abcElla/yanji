package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A reading guide may organize quoted material, not invent research admission requirements. */
final class PaperGuideWording {
  private PaperGuideWording() {}

  static void apply(ObjectNode output, ObjectNode context) {
    if (!"paper_guide".equals(context.path("kind").asText())
        || !"ok".equals(output.at("/result/outcome").asText())) return;
    var result = (ObjectNode) output.path("result");
    result.putArray("missing_inputs");
    result.putArray("limitations").add("这里整理的是本次读到的原文，是否适用于你的研究，还需要结合全文和导师要求判断。");
    for (JsonNode node : output.path("proposal_drafts")) {
      if (!"reading_note.draft".equals(node.path("action_type").asText())) continue;
      var questions = ((ObjectNode) node.path("payload")).putArray("questions");
      // References and factual claims have already passed provenance validation.
      for (JsonNode claim : result.path("claims")) {
        String quote = claim.path("text").asText().trim();
        if (quote.length() < 15 || quote.length() > 240 || questions.size() == 2) continue;
        questions.add("原文写到：“" + quote + "”这段内容适用的条件是什么？与你的研究有哪些关系？");
      }
      if (questions.isEmpty()) questions.add("这些原文摘录能回答你的哪些问题？还有哪些内容需要回到全文继续阅读？");
      questions.add("哪些表述是原文已有的结论，哪些事项仍待确认？请把自己的理解与原文内容分开记录。");
    }
  }
}
