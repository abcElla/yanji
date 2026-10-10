package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Keep an applicable mentor draft inside the facts the model selected from this report. */
final class MentorFeedbackWording {
  private MentorFeedbackWording() {}

  static void apply(ObjectNode output, ObjectNode context, JsonNode evidence) {
    if (!"risk_brief".equals(context.path("kind").asText())
        || context.at("/input/report_id").asText().isBlank()
        || !"ok".equals(output.at("/result/outcome").asText())) return;
    var result = (ObjectNode) output.path("result");
    result.putArray("limitations").add("反馈依据当前已提交的周报整理，尚未额外核验原始实验与资料。具体安排需师生沟通确认。");
    result.putArray("missing_inputs");
    for (JsonNode raw : output.path("proposal_drafts")) {
      if (!"mentor_feedback.draft".equals(raw.path("action_type").asText())) continue;
      var proposal = (ObjectNode) raw;
      var refs = proposal.withArray("source_refs");
      var covered = new LinkedHashSet<String>();
      for (JsonNode ref : refs) covered.add(ref.at("/locator/field").asText());
      // Reading all four submitted sections is deterministic; do not let a model omit
      // the student's request for help merely because it chose only progress quotes.
      for (JsonNode chunk : evidence) {
        String field = chunk.at("/locator/field").asText();
        if (!List.of("done_items", "issues", "next_plan", "help_needed").contains(field)
            || covered.contains(field)
            || !chunk.path("source_id").equals(context.at("/weekly_report/source_id"))
            || !chunk.path("source_revision").equals(context.at("/weekly_report/source_revision")))
          continue;
        String text = chunk.path("text").asText().strip();
        if (text.isBlank()) continue;
        String quote = text.lines().filter(line -> !line.isBlank()).findFirst().orElse(text);
        if (quote.length() > 300) {
          String prefix = quote.substring(0, 300);
          int stop = Math.max(prefix.lastIndexOf('。'), prefix.lastIndexOf('；'));
          quote = stop > 0 ? prefix.substring(0, stop + 1) : prefix;
        }
        var ref =
            refs.addObject()
                .put("source_id", chunk.path("source_id").asText())
                .put("source_revision", chunk.path("source_revision").asInt())
                .put("quote", quote);
        ref.set("locator", chunk.path("locator").deepCopy());
        ref.set("chunk_id", chunk.path("chunk_id").deepCopy());
        covered.add(field);
      }
      Map<String, LinkedHashSet<String>> sections = new LinkedHashMap<>();
      for (JsonNode ref : proposal.path("source_refs")) {
        String field = ref.at("/locator/field").asText("report");
        sections
            .computeIfAbsent(field, k -> new LinkedHashSet<>())
            .add(ref.path("quote").asText().strip());
      }
      StringBuilder body = new StringBuilder("请先核对这份周报中已写出的内容，再一起确认下一步安排。\n\n");
      for (String field : List.of("done_items", "issues", "next_plan", "help_needed", "report")) {
        var quotes = sections.get(field);
        if (quotes == null || quotes.isEmpty()) continue;
        String lead =
            switch (field) {
              case "done_items" -> "已经完成的工作，请继续核对表述与记录是否一致：";
              case "issues" -> "遇到的问题，请区分已经确认的条件和仍待补充的内容：";
              case "next_plan" -> "你写出的下周计划，请先确认条件和顺序，再推进：";
              case "help_needed" -> "需要老师一起确认的事项：";
              default -> "周报中需要核对的内容：";
            };
        body.append(lead).append('\n').append(String.join("\n", quotes)).append("\n\n");
      }
      body.append("先把这些内容说清楚；需要补充的资料和具体安排，我们沟通确认后再执行。");
      ((ObjectNode) proposal.path("payload")).put("body", body.toString());
      proposal.put("reason", "根据当前已提交周报的内容整理，确认后保存为可编辑的反馈草稿。");
    }
  }
}
