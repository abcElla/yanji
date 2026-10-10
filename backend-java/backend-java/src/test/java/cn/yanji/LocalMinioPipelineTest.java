package cn.yanji;

import static cn.yanji.core.Data.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.yanji.mapper.Repository;
import cn.yanji.service.StorageService;
import cn.yanji.worker.BackgroundWorkers;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

@EnabledIfSystemProperty(named = "verify.local.minio", matches = "true")
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:minioflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY;DB_CLOSE_DELAY=-1",
      "yanji.workers-enabled=false",
      "logging.file.name=./target/minio-flow.log"
    })
@AutoConfigureMockMvc
class LocalMinioPipelineTest {
  @Autowired MockMvc mvc;
  @Autowired Repository db;
  @Autowired StorageService storage;
  @Autowired BackgroundWorkers workers;

  @DynamicPropertySource
  static void storageSettings(DynamicPropertyRegistry registry) throws Exception {
    Map<?, ?> config;
    try (var input = java.nio.file.Files.newInputStream(Path.of("config/application-local.yml"))) {
      config = new Yaml().load(input);
    }
    var values = (Map<?, ?>) config.get("yanji");
    for (String name :
        List.of(
            "storage-backend",
            "minio-endpoint",
            "minio-access-key",
            "minio-secret-key",
            "minio-bucket")) {
      Object value = values.get(name);
      registry.add("yanji." + name, () -> value);
    }
  }

  Map<String, Object> call(String method, String path, String token, Object body, int status)
      throws Exception {
    var request =
        request(org.springframework.http.HttpMethod.valueOf(method), path)
            .contentType("application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.content(json(body));
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(status);
    return obj(obj(parse(response.getContentAsString())).get("data"));
  }

  String[] account(String role) throws Exception {
    String username = "qa" + uid().replace("-", "").substring(0, 15);
    var user =
        call(
            "POST",
            "/api/v1/auth/register",
            null,
            map("username", username, "password", "Probe123", "display_name", "QA", "role", role),
            201);
    var login =
        call(
            "POST",
            "/api/v1/auth/login",
            null,
            map(
                "username",
                username,
                "password",
                "Probe123",
                "installation_id",
                uid(),
                "device_label",
                "QA"),
            200);
    return new String[] {str(user, "user_id"), str(login, "access_token")};
  }

  @Test
  void realMinioPdfAndDocxUploadIngestDownloadAndReplay() throws Exception {
    var student = account("student");
    var mentor = account("mentor");
    String lab =
        str(
            obj(list(call("GET", "/api/v1/me", mentor[1], null, 200).get("memberships")).get(0)),
            "lab_id");
    var application =
        obj(
            call(
                    "POST",
                    "/api/v1/mentor-applications",
                    student[1],
                    map("mentor_id", mentor[0], "lab_id", lab),
                    201)
                .get("application"));
    call(
        "POST",
        "/api/v1/mentor-applications/" + str(application, "id") + "/decision",
        mentor[1],
        map("decision", "approve"),
        200);
    byte[] pdf;
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
        var output = new ByteArrayOutputStream()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(
            new org.apache.pdfbox.pdmodel.font.PDType1Font(
                org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
            12);
        content.newLineAtOffset(50, 700);
        content.showText("QA PDF evidence");
        content.endText();
      }
      document.save(output);
      pdf = output.toByteArray();
    }
    var uploaded = new ArrayList<String>();
    try {
      for (String extension : List.of("pdf", "docx")) {
        byte[] bytes =
            extension.equals("pdf") ? pdf : BackendIntegrationTest.docx("QA DOCX evidence");
        String mime =
            extension.equals("pdf")
                ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        String client = uid(), key = uid();
        var file = new MockMultipartFile("file", "qa." + extension, mime, bytes);
        var request =
            multipart("/api/v1/files")
                .file(file)
                .param("purpose", "paper")
                .param("lab_id", lab)
                .param("client_upload_id", client)
                .header("Authorization", "Bearer " + student[1])
                .header("Idempotency-Key", key);
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        String fid = str(obj(obj(parse(response.getContentAsString())).get("data")), "file_id");
        uploaded.add(fid);
        var repeated = mvc.perform(request).andReturn().getResponse();
        assertThat(repeated.getStatus()).isEqualTo(201);
        assertThat(str(obj(obj(parse(repeated.getContentAsString())).get("data")), "file_id"))
            .isEqualTo(fid);
        var paper =
            call(
                "POST",
                "/api/v1/papers",
                student[1],
                map("file_id", fid, "title", "QA " + extension, "visibility", "lab"),
                202);
        workers.processFileOnce();
        var ready = call("GET", "/api/v1/papers/" + str(paper, "paper_id"), student[1], null, 200);
        assertThat(ready.get("ingest_status")).isEqualTo("ready");
        assertThat(
                call("GET", "/api/v1/sources/" + str(ready, "source_id"), student[1], null, 200)
                    .get("excerpt")
                    .toString())
            .contains("evidence");
        var download =
            mvc.perform(
                    get("/api/v1/files/" + fid + "/download")
                        .header("Authorization", "Bearer " + student[1]))
                .andReturn()
                .getResponse();
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo(bytes);
      }
    } finally {
      for (String id : uploaded) storage.delete(str(db.get("files", id), "object_key"));
    }
  }
}
