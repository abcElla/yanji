package cn.yanji;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;

import cn.yanji.service.LexicalSearch;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class CompatibilityTest {
  @Test
  void cursorSortsInstantsRegardlessOfFractionLength() {
    String early = uid(), late = uid();
    var rows =
        List.of(
            map("id", early, "created_at", "2026-10-07T00:00:00.1Z"),
            map("id", late, "created_at", "2026-10-07T00:00:00.11Z"));
    var first = page(rows, map("limit", 1), "items", "created_at");
    assertThat(obj(list(first.get("items")).get(0)).get("id")).isEqualTo(late);
    var second =
        page(rows, map("limit", 1, "cursor", first.get("next_cursor")), "items", "created_at");
    assertThat(obj(list(second.get("items")).get(0)).get("id")).isEqualTo(early);
    assertThat(second.get("next_cursor")).isNull();
  }

  @TestFactory
  Stream<DynamicTest> lexicalMatchesOldBackend() throws Exception {
    var cases =
        list(
            JSON.readValue(
                new ClassPathResource("lexical-compatibility.json").getInputStream(),
                Object.class));
    return cases.stream()
        .map(
            raw -> {
              var c = obj(raw);
              return DynamicTest.dynamicTest(
                  str(c, "query"),
                  () -> {
                    var terms = LexicalSearch.terms(str(c, "query"));
                    assertThat(new ArrayList<>(terms)).isEqualTo(list(c.get("terms")));
                    assertThat(LexicalSearch.score(str(c, "text"), terms))
                        .isEqualTo(num(c, "score"));
                  });
            });
  }

  @Test
  void javaVerifiesExistingPythonArgon2Passwords() throws Exception {
    String hash =
        new ClassPathResource("python-argon2.txt")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    var encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
    assertThat(encoder.matches("Synthetic123", hash)).isTrue();
    assertThat(encoder.matches("wrong", hash)).isFalse();
  }

  @Test
  void stopWordsAndOneCharacterNoiseAreExcluded() {
    assertThat(LexicalSearch.terms("如何 使用 问题 一 a １")).isEmpty();
  }
}
