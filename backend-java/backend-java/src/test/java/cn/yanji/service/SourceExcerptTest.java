package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.yanji.mapper.Repository;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceExcerptTest {
  @Test
  void locatorsShareImmutableTextAndMultiChunkSelectionIsStable() {
    var db = mock(Repository.class);
    var access = new Access(db);
    for (var locator :
        List.of(
            map("kind", "pdf_page", "page", 1),
            map("kind", "docx_paragraph", "paragraph", 2),
            map("kind", "record_field", "field", "requirements"),
            map("kind", "record_field", "field", "source_refs"))) {
      var rev =
          map(
              "source_id",
              "source",
              "revision",
              1,
              "locator_scheme",
              locator.get("kind"),
              "text_snapshot",
              "{\"requirements\":[\"中文\"]}");
      var a = map("chunk_id", "part:2", "text", "[\"中文\", ", "locator", locator);
      var b = map("chunk_id", "part:10", "text", "\"换行\\n引用\"]", "locator", locator);
      when(db.query("chunks", "WHERE source_id=? AND source_revision=?", "source", 1))
          .thenReturn(List.of(b, a));
      assertThat(access.sourceExcerpt(rev, locator, null)).isEqualTo("[\"中文\", \"换行\\n引用\"]");
      assertThat(access.sourceExcerpt(rev, locator, "part:10")).isEqualTo(b.get("text"));
      var padded = new LinkedHashMap<>(locator);
      padded.put("field", locator.get("field"));
      padded.put("unused", null);
      assertThat(access.sourceExcerpt(rev, padded, "part:2")).isEqualTo(a.get("text"));
    }
  }
}
