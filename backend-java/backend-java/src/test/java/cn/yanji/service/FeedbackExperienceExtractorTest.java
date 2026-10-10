package cn.yanji.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FeedbackExperienceExtractorTest {
  @Test void extractsScenarioAndExactFeedbackWithoutGlobalizingTemporaryRequests() {
    var memory=FeedbackExperienceExtractor.describe("本周请先补充基线对照","实验准备");
    assertThat(memory.get("category")).isEqualTo("baseline");
    assertThat(memory.get("reuse_scope")).isEqualTo("temporary_requirement");
    assertThat(memory.get("source_quote")).isEqualTo("本周请先补充基线对照");
    assertThat(memory.get("generalization_requires_confirmation")).isEqualTo(true);
  }
  @Test void negativeFeedbackIsNotConvertedIntoTheOppositeRequirement() {
    var memory=FeedbackExperienceExtractor.describe("暂不开展消融实验","模型验证");
    assertThat(memory.get("polarity")).isEqualTo("avoid");
    assertThat(memory.get("category")).isEqualTo("ablation");
  }
  @Test void adoptionWithoutReasonDoesNotInventACause() {
    var memory=FeedbackExperienceExtractor.describe("用户采纳了计划","任务安排");
    assertThat(memory.get("explicit_feedback")).isEqualTo(false);
    assertThat(memory.get("reuse_scope")).isEqualTo("project_local");
  }
}
