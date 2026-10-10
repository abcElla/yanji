package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentReportMergeTest {
  @Test
  void adoptingAiDraftKeepsWrittenItemsAndAddsOnlyNewContent() {
    var human = map("id", "student-item", "text", "实际电价尚未获取", "origin", "human");
    var earlier = map("id", "earlier-ai", "text", "已核对单位", "origin", "agent");
    var newItem = map("id", "new-ai", "text", "费用差为0.288元", "origin", "agent");
    var merged = AgentService.mergeReportItems(
        List.of(human, earlier),
        List.of(map("id", "duplicate", "text", "实际电价尚未获取", "origin", "agent"), newItem));
    assertThat(merged).containsExactly(human, earlier, newItem);
    assertThat(AgentService.mergeReportItems(List.of(human), List.of())).containsExactly(human);
    assertThat(AgentService.mergeReportItems(merged, List.of(newItem))).isEqualTo(merged);
  }
}
