package cn.yanji.agent.model;

import cn.yanji.agent.contract.AgentFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Recover an answer envelope without recovering or inventing executable actions. */
public final class ReadableModelAnswer {
  private ReadableModelAnswer() {}

  public static ObjectNode parse(ObjectMapper json, String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 80000)
      throw new AgentFailure("MODEL_RESPONSE_INVALID", "模型未返回可显示的回答", false);
    String text = raw.trim();
    if (text.startsWith("```")) {
      int newline = text.indexOf('\n');
      int end = text.lastIndexOf("```");
      if (newline > 0 && end > newline) text = text.substring(newline + 1, end).trim();
    }
    try {
      JsonNode node = json.readTree(text);
      if (node instanceof ObjectNode object) return normalize(json, object);
      if (node != null && node.isTextual()) return prose(json, node.asText());
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
      // A malformed JSON envelope may contain internal fields; never display it as prose.
      if (text.startsWith("{") || text.startsWith("["))
        throw new AgentFailure("MODEL_RESPONSE_INVALID", "模型回答格式不完整，请重试", false);
      return prose(json, text);
    }
    throw new AgentFailure("MODEL_RESPONSE_INVALID", "模型未返回可显示的回答", false);
  }

  public static ObjectNode normalize(ObjectMapper json, ObjectNode raw) {
    var out = raw.deepCopy();
    ObjectNode result;
    if (out.path("result") instanceof ObjectNode existing) result = existing;
    else {
      result = out.putObject("result");
      if (raw.path("result").isTextual()) result.put("summary", raw.path("result").asText());
      for (String field : new String[] {"summary", "claims", "next_steps", "suggested_skills"})
        if (raw.has(field)) result.set(field, raw.path(field).deepCopy());
    }
    if (result.path("summary").asText().isBlank()) {
      for (String field : new String[] {"answer", "content", "message"}) {
        JsonNode candidate = result.has(field) ? result.path(field) : raw.path(field);
        if (candidate.isTextual() && !candidate.asText().isBlank()) {
          result.put("summary", candidate.asText());
          break;
        }
      }
    }
    if (result.path("summary").asText().isBlank()) {
      StringBuilder summary = new StringBuilder();
      for (JsonNode step : result.path("next_steps")) {
        String title = step.path("title").asText();
        String description = step.path("description").asText();
        if (!title.isBlank()) summary.append(title).append('\n').append(description).append("\n\n");
      }
      if (!summary.isEmpty()) result.put("summary", summary.toString().trim());
    }
    return out;
  }

  private static ObjectNode prose(ObjectMapper json, String text) {
    if (text.isBlank()) throw new AgentFailure("MODEL_RESPONSE_INVALID", "模型返回了空回答", false);
    var out = json.createObjectNode();
    out.putObject("result").put("outcome", "ok").put("summary", text);
    out.putArray("proposal_drafts");
    return out;
  }
}
