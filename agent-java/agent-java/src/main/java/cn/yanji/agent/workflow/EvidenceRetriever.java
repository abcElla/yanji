package cn.yanji.agent.workflow;

import cn.yanji.agent.backend.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

public final class EvidenceRetriever {
  public record Evidence(ArrayNode chunks, boolean lexicalFallback, int searches) {}

  private final ObjectMapper json;
  private final BusinessPolicy policy;
  private final BackendClient backend;

  public EvidenceRetriever(ObjectMapper json, BusinessPolicy policy, BackendClient backend) {
    this.json = json;
    this.policy = policy;
    this.backend = backend;
  }

  public Evidence retrieve(ResearchPlan plan, ObjectNode context, Lease lease, Runnable check) {
    var queries = new java.util.ArrayList<String>();
    for (var query : plan.queries()) queries.add(query.query());
    ArrayNode chunks = json.createArrayNode();
    var seen = new java.util.HashSet<String>();
    int calls = 0;
    boolean degraded = false;
    for (int i = 0; i < 3; i++) {
      String query;
      if (i < queries.size()) query = queries.get(i);
      else if (chunks.isEmpty()) query = keywords(context);
      else break;
      check.run();
      ObjectNode request = json.createObjectNode().put("query", query).put("top_k", 4);
      if (context.at("/subject/stage_id").isTextual())
        request.set("stage_id", context.at("/subject/stage_id"));
      var response = backend.search(lease, request);
      calls++;
      check.run();
      if (!response.path("chunks").isArray())
        throw new cn.yanji.agent.contract.AgentFailure(
            "BACKEND_PROTOCOL_INVALID", "检索响应缺少chunks数组", false);
      degraded |= "lexical_fallback".equals(response.path("retrieval_mode").asText());
      for (var row : response.path("chunks")) {
        String key =
            row.path("source_id").asText()
                + ":"
                + row.path("source_revision").asInt()
                + ":"
                + (row.hasNonNull("chunk_id")
                    ? row.path("chunk_id").asText()
                    : row.path("locator").toString());
        if (chunks.size() < 8 && policy.evidenceInBusinessScope(row, context) && seen.add(key))
          chunks.add(row.deepCopy());
      }
      // Exactly one code-controlled fallback after the planned queries, including an empty plan.
      if (i >= queries.size()) break;
    }
    return new Evidence(chunks, degraded, calls);
  }

  public static ArrayNode modelView(ArrayNode evidence) {
    ArrayNode view = evidence.deepCopy();
    for (var row : view) {
      String text = row.path("text").asText();
      if (text.length() > 1500) {
        int end = Character.isHighSurrogate(text.charAt(1499)) ? 1499 : 1500;
        ((ObjectNode) row).put("text", text.substring(0, end));
      }
    }
    return view;
  }

  private static String keywords(ObjectNode context) {
    String raw = context.at("/input/question").asText();
    if (raw.isBlank()) raw = context.at("/stage/title").asText();
    var terms = new java.util.ArrayList<String>();
    for (String term :
        java.util.List.of("重复", "数据", "划分", "实验", "训练", "验证", "结果", "记录", "材料", "规则", "冲突", "方法"))
      if (raw.contains(term)) terms.add(term);
    if (!terms.isEmpty()) return String.join(" ", terms);
    raw =
        raw.replaceAll("https?://\\S+", " ")
            .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    if (raw.isBlank()) raw = "实验 结果 方法 问题 研究";
    return raw.substring(0, Math.min(80, raw.length()));
  }
}
