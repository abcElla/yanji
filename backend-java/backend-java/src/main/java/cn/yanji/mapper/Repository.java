package cn.yanji.mapper;

import static cn.yanji.core.Data.*;

import cn.yanji.core.*;
import java.sql.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** All identifiers originate in the imported table metadata, never in HTTP input. */
@org.springframework.stereotype.Repository
public class Repository {
  private final JdbcTemplate jdbc;
  private final Map<String, Object> tables;

  public Repository(JdbcTemplate jdbc) throws Exception {
    this.jdbc = jdbc;
    this.tables =
        obj(JSON.readValue(new ClassPathResource("tables.json").getInputStream(), Object.class));
  }

  private Map<String, Object> columns(String table) {
    if (!tables.containsKey(table)) throw new IllegalArgumentException("Unknown table");
    return obj(tables.get(table));
  }

  public List<Map<String, Object>> query(String table, String clause, Object... args) {
    Map<String, Object> cols = columns(table);
    return jdbc.query(
        "SELECT * FROM `" + table + "` " + clause,
        (rs, row) -> {
          Map<String, Object> out = map();
          for (String name : cols.keySet()) {
            Object v = rs.getObject(name);
            String kind = str(obj(cols.get(name)), "kind");
            if (v != null) {
              if (kind.equals("json")) {
                String s = rs.getString(name);
                Object p = parse(s);
                if (p instanceof String t) p = parse(t);
                v = p;
              } else if (kind.equals("datetime")) v = rs.getTimestamp(name).toInstant().toString();
              else if (kind.equals("date")) v = rs.getObject(name, java.time.LocalDate.class).toString();
              else if (kind.equals("boolean")) v = rs.getBoolean(name);
              else if (kind.equals("integer")) v = rs.getLong(name);
              else v = rs.getString(name);
            }
            out.put(name, v);
          }
          return out;
        },
        args);
  }

  public List<Map<String, Object>> all(String table) {
    return query(table, "");
  }

  public Map<String, Object> one(String table, String clause, Object... args) {
    List<Map<String, Object>> rows = query(table, clause, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> get(String table, String id) {
    return id == null ? null : one(table, "WHERE id=?", id);
  }

  public Map<String, Object> lock(String table, String id) {
    return id == null ? null : one(table, "WHERE id=? FOR UPDATE", id);
  }

  private Object dbValue(String table, String key, Object value) {
    if (value == null) return null;
    String kind = str(obj(columns(table).get(key)), "kind");
    return switch (kind) {
      case "json" -> json(value);
      case "datetime" -> Timestamp.from(instant(value));
      case "date" -> java.time.LocalDate.parse(value.toString());
      default -> value;
    };
  }

  public Map<String, Object> insert(String table, Map<String, Object> values) {
    Map<String, Object> row = map();
    Map<String, Object> cols = columns(table);
    for (String key : cols.keySet()) {
      Object value = values.get(key);
      if (!values.containsKey(key)) {
        value = obj(cols.get(key)).get("default");
        if (eq(value, "@uuid")) value = uid();
        if (eq(value, "@now")) value = now();
      }
      row.put(key, value);
    }
    String keys = String.join(",", row.keySet().stream().map(k -> "`" + k + "`").toList());
    jdbc.update(
        "INSERT INTO `"
            + table
            + "` ("
            + keys
            + ") VALUES ("
            + String.join(",", Collections.nCopies(row.size(), "?"))
            + ")",
        row.entrySet().stream().map(e -> dbValue(table, e.getKey(), e.getValue())).toArray());
    return row;
  }

  public void update(String table, Map<String, Object> row) {
    List<String> keys =
        columns(table).keySet().stream()
            .filter(k -> !k.equals("id") && row.containsKey(k))
            .toList();
    List<Object> values = new ArrayList<>();
    for (String k : keys) values.add(dbValue(table, k, row.get(k)));
    values.add(row.get("id"));
    jdbc.update(
        "UPDATE `"
            + table
            + "` SET "
            + String.join(",", keys.stream().map(k -> "`" + k + "`=?").toList())
            + " WHERE id=?",
        values.toArray());
  }

  public void execute(String sql, Object... args) {
    jdbc.update(sql, args);
  }

  public void outbox(String type, Map<String, Object> payload) {
    insert("outbox_events", map("event_type", type, "payload", payload));
  }

  public void version(Map<String, Object> row, Map<String, Object> body) {
    if (!eq(row.get("version"), body.get("expected_version")))
      throw new AppError(
          409,
          "VERSION_CONFLICT",
          "内容已更新，请刷新后重试",
          map("current_version", row.get("version")),
          false);
  }

  public void bump(String table, Map<String, Object> row) {
    row.put("version", num(row, "version") + 1);
    update(table, row);
  }
}
