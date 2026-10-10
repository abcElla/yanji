package cn.yanji.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks fixed minimum repetition counts, not general semantic entailment. */
final class RepeatThresholdGuard {
  private static final String COUNT = "([0-9]+|[一二两三四五六七八九十]+)";
  private static final String UNIT = "(项|条|篇|字|个|人|轮|次)";
  private static final Pattern TASK_MINIMUM =
      Pattern.compile(
          "(?:不少于|至少|最少)\\s*" + COUNT + "\\s*" + UNIT + "|" + COUNT + "\\s*" + UNIT + "(?:及)?以上");
  private static final Pattern MINIMUM =
      Pattern.compile(
          "(?:不少于|至少|最少|≥|>=)\\s*(?:独立)?(?:重复)?([0-9]+|[一二三四五六七八九十]+)\\s*次(?:的)?(?:独立)?(?:重复)?(?:实验|测量|验证)?");

  private RepeatThresholdGuard() {}

  static void validate(ObjectNode output, ArrayNode evidence) {
    Set<String> authorizedTaskCounts = new HashSet<>();
    for (var row : evidence) collectTaskMinimum(row.path("text").asText(), authorizedTaskCounts);
    Set<String> requestedTaskCounts = new HashSet<>();
    for (var proposal : output.path("proposal_drafts")) {
      if (!"task.create_batch".equals(proposal.path("action_type").asText())) continue;
      for (var task : proposal.at("/payload/tasks")) {
        for (var criterion : task.path("acceptance_criteria"))
          collectTaskMinimum(criterion.asText(), requestedTaskCounts);
      }
    }
    if (!authorizedTaskCounts.containsAll(requestedTaskCounts))
      throw new UnsupportedRequirementEvidence("任务数量门槛缺少导师要求或原文支持");
    Set<String> allowed = new HashSet<>();
    for (var row : evidence) collect(row.path("text").asText(), allowed);
    Set<String> requested = new HashSet<>();
    scan(output.path("result"), requested);
    for (var proposal : output.path("proposal_drafts")) {
      scan(proposal.path("reason"), requested);
      scan(proposal.path("payload"), requested);
    }
    if (!allowed.containsAll(requested)) throw new UnsupportedRequirementEvidence("重复次数门槛缺少授权证据");
  }

  private static void scan(JsonNode value, Set<String> numbers) {
    if (value.isTextual()) collect(value.asText(), numbers);
    else if (value.isContainerNode())
      value
          .fields()
          .forEachRemaining(
              e -> {
                if (!"source_refs".equals(e.getKey())) scan(e.getValue(), numbers);
              });
    if (value.isArray()) for (var row : value) scan(row, numbers);
  }

  private static void collect(String text, Set<String> numbers) {
    var matcher = MINIMUM.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
    while (matcher.find()) {
      // Ignore explicit rejection of a threshold and unrelated occurrence counts.
      String prefix = text.substring(Math.max(0, matcher.start() - 12), matcher.start());
      if (prefix.matches("(?s).*(?:不能将|不应将|不应要求|不要求|不必|无需|无须|并非要求)[^，。；;]*")) continue;
      if (!matcher.group().matches("(?s).*(?:重复|实验|测量|验证).*")) continue;
      String count = matcher.group(1);
      int ten = count.indexOf('十');
      String digits = "一二三四五六七八九";
      if (ten >= 0) {
        int tens = ten == 0 ? 1 : digits.indexOf(count.charAt(0)) + 1;
        int units = ten == count.length() - 1 ? 0 : digits.indexOf(count.charAt(ten + 1)) + 1;
        numbers.add(Integer.toString(tens * 10 + units));
      } else {
        int index = digits.indexOf(count);
        numbers.add(index >= 0 ? Integer.toString(index + 1) : count.replaceFirst("^0+(?!$)", ""));
      }
    }
  }

  private static void collectTaskMinimum(String text, Set<String> counts) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    var matcher = TASK_MINIMUM.matcher(normalized);
    while (matcher.find()) {
      String prefix = normalized.substring(Math.max(0, matcher.start() - 16), matcher.start());
      if (prefix.matches("(?s).*(?:不要求|不需要|不应要求|不必|无需|无须|不得|不能将|不应将|并非要求|不作为)[^，。；;]*")) continue;
      String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
      String unit = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
      String digits = "一二三四五六七八九";
      number = number.replace('两', '二');
      int ten = number.indexOf('十');
      if (ten >= 0) {
        int tens = ten == 0 ? 1 : digits.indexOf(number.charAt(0)) + 1;
        int units = ten == number.length() - 1 ? 0 : digits.indexOf(number.charAt(ten + 1)) + 1;
        number = Integer.toString(tens * 10 + units);
      } else if (digits.contains(number)) number = Integer.toString(digits.indexOf(number) + 1);
      counts.add(number.replaceFirst("^0+(?!$)", "") + ":" + unit);
    }
  }
}
