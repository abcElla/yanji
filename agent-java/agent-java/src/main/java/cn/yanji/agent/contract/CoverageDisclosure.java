package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Scope notices are trusted snapshot metadata, never model claims or invented citations. */
public final class CoverageDisclosure {
  private CoverageDisclosure() {}

  public static void apply(ObjectNode output, ObjectNode context) {
    ObjectNode result = (ObjectNode) output.path("result");
    JsonNode coverage = context.path("record_coverage");
    boolean truncated =
        context.path("source_scope_truncated").asBoolean()
            || coverage.path("source_scope_truncated").asBoolean()
            || coverage.path("excluded_by_source_limit").asInt() > 0;
    boolean partial =
        coverage.path("partial").asBoolean()
            || coverage.path("omitted_records").asInt() > 0
            || truncated;
    String recordNotice = "";
    if (partial) {
      recordNotice =
          "这次可参考的已确认记录共"
              + count(coverage, "total_records")
              + "条，实际读到"
              + count(coverage, "included_records")
              + "条，还有"
              + count(coverage, "omitted_records")
              + "条还没有读取。因此，这份整理不能代表全部记录。";
      if (truncated) recordNotice += "这次能读取的材料数量有限，不能保证读取全部未展示的记录。";
      else if (coverage.has("omitted_records_searchable")
          && !coverage.path("omitted_records_searchable").asBoolean())
        recordNotice += "其余记录暂不确定能否读取。";
      result.withArray("limitations").add(recordNotice);
      if ("weekly_report".equals(context.path("kind").asText())
          && "ok".equals(result.path("outcome").asText()))
        result.put("summary", recordNotice + "周报草稿只整理了部分内容，提交前请核对原始记录。");
      else result.put("summary", recordNotice + "\n" + result.path("summary").asText());
    }
    String paperNotice = "";
    if ("paper_guide".equals(context.path("kind").asText())) {
      JsonNode stats = context.path("retrieval_coverage");
      paperNotice =
          stats.path("stats_available").asBoolean()
              ? "这次读到了文献中的"
                  + count(stats, "included_chunks")
                  + "/"
                  + count(stats, "eligible_chunks")
                  + "段材料；"
                  + (stats.path("coverage_complete").asBoolean() ? "本次可读取的片段都已返回，" : "还有片段没有读到，")
                  + "导读只摘取了部分原文，没有阅读全文，也没有验证研究结论。"
              : "暂时无法确定这次读到了文献的多少内容。导读只摘取了部分原文，没有阅读全文，也没有验证研究结论。";
      result.put("summary", result.path("summary").asText() + "\n" + paperNotice);
      result.withArray("limitations").add(paperNotice);
    }
    for (JsonNode node : output.path("proposal_drafts")) {
      ObjectNode proposal = (ObjectNode) node;
      if (!recordNotice.isEmpty())
        proposal.put("reason", recordNotice + "\n" + proposal.path("reason").asText());
      if (!recordNotice.isEmpty()
          && "weekly_report.draft".equals(proposal.path("action_type").asText())) {
        // The public payload has no coverage field. Keep the notice in one existing text item,
        // so it survives approval/save; do not forge a source ref for snapshot metadata.
        for (String section : List.of("done_items", "issues", "next_plan", "help_needed")) {
          JsonNode rows = proposal.path("payload").path(section);
          if (!rows.isEmpty()) {
            ObjectNode first = (ObjectNode) rows.get(0);
            first.put("text", recordNotice + "\n" + first.path("text").asText());
            break;
          }
        }
      }
      if (!paperNotice.isEmpty()
          && "reading_note.draft".equals(proposal.path("action_type").asText())) {
        ArrayNode questions = ((ObjectNode) proposal.path("payload")).withArray("questions");
        if (questions.isEmpty()) questions.add(paperNotice);
        else questions.set(0, questions.textNode(paperNotice + "\n" + questions.get(0).asText()));
      }
    }
  }

  private static String count(JsonNode object, String field) {
    JsonNode value = object.path(field);
    return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0
        ? value.asText()
        : "（未提供）";
  }
}
