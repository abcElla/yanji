package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Pattern;

/** Keeps task wording free of transport identifiers and unsupported quantitative constraints. */
final class PlanningWording {
  private static final Pattern CITATION =
      Pattern.compile("(?<![A-Za-z0-9_])c[1-9]\\d*_[1-9]\\d*(?![A-Za-z0-9_])");
  private static final Pattern WORD_MINIMUM =
      Pattern.compile(
          "(?:不少于|不低于|至少|最少|不超过|不多于|至多|最多|少于|大于|≥|>=|≤|<=)\\s*([0-9]+|[一二两三四五六七八九十百千]+)\\s*(?:字|次|种|项|个|份|张|组|点|处|条|类|页|行|篇)(?:的)?"
              + "|[0-9]+\\s*[–—~～-]\\s*[0-9]+\\s*(?:字|次|种|项|个|份|张|组|点|处|条|类|页|行|篇)(?:的)?");

  private static final Pattern EXAMPLE = Pattern.compile("[（(](?:如|例如)[:：]?([^（）()]+)[）)]");
  private static final Pattern REHEARSAL_LABEL =
      Pattern.compile(
          "(?:在)?(?:表头|标题|文件名|页面)(?:中|上)?(?:必须|应|需|要)?(?:写明|注明|标明|标注|添加)\\s*[“\"]([^”\"]*(?:预演|演示)[^”\"]*)[”\"][。；;]?");

  private PlanningWording() {}

  static void apply(ObjectNode output, ArrayNode evidence) {
    for (JsonNode proposal : output.path("proposal_drafts")) {
      if (!"task.create_batch".equals(proposal.path("action_type").asText())) continue;
      for (JsonNode task : proposal.at("/payload/tasks")) {
        if (!(task instanceof ObjectNode row)) continue;
        if (row.path("description").isTextual())
          row.put("description", clean(row.path("description").asText(), evidence));
        if (row.path("acceptance_criteria") instanceof ArrayNode criteria)
          for (int i = 0; i < criteria.size(); i++)
            if (criteria.get(i).isTextual())
              criteria.set(i, criteria.textNode(clean(criteria.get(i).asText(), evidence)));
      }
    }
  }

  private static String clean(String text, ArrayNode evidence) {
    text = CITATION.matcher(text).replaceAll("所引材料");
    var labels = REHEARSAL_LABEL.matcher(text);
    var labelText = new StringBuilder();
    while (labels.find()) {
      boolean supported = false;
      for (JsonNode chunk : evidence)
        supported |= chunk.path("text").asText().contains(labels.group());
      labels.appendReplacement(
          labelText, supported ? java.util.regex.Matcher.quoteReplacement(labels.group()) : "");
    }
    labels.appendTail(labelText);
    text = labelText.toString();
    var examples = EXAMPLE.matcher(text);
    var grounded = new StringBuilder();
    while (examples.find()) {
      boolean supported = false;
      for (JsonNode chunk : evidence)
        supported |= chunk.path("text").asText().contains(examples.group(1));
      examples.appendReplacement(
          grounded, supported ? java.util.regex.Matcher.quoteReplacement(examples.group()) : "");
    }
    examples.appendTail(grounded);
    text = grounded.toString();
    var matcher = WORD_MINIMUM.matcher(text);
    var result = new StringBuilder();
    while (matcher.find()) {
      boolean supported = false;
      for (JsonNode chunk : evidence) {
        var actual = WORD_MINIMUM.matcher(chunk.path("text").asText());
        while (actual.find())
          supported |=
              actual.group().replaceAll("\\s+", "").equals(matcher.group().replaceAll("\\s+", ""));
      }
      matcher.appendReplacement(
          result, supported ? java.util.regex.Matcher.quoteReplacement(matcher.group()) : "");
    }
    matcher.appendTail(result);
    return result.toString().trim();
  }
}
