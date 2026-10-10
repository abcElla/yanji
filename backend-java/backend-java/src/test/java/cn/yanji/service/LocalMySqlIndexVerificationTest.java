package cn.yanji.service;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;

import cn.yanji.mapper.Repository;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

/** Opt-in: builds only embedding metadata for existing immutable chunks, never business records. */
@EnabledIfSystemProperty(named = "verify.local.mysql.index", matches = "true")
class LocalMySqlIndexVerificationTest {
  @Test
  void buildsFirstBatchOfExistingMySqlChunkVectors() throws Exception {
    Map<?, ?> config;
    try (var input = java.nio.file.Files.newInputStream(Path.of("config/application-local.yml"))) {
      config = new Yaml().load(input);
    }
    var spring = (Map<?, ?>) config.get("spring");
    var database = (Map<?, ?>) spring.get("datasource");
    var datasource =
        new DriverManagerDataSource(
            database.get("url").toString(),
            database.get("username").toString(),
            database.get("password").toString());
    var jdbc = new JdbcTemplate(datasource);
    var repository = new Repository(jdbc);
    var env =
        new MockEnvironment()
            .withProperty("yanji.retrieval-mode", "hybrid")
            .withProperty("yanji.embedding-url", "http://127.0.0.1:8092/v1/embeddings");
    var embeddings = new EmbeddingService(env, repository);
    embeddings.indexPending();
    var count =
        repository.query("chunks", "WHERE model_id=?", embeddings.model()).stream()
            .filter(embeddings::indexed)
            .count();
    assertThat(count).isGreaterThan(0);
    System.out.println("Existing MySQL chunks with verified BGE index metadata: " + count);
  }
}
