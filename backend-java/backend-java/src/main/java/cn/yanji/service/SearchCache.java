package cn.yanji.service;

import static cn.yanji.core.Data.*;

import cn.yanji.core.LogDiagnostics;
import java.time.Duration;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

@org.springframework.stereotype.Service
public class SearchCache {
  final StringRedisTemplate redis;
  final Environment env;

  public SearchCache(StringRedisTemplate redis, Environment env) {
    this.redis = redis;
    this.env = env;
  }

  public record Lookup(Map<String, Object> value, String status) {}

  public Lookup get(String key) {
    if (!env.getProperty("yanji.redis-enabled", Boolean.class, false))
      return new Lookup(null, "disabled");
    try {
      String value = redis.opsForValue().get(key);
      return new Lookup(value == null ? null : obj(parse(value)), value == null ? "miss" : "hit");
    } catch (Exception error) {
      LoggerFactory.getLogger(SearchCache.class)
          .warn(
              "Search cache unavailable reason={} exception={}",
              LogDiagnostics.reason(error),
              LogDiagnostics.exceptionType(error));
      return new Lookup(null, "unavailable");
    }
  }

  public void put(String key, Map<String, Object> value) {
    if (!env.getProperty("yanji.redis-enabled", Boolean.class, false)) return;
    try {
      int seconds = env.getProperty("yanji.redis-cache-ttl-seconds", Integer.class, 60);
      redis
          .opsForValue()
          .set(key, json(value), Duration.ofSeconds(Math.max(1, Math.min(seconds, 300))));
    } catch (Exception error) {
      LoggerFactory.getLogger(SearchCache.class)
          .warn("Search cache write skipped reason={}", LogDiagnostics.reason(error));
    }
  }
}
