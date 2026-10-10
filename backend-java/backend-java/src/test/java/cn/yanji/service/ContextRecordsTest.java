package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ContextRecordsTest {
  List<Map<String, Object>> records(String prefix, int count) {
    var result = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < count; i++)
      result.add(
          map(
              "id",
              prefix + i,
              "source_id",
              prefix + i,
              "confirmed_at",
              "2026-10-07T10:00:" + String.format("%02d", i) + "Z"));
    return result;
  }

  @Test
  void largeMixedWeekIsBoundedBalancedAndDeterministic() {
    var ex = records("e", 13);
    var ns = records("n", 8);
    var selected = ContextRecords.select(ex, ns);
    assertThat(num(selected, "included_records")).isEqualTo(10);
    assertThat(num(selected, "total_records")).isEqualTo(21);
    assertThat(num(selected, "omitted_records")).isEqualTo(11);
    assertThat(selected.get("partial")).isEqualTo(true);
    assertThat(list(selected.get("selected_ids")))
        .containsExactly("e12", "n7", "e11", "n6", "e10", "n5", "e9", "n4", "e8", "n3");
    Collections.reverse(ex);
    Collections.reverse(ns);
    assertThat(ContextRecords.select(ex, ns)).isEqualTo(selected);
  }

  @Test
  void emptyAndSingleKindUseAvailableSlotsWithoutInventingRecords() {
    assertThat(num(ContextRecords.select(List.of(), List.of()), "included_records")).isZero();
    var selected = ContextRecords.select(records("e", 12), records("n", 1));
    assertThat(list(selected.get("selected_ids"))).hasSize(10).contains("n0");
    assertThat(ContextRecords.select(records("e", 3), records("n", 2)).get("partial"))
        .isEqualTo(false);
    var times = records("e", 2);
    times.get(0).put("confirmed_at", "2026-10-07T10:00:00Z");
    times.get(1).put("confirmed_at", "2026-10-07T10:00:00.001Z");
    assertThat(list(ContextRecords.select(times, List.of()).get("selected_ids")))
        .containsExactly("e1", "e0");
  }
}
