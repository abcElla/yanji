package cn.yanji.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Keep readable advice when validation fails, without making rejected steps executable. */
public final class GuidancePublication {
  private GuidancePublication() {}

  public static ObjectNode forReview(ObjectNode context, ObjectNode draft, List<String> errors) {
    var out = draft.deepCopy();
    if (!(out.path("result") instanceof ObjectNode result)) return out;
    if (errors.contains("UNSUPPORTED_FACT")) {
      result.put("summary", result.path("summary").asText() + "\n\n涉及已有事实的描述仍待核对，请以原始记录为准。");
      for (JsonNode claim : result.path("claims")) {
        if (claim instanceof ObjectNode object) {
          object.put("text", "待核对的分析：" + claim.path("text").asText());
          object.remove("anchor_id");
          object.putArray("source_refs");
        }
      }
    }
    boolean taskErrors =
        errors.stream()
            .anyMatch(
                e ->
                    !List.of("UNSUPPORTED_FACT", "UNKNOWN_UI_FUNCTION", "UNKNOWN_SKILL")
                        .contains(e));
    if (taskErrors) {
      var claims = result.withArray("/claims");
      for (JsonNode step : result.path("next_steps")) {
        String title = step.path("title").asText();
        String description = step.path("description").asText();
        if (!title.isBlank() || !description.isBlank())
          claims
              .addObject()
              .put("text", "待确认的行动建议：" + title + "\n" + description)
              .putArray("source_refs");
      }
      // These remain readable, but cannot be adopted as tasks with invalid dependencies.
      result.putArray("next_steps");
      var clarification = ConstraintAwarePlanning.fallback(context, errors);
      result.set("next_steps", clarification.at("/result/next_steps").deepCopy());
      result.withArray("/missing_inputs").add("请先核对建议的前置顺序和已有任务关联，再加入任务。");
    }
    result.withArray("/limitations").add("分析已保留供参考；未通过核对的内容不作为已确认事实或自动执行指令。");
    out.putArray("proposal_drafts");
    return out;
  }
}
