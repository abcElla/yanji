package cn.yanji.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class Data {
  private Data() {}

  public static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Object value) {
    return value instanceof List ? (List<Object>) value : new ArrayList<>();
  }

  public static String str(Map<String, Object> m, String key) {
    return m.get(key) == null ? null : m.get(key).toString();
  }

  public static int num(Map<String, Object> m, String key) {
    return m.get(key) instanceof Number n
        ? n.intValue()
        : Integer.parseInt(String.valueOf(m.get(key)));
  }

  public static boolean eq(Object a, Object b) {
    return Objects.equals(a, b) || (a != null && b != null && a.toString().equals(b.toString()));
  }

  public static Map<String, Object> pick(Map<String, Object> m, String... keys) {
    Map<String, Object> r = map();
    for (String k : keys) r.put(k, m.get(k));
    return r;
  }

  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static Object parse(String value) {
    try {
      return JSON.readValue(value, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public static String uid() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return Instant.now().toString();
  }

  public static Instant instant(Object value) {
    return OffsetDateTime.parse(value.toString()).toInstant();
  }

  public static String due(Object value) {
    if (value == null) return null;
    try {
      String s = value.toString();
      return s.length() == 10
          ? LocalDate.parse(s)
              .atTime(23, 59, 59)
              .atZone(ZoneId.of("Asia/Shanghai"))
              .toInstant()
              .toString()
          : instant(s).toString();
    } catch (Exception e) {
      throw new AppError(422, "VALIDATION_ERROR", "due_at 必须是含时区的时间或日期");
    }
  }

  public static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String token(int bytes) {
    byte[] b = new byte[bytes];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw new AppError(status, code, message);
  }

  public static boolean before(Object at) {
    return at != null && instant(at).isBefore(Instant.now());
  }

  public static Map<String, Object> page(
      List<Map<String, Object>> rows, Map<String, Object> query, String key, String at) {
    int limit = Integer.parseInt(String.valueOf(query.getOrDefault("limit", 20)));
    require(limit >= 1 && limit <= 100, 422, "VALIDATION_ERROR", "limit 必须为1至100");
    rows = new ArrayList<>(rows);
    rows.sort(
        Comparator.<Map<String, Object>, Instant>comparing(r -> instant(r.get(at)))
            .thenComparing(r -> String.valueOf(r.get("id")))
            .reversed());
    if (query.get("cursor") != null) {
      try {
        String cursor = str(query, "cursor");
        if (cursor.length() > 1024) throw new IllegalArgumentException();
        List<Object> payload =
            list(parse(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)));
        Instant time = instant(payload.get(0));
        String id = payload.get(1).toString();
        UUID.fromString(id);
        rows.removeIf(
            r ->
                instant(r.get(at)).isAfter(time)
                    || (instant(r.get(at)).equals(time)
                        && r.get("id").toString().compareTo(id) >= 0));
      } catch (Exception e) {
        throw new AppError(422, "VALIDATION_ERROR", "cursor 无效");
      }
    }
    String next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      Map<String, Object> last = rows.get(rows.size() - 1);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  json(List.of(last.get(at), last.get("id"))).getBytes(StandardCharsets.UTF_8));
    }
    return map(key, rows, "next_cursor", next);
  }
}
