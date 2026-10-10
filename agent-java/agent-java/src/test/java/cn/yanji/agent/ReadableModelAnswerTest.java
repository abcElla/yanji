package cn.yanji.agent;

import static org.assertj.core.api.Assertions.*;

import cn.yanji.agent.contract.AgentFailure;
import cn.yanji.agent.contract.OutputGuard;
import cn.yanji.agent.model.ReadableModelAnswer;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class ReadableModelAnswerTest {
  @Test
  void displayHumanizesProseWithoutChangingTaskAssociation() {
    var raw =
        Fixtures.object(
            "{\"result\":{\"summary\":\"任务（task_id: baseline）为todo；请补充confirmed_experiments。\",\"next_steps\":[{\"title\":\"补充记录\",\"existing_task_id\":\"baseline\"}]}}");
    var result =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(
                raw, Fixtures.context("research_followup"), Fixtures.JSON.createArrayNode(), 1);
    assertThat(result.at("/result/summary").asText())
        .contains("待开始", "已确认实验记录")
        .doesNotContain("task_id", "confirmed_experiments", "todo");
    assertThat(result.at("/result/next_steps/0/existing_task_id").asText()).isEqualTo("baseline");
  }

  @Test
  void plainAnswerAndFencedJsonBecomeReadableWithoutActions() {
    var raw = ReadableModelAnswer.parse(Fixtures.JSON, "建议先固定数据划分，再比较同一测试集的基线。");
    var result =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(raw, Fixtures.context("research_qa"), Fixtures.JSON.createArrayNode(), 1);
    assertThat(result.at("/result/summary").asText()).contains("固定数据划分");
    assertThat(result.path("proposal_drafts")).isEmpty();
    var fenced = ReadableModelAnswer.parse(Fixtures.JSON, "```json\n{\"answer\":\"先补充消融实验\"}\n```");
    assertThat(fenced.at("/result/summary").asText()).isEqualTo("先补充消融实验");
  }

  @Test
  void stepsOnlyAndStringClaimsDoNotDiscardTheAnswer() {
    var raw =
        Fixtures.object(
            "{\"result\":{\"claims\":[\"待比较相同测试集\"],\"next_steps\":[{\"title\":\"准备基线\",\"description\":\"固定划分并记录配置\",\"existing_task_id\":null}]}}");
    var result =
        new OutputGuard(Fixtures.JSON, Clock.systemUTC(), false)
            .prepare(
                raw, Fixtures.context("research_followup"), Fixtures.JSON.createArrayNode(), 1);
    assertThat(result.at("/result/summary").asText()).contains("固定划分");
    assertThat(result.at("/result/claims/0/text").asText()).contains("相同测试集");
    assertThat(result.at("/result/next_steps/0/existing_task_id").isMissingNode()).isTrue();
  }

  @Test
  void corruptJsonAndEmptyResponsesAreNotPretendedToBeAnswers() {
    assertThatThrownBy(
            () -> ReadableModelAnswer.parse(Fixtures.JSON, "{\"result\":{\"summary\":\"内部字段"))
        .isInstanceOf(AgentFailure.class);
    assertThatThrownBy(() -> ReadableModelAnswer.parse(Fixtures.JSON, " "))
        .isInstanceOf(AgentFailure.class);
  }
}
