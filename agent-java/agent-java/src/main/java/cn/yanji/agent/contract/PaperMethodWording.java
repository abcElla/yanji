package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/** The built-in reading action explains located passages, not admission to a research project. */
final class PaperMethodWording {
  private static final String READING_QUESTION = "请结合这篇文献，解释关键方法、适用条件和局限，并说明与我的当前研究任务有什么关系。";

  static void apply(ObjectNode output, ObjectNode context, ArrayNode evidence) {
    if (!"research_qa".equals(context.path("kind").asText())
        || !"ok".equals(output.at("/result/outcome").asText())
        || !READING_QUESTION.equals(context.at("/input/question").asText())
        || context.at("/paper/source_id").asText().isBlank()) return;
    var selected = evidence.arrayNode();
    for (JsonNode chunk : evidence) {
      if (!context.at("/paper/source_id").asText().equals(chunk.path("source_id").asText()))
        continue;
      for (JsonNode version : context.path("source_versions")) {
        if (version.path("source_id").asText().equals(chunk.path("source_id").asText())
            && version.path("revision").asInt(-1) == chunk.path("source_revision").asInt(-2)) {
          selected.add(chunk);
          break;
        }
      }
    }
    var claims = evidence.arrayNode();
    Set<String> seen = new HashSet<>();
    for (JsonNode choice : EvidenceCitations.catalog(selected)) {
      String quote = choice.path("quote").asText();
      if (!quote.matches("(?is).*(方法|公式|计算|功率|用电|适用|条件|局限|未知|复核|method|formula|limitation).*"))
        continue;
      if (!seen.add(quote)) continue;
      var reference = ((ObjectNode) choice).deepCopy();
      reference.remove("citation_id");
      var claim = claims.addObject().put("text", quote);
      claim.putArray("source_refs").add(reference);
      if (claims.size() == 5) break;
    }
    if (claims.isEmpty()) return;
    var result = (ObjectNode) output.path("result");
    result.set("claims", claims);
    result
        .putArray("limitations")
        .add("下面整理的是当前稿件已有的方法、条件与局限。与研究任务的具体关系仍需结合阶段目标和导师要求核对；不代表已经完成真实测量或证明结论。");
  }
}
