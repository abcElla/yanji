package cn.yanji.service;

import static cn.yanji.core.Data.*;

import java.util.*;

/** Bounded, deterministic inline evidence; omitted sources remain searchable in the snapshot. */
final class ContextRecords {
  static final int LIMIT = 10;

  static Map<String, Object> select(
      List<Map<String, Object>> experiments, List<Map<String, Object>> notes) {
    var newest =
        Comparator.<Map<String, Object>, java.time.Instant>comparing(
                r ->
                    r.get("confirmed_at") == null
                        ? java.time.Instant.MIN
                        : instant(r.get("confirmed_at")))
            .reversed()
            .thenComparing(r -> str(r, "id"));
    var ex = experiments.stream().sorted(newest).toList();
    var ns = notes.stream().sorted(newest).toList();
    var selected = new ArrayList<Map<String, Object>>();
    int e = 0, n = 0;
    // Keep both kinds represented, then use each remaining slot for the nonempty kind.
    while (selected.size() < LIMIT && (e < ex.size() || n < ns.size())) {
      if (e < ex.size()) selected.add(ex.get(e++));
      if (selected.size() < LIMIT && n < ns.size()) selected.add(ns.get(n++));
    }
    int total = ex.size() + ns.size();
    return map(
        "selected_ids",
        selected.stream().map(r -> str(r, "source_id")).toList(),
        "policy",
        "latest_confirmed_balanced_v1",
        "max_inline_records",
        LIMIT,
        "total_records",
        total,
        "included_records",
        selected.size(),
        "omitted_records",
        total - selected.size(),
        "partial",
        total > selected.size(),
        "omitted_records_searchable",
        true);
  }
}
