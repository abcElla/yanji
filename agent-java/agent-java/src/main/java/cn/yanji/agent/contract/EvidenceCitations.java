package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Short, exact citation choices; the model selects identifiers, never provenance metadata. */
public final class EvidenceCitations {
  private EvidenceCitations() {}

  public static ArrayNode catalog(ArrayNode evidence) {
    ArrayNode choices = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < evidence.size(); i++) {
      JsonNode chunk = evidence.get(i);
      int n = 0;
      for (String text : citationTexts(chunk)) {
        int start = 0;
        while (start < text.length() && ++n <= 8) {
          int end = Math.min(text.length(), start + 140);
          // Prefer whole short sentences; retain every original character and offset.
          for (int cursor = start + 1; cursor <= end; cursor++) {
            char c = text.charAt(cursor - 1);
            if ("。！？!?".indexOf(c) >= 0
                || (c == '.'
                    && (cursor == text.length() || Character.isWhitespace(text.charAt(cursor))))) {
              end = cursor;
              break;
            }
          }
          if (end == start + 140 && end < text.length()) {
            for (int cursor = end; cursor > start + 100; cursor--) {
              if (Character.isWhitespace(text.charAt(cursor - 1))) {
                end = cursor;
                break;
              }
            }
          }
          if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
          String quote = text.substring(start, end);
          if (!quote.isBlank()) {
            ObjectNode choice = choices.addObject().put("citation_id", "c" + (i + 1) + "_" + n);
            choice.put("quote", quote);
            for (String field : List.of("source_id", "source_revision", "chunk_id", "locator"))
              if (chunk.has(field)) choice.set(field, chunk.get(field).deepCopy());
          }
          start = end;
        }
      }
    }
    return choices;
  }

  private static List<String> citationTexts(JsonNode chunk) {
    String text = chunk.path("text").asText();
    if (!"record_field".equals(chunk.at("/locator/kind").asText())
        || !Set.of("parameters", "steps", "claims", "questions")
            .contains(chunk.at("/locator/field").asText())) return List.of(text);
    try {
      if (!new ObjectMapper().readTree(text).isContainerNode()) return List.of(text);
      var values = new ArrayList<String>();
      var strings = java.util.regex.Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(text);
      while (strings.find()) {
        int after = strings.end();
        while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
        if (after < text.length() && text.charAt(after) == ':') continue;
        // Retain raw escaped bytes as an exact substring of immutable legacy text.
        // Exclude keys and wrappers; never rewrite stored sources or old citations.
        values.add(strings.group(1));
      }
      return values;
    } catch (Exception ignored) {
      return List.of(text);
    }
  }

  public static boolean expand(ObjectNode output, ObjectNode context, ArrayNode evidence) {
    Map<String, ObjectNode> choices = new HashMap<>();
    for (JsonNode choice : catalog(evidence)) {
      ObjectNode reference = ((ObjectNode) choice).deepCopy();
      String id = reference.remove("citation_id").asText();
      choices.put(id, reference);
    }
    boolean compact = expandRefs(output, choices);
    // Legacy research answers also publish excerpts, never a paraphrase that adds facts.
    if (!compact && !"research_qa".equals(context.path("kind").asText())) return false;
    ArrayNode uniqueClaims = JsonNodeFactory.instance.arrayNode();
    Set<String> seenClaims = new HashSet<>();
    for (JsonNode claim : output.at("/result/claims")) {
      if (!(claim instanceof ObjectNode object)) reject();
      ((ObjectNode) claim).put("text", quotedText(claim.path("source_refs")));
      if (seenClaims.add(claim.toString())) uniqueClaims.add(claim);
    }
    if (output.at("/result/claims").isArray())
      ((ObjectNode) output.path("result")).set("claims", uniqueClaims);
    String kind = context.path("kind").asText();
    for (JsonNode proposal : output.path("proposal_drafts")) {
      if (!(proposal instanceof ObjectNode)) reject();
      if ("reading_note.draft".equals(proposal.path("action_type").asText())) {
        if (!(proposal.path("payload") instanceof ObjectNode)) reject();
        ObjectNode payload = (ObjectNode) proposal.path("payload");
        ArrayNode claims = payload.putArray("claims");
        Set<String> seenQuotes = new HashSet<>();
        for (JsonNode ref : payload.path("source_refs"))
          if (seenQuotes.add(ref.path("quote").asText())) claims.add(ref.path("quote").asText());
        ((ObjectNode) proposal).put("reason", "根据当前文献的原文摘录生成阅读笔记草稿，请核对后确认。");
      } else if ("stage_plan".equals(kind))
        ((ObjectNode) proposal).put("reason", "根据所选阶段材料提出规划草稿，请导师核对任务内容、依赖和验收条件后确认。");
      else if ("weekly_report.draft".equals(proposal.path("action_type").asText())) {
        if (!proposal.path("payload").isObject()) reject();
        for (JsonNode row : proposal.at("/payload/done_items")) {
          if (!(row instanceof ObjectNode)) reject();
          ((ObjectNode) row).put("text", quotedText(row.path("source_refs")));
        }
        ((ObjectNode) proposal).put("reason", "根据本次已确认记录的原文摘录生成周报草稿，请核对覆盖范围后确认。");
      }
    }
    return true;
  }

  public static void constrainSummary(
      ObjectNode output, ObjectNode context, java.time.Clock clock) {
    String kind = context.path("kind").asText();
    ObjectNode result = (ObjectNode) output.path("result");
    if ("stage_plan".equals(kind))
      result.put(
          "summary",
          "当前阶段："
              + context.at("/stage/title").asText("未命名")
              + "。目前有"
              + context.path("tasks").size()
              + "项任务。请先检查下面列出的材料和待补充内容。"
              + "具体安排由导师确认后生效。");
    else if ("risk_brief".equals(kind) && !context.at("/input/report_id").asText().isBlank()) {
      if ("ok".equals(result.path("outcome").asText()))
        result.put("summary", "已对照这份周报整理指导重点。请核对下方反馈草稿，确认后仍可修改文字，再向学生发布。");
    } else if ("risk_brief".equals(kind)) {
      boolean detected = false;
      for (JsonNode task : context.path("tasks")) {
        if (Set.of("completed", "submitted").contains(task.path("status").asText())) continue;
        detected |= "blocked".equals(task.path("status").asText());
        try {
          detected |=
              java.time.OffsetDateTime.parse(task.path("due_at").asText())
                  .toInstant()
                  .isBefore(clock.instant());
        } catch (RuntimeException ignored) {
          /* Missing dates cannot prove overdue. */
        }
      }
      if (!detected
          && "ok".equals(result.path("outcome").asText())
          && result.path("claims").isEmpty()) {
        result.put("summary", "目前没有发现任务卡住或超过截止日期。后续进展有变化时，可以再检查一次。");
      } else
        result.put(
            "summary",
            "已选取"
                + result.path("claims").size()
                + "条相关记录。可以先联系学生确认遇到了什么困难，再决定怎样调整安排。材料不足时，暂时不能判断问题的原因。");
    } else if ("research_qa".equals(kind)) result.put("summary", researchSummary(result, context));
    else if ("paper_guide".equals(kind))
      result.put(
          "summary",
          "已为《"
              + context.at("/paper/title").asText("当前文献")
              + "》选取"
              + result.path("claims").size()
              + "处值得关注的内容。\n\n请结合原文阅读，再检查下方笔记草稿和待思考的问题。这里仅整理本次读取的内容，不能代替通读全文。");
  }

  private static String researchSummary(ObjectNode result, ObjectNode context) {
    var claims = new ArrayList<JsonNode>();
    result.path("claims").forEach(claims::add);
    claims.sort(Comparator.comparing(claim -> !thesisFeedback(claim, context)));
    if (claims.isEmpty()) return "这次读到的材料还不足以回答你的问题。需要补充的内容见下方，请和导师确认后再继续。";
    StringBuilder summary =
        new StringBuilder(
            "ok".equals(result.path("outcome").asText())
                ? "根据这次读到的材料，先看下面几点：\n\n"
                : "现有材料还不能完整回答你的问题，下面这些内容可供核对：\n\n");
    int shown = 0;
    boolean feedbackHeading = false;
    boolean manuscriptHeading = false;
    Set<String> seen = new HashSet<>();
    for (JsonNode claim : claims) {
      // Called after provenance validation. Never reuse the model's free-form summary.
      String text = displayText(claim.path("text").asText());
      if (text.isBlank() || !seen.add(text)) continue;
      if (shown == 5 || summary.length() + text.length() > 3000) break;
      if (thesisFeedback(claim, context) && !feedbackHeading) {
        summary.append("导师对这份稿件的意见：\n");
        feedbackHeading = true;
      } else if (!thesisFeedback(claim, context) && feedbackHeading && !manuscriptHeading) {
        summary.append("稿件中的相关内容：\n");
        manuscriptHeading = true;
      }
      summary.append("• ").append(text).append("\n\n");
      shown++;
    }
    if (shown < seen.size() || shown < claims.size()) summary.append("小结只展示部分内容，其余原文见下方。\n\n");
    summary.append(
        claims.stream().anyMatch(claim -> thesisFeedback(claim, context))
            ? "下一步：结合这份稿件和导师意见，核对还有哪些待查证事项，再决定后续安排。"
            : "下一步：对照原文确认这些内容，再结合你的问题和导师要求决定需要补充什么。");
    if (!result.path("missing_inputs").isEmpty()) summary.append("需要补充的材料见下方，请先核对这些缺项。");
    return summary.toString();
  }

  private static boolean thesisFeedback(JsonNode claim, ObjectNode context) {
    if (!context.at("/subject/paper_id").isTextual()) return false;
    // A paper-specific backend snapshot permits this paper, its notes and its feedback only.
    // Within that scope record_field.content is the feedback content, never another revision.
    for (JsonNode ref : claim.path("source_refs"))
      if ("record_field".equals(ref.at("/locator/kind").asText())
          && "content".equals(ref.at("/locator/field").asText())) return true;
    return false;
  }

  private static String displayText(String text) {
    if (text.contains("Python Decimal") && text.matches("(?s).*Decimal\\(.*\\d\\h*\\R\\h*\\d.*"))
      return "原文记录了 Python Decimal 计算过程，完整算式请展开下方原文查看。";
    if ("changes_requested".equals(text.trim())) return "原文标记：要求修改";
    if ("accepted".equals(text.trim())) return "原文标记：已接受";
    return text.replaceAll("(?<=[\\p{IsHan}，。；：！？、])\\h*\\R\\h*(?=[\\p{IsHan}，。；：！？、])", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static String quotedText(JsonNode refs) {
    List<String> quotes = new ArrayList<>();
    for (JsonNode ref : refs) quotes.add(ref.path("quote").asText());
    return String.join("\n", quotes);
  }

  private static boolean expandRefs(JsonNode node, Map<String, ObjectNode> choices) {
    boolean compact = false;
    if (node instanceof ObjectNode object) {
      var fields = object.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if ("source_refs".equals(field.getKey()) && field.getValue() instanceof ArrayNode refs) {
          for (int i = 0; i < refs.size(); i++) {
            JsonNode ref = refs.get(i);
            if (!ref.has("citation_id")) continue;
            if (ref.size() != 1 || !ref.path("citation_id").isTextual()) reject();
            ObjectNode actual = choices.get(ref.path("citation_id").asText());
            if (actual == null) reject();
            refs.set(i, actual.deepCopy());
            compact = true;
          }
        } else compact |= expandRefs(field.getValue(), choices);
      }
    } else if (node.isArray()) for (JsonNode child : node) compact |= expandRefs(child, choices);
    return compact;
  }

  private static void reject() {
    throw new AgentFailure("MODEL_OUTPUT_INVALID", "引用编号无效或夹带未经授权的来源字段", false);
  }
}
