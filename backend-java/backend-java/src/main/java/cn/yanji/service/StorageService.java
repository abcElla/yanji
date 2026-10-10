package cn.yanji.service;

import cn.yanji.core.AppError;
import cn.yanji.core.LogDiagnostics;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import java.io.*;
import java.nio.file.*;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

@org.springframework.stereotype.Service
public class StorageService {
  final Path root;
  final MinioClient minio;
  final String bucket;

  public StorageService(Environment env) {
    root =
        Path.of(env.getProperty("yanji.storage-root", "./private_files"))
            .toAbsolutePath()
            .normalize();
    bucket = env.getProperty("yanji.minio-bucket", "yanji-files");
    minio =
        "minio".equals(env.getProperty("yanji.storage-backend"))
            ? MinioClient.builder()
                .endpoint(env.getProperty("yanji.minio-endpoint"))
                .credentials(
                    env.getProperty("yanji.minio-access-key"),
                    env.getProperty("yanji.minio-secret-key"))
                .build()
            : null;
  }

  Path path(String key) {
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root) || key.contains("..") || Path.of(key).isAbsolute())
      throw new IllegalArgumentException("Invalid object key");
    return p;
  }

  public void put(String key, byte[] bytes, String mime) {
    try {
      if (minio == null) {
        Path p = path(key);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes, StandardOpenOption.CREATE_NEW);
      } else
        minio.putObject(
            PutObjectArgs.builder().bucket(bucket).object(key).stream(
                    new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType(mime)
                .build());
    } catch (Exception e) {
      diagnostic("put", e);
      throw new AppError(503, "STORAGE_UNAVAILABLE", "文件存储暂不可用");
    }
  }

  public byte[] get(String key) {
    try {
      if (minio == null) return Files.readAllBytes(path(key));
      try (InputStream in =
          minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
        return in.readNBytes(20 * 1024 * 1024 + 1);
      }
    } catch (Exception e) {
      diagnostic("get", e);
      throw new AppError(503, "STORAGE_UNAVAILABLE", "文件暂不可用");
    }
  }

  public void delete(String key) {
    try {
      if (minio == null) Files.deleteIfExists(path(key));
      else minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception error) {
      diagnostic("delete", error);
    }
  }

  private void diagnostic(String operation, Exception error) {
    String reason = LogDiagnostics.reason(error);
    if (error instanceof ErrorResponseException response) {
      String code = response.errorResponse().code();
      if (code != null && code.matches("[A-Za-z0-9_]{1,80}")) reason = code;
    }
    LoggerFactory.getLogger(StorageService.class)
        .warn(
            "Storage operation={} backend={} reason={} exception={}",
            operation,
            minio == null ? "local" : "minio",
            reason,
            LogDiagnostics.exceptionType(error));
  }
}
