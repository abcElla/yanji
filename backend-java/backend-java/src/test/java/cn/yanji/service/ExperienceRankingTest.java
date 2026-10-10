package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExperienceRankingTest {
  @Test void extractsBoundedUniqueChineseFeedback() {
    assertThat(ExperienceRanking.extract("请补充基线对照。请补充基线对照；解释数据划分\n短"))
        .containsExactly("请补充基线对照","解释数据划分");
    assertThat(ExperienceRanking.extract("长".repeat(7000))).allSatisfy(s->assertThat(s.length()).isLessThanOrEqualTo(240));
  }
  @Test void rejectsUnrelatedRecentFeedbackAndRanksRelevantFeedback() {
    var now=Instant.parse("2026-10-09T00:00:00Z");
    var baseline=map("lesson","补充基线对照和数据划分","observed_at",now.toString(),"signal",0.9);
    var irrelevant=map("lesson","安排食堂午餐菜单","observed_at",now.toString(),"signal",1.0);
    assertThat(ExperienceRanking.select(List.of(irrelevant,baseline),"基线对照 数据划分",5,now))
        .hasSize(1).first().satisfies(row->assertThat(row.get("lesson")).isEqualTo(baseline.get("lesson")));
  }
  @Test void recencyDecaysAndDuplicatePenaltyAddsDiversity() {
    var now=Instant.parse("2026-10-09T00:00:00Z");
    var recent=map("lesson","基线对照","observed_at",now.toString(),"signal",0.9);
    var duplicate=map("lesson","基线对照","observed_at","2020-01-01T00:00:00Z","signal",0.9);
    var split=map("lesson","数据划分","observed_at",now.toString(),"signal",0.9);
    assertThat(ExperienceRanking.select(List.of(recent,duplicate,split),"基线对照 数据划分",2,now))
        .extracting(row->row.get("lesson")).containsExactly("基线对照","数据划分");
  }
}
