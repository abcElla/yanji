package cn.yanji.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

/** Opt-in: writes and deletes only uniquely named QA objects in the existing local bucket. */
@EnabledIfSystemProperty(named = "verify.local.minio", matches = "true")
class LocalMinioVerificationTest {
  @Test
  void uploadsReadsAndDeletesThroughJavaSdk() throws Exception {
    Map<?, ?> config;
    try (var input = java.nio.file.Files.newInputStream(Path.of("config/application-local.yml"))) {
      config = new Yaml().load(input);
    }
    Map<?, ?> settings = (Map<?, ?>) config.get("yanji");
    var env = new MockEnvironment();
    settings.forEach((key, value) -> env.setProperty("yanji." + key, value.toString()));
    var storage = new StorageService(env);
    String key = "qa-backend-verification/" + UUID.randomUUID() + ".pdf";
    byte[] bytes = "%PDF-1.4\nQA storage probe\n%%EOF\n".getBytes(StandardCharsets.UTF_8);
    try {
      storage.put(key, bytes, "application/pdf");
      assertThat(storage.get(key)).isEqualTo(bytes);
    } finally {
      storage.delete(key);
    }
  }
}
