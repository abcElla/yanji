package cn.yanji.core;

import static cn.yanji.core.Data.*;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/** Uses the imported OpenAPI as the request schema, including strict nested fields. */
@org.springframework.stereotype.Component
public class Contract {
  public final Map<String, Object> document;

  public Contract() throws Exception {
    document =
        obj(JSON.readValue(new ClassPathResource("openapi.json").getInputStream(), Object.class));
  }

  public record Route(
      String path,
      String operation,
      int status,
      Map<String, Object> definition,
      Map<String, Object> variables) {}

  public Route route(String method, String path) {
    for (var entry : obj(document.get("paths")).entrySet()) {
      String[] template = entry.getKey().split("/"), actual = path.split("/");
      if (template.length != actual.length) continue;
      Map<String, Object> vars = map();
      boolean match = true;
      for (int i = 0; i < template.length; i++) {
        if (template[i].startsWith("{"))
          vars.put(template[i].substring(1, template[i].length() - 1), actual[i]);
        else if (!template[i].equals(actual[i])) {
          match = false;
          break;
        }
      }
      if (!match) continue;
      Map<String, Object> op = obj(obj(entry.getValue()).get(method.toLowerCase(Locale.ROOT)));
      if (op.isEmpty()) throw new AppError(405, "METHOD_NOT_ALLOWED", "请求方法不支持");
      int status =
          obj(op.get("responses")).keySet().stream()
              .filter(s -> s.startsWith("2"))
              .mapToInt(Integer::parseInt)
              .findFirst()
              .orElse(200);
      return new Route(entry.getKey(), str(op, "operationId"), status, op, vars);
    }
    throw new AppError(404, "NOT_FOUND", "接口不存在");
  }

  private Map<String, Object> resolve(Map<String, Object> s) {
    return s.containsKey("$ref")
        ? obj(
            obj(obj(document.get("components")).get("schemas"))
                .get(str(s, "$ref").substring(str(s, "$ref").lastIndexOf('/') + 1)))
        : s;
  }

  public void validateBody(Route route, Map<String, Object> body) {
    Map<String, Object> schema =
        obj(
            obj(obj(obj(route.definition().get("requestBody")).get("content"))
                    .get("application/json"))
                .get("schema"));
    if (!schema.isEmpty()) validate(body, schema, "body");
  }

  public void validateNamed(Map<String, Object> body, String schema) {
    validate(body, map("$ref", "#/components/schemas/" + schema), schema);
  }

  public void validateParams(Route route, Map<String, Object> query, Map<String, Object> headers) {
    for (Object raw : list(route.definition().get("parameters"))) {
      Map<String, Object> p = obj(raw);
      String name = str(p, "name"), loc = str(p, "in");
      Map<String, Object> values =
          loc.equals("path") ? route.variables() : loc.equals("header") ? headers : query;
      Map<String, Object> schema = resolve(obj(p.get("schema")));
      Object value = values.get(name);
      if (value == null) {
        require(!eq(p.get("required"), true), 422, "VALIDATION_ERROR", name + " 必填");
        if (schema.containsKey("default")) values.put(name, schema.get("default"));
        continue;
      }
      try {
        Map<String, Object> typed = schema;
        if (schema.containsKey("anyOf")) typed = resolve(obj(list(schema.get("anyOf")).get(0)));
        String type = str(typed, "type");
        if ("integer".equals(type)) value = Long.parseLong(value.toString());
        if ("boolean".equals(type)) {
          require(
              Set.of("true", "false", "1", "0").contains(value.toString().toLowerCase()),
              422,
              "VALIDATION_ERROR",
              name + " 必须是布尔值");
          value = Set.of("true", "1").contains(value.toString().toLowerCase());
        }
      } catch (NumberFormatException e) {
        throw new AppError(422, "VALIDATION_ERROR", name + " 格式错误");
      }
      validate(value, schema, name);
      values.put(name, value);
    }
  }

  private void bad(String path) {
    throw new AppError(422, "VALIDATION_ERROR", path + " 格式或取值无效");
  }

  public void validate(Object value, Map<String, Object> input, String path) {
    Map<String, Object> s = resolve(input);
    if (s.containsKey("anyOf")) {
      for (Object option : list(s.get("anyOf"))) {
        try {
          validate(value, obj(option), path);
          return;
        } catch (AppError ignored) {
        }
      }
      bad(path);
    }
    String type = str(s, "type");
    if (value == null) {
      if (!"null".equals(type) && type != null) bad(path);
      return;
    }
    if (s.containsKey("enum") && !list(s.get("enum")).contains(value)) bad(path);
    if (s.containsKey("const") && !eq(s.get("const"), value)) bad(path);
    if ("object".equals(type)) {
      if (!(value instanceof Map)) bad(path);
      Map<String, Object> v = obj(value), props = obj(s.get("properties"));
      for (Object required : list(s.get("required")))
        if (!v.containsKey(required.toString())) bad(path + "." + required);
      if (eq(s.get("additionalProperties"), false))
        for (String key : v.keySet()) if (!props.containsKey(key)) bad(path + "." + key);
      for (var p : props.entrySet()) {
        Map<String, Object> ps = resolve(obj(p.getValue()));
        if (!v.containsKey(p.getKey()) && ps.containsKey("default"))
          v.put(p.getKey(), parse(json(ps.get("default"))));
        if (v.containsKey(p.getKey())) validate(v.get(p.getKey()), ps, path + "." + p.getKey());
      }
      // Pydantic default_factory fields do not appear as defaults in OpenAPI.
      for (var p : props.entrySet()) {
        Map<String, Object> ps = resolve(obj(p.getValue()));
        if (!v.containsKey(p.getKey()) && "array".equals(str(ps, "type")))
          v.put(p.getKey(), new ArrayList<>());
        if (!v.containsKey(p.getKey())
            && "object".equals(str(ps, "type"))
            && !ps.containsKey("properties")) v.put(p.getKey(), map());
      }
      if (eq(v.get("origin"), "agent") && list(v.get("source_refs")).isEmpty())
        bad(path + ".source_refs");
      if (v.containsKey("kind")
          && Set.of("pdf_page", "docx_paragraph", "record_field").contains(str(v, "kind"))) {
        String field =
            switch (str(v, "kind")) {
              case "pdf_page" -> "page";
              case "docx_paragraph" -> "paragraph";
              default -> "field";
            };
        if (v.get(field) == null) bad(path + "." + field);
      }
    } else if ("array".equals(type)) {
      if (!(value instanceof List)) bad(path);
      List<Object> a = list(value);
      if (s.containsKey("maxItems") && a.size() > num(s, "maxItems")
          || s.containsKey("minItems") && a.size() < num(s, "minItems")) bad(path);
      for (Object item : a) validate(item, obj(s.get("items")), path + "[]");
    } else if ("string".equals(type)) {
      if (!(value instanceof String)) bad(path);
      String v = value.toString();
      if (s.containsKey("minLength") && v.length() < num(s, "minLength")
          || s.containsKey("maxLength") && v.length() > num(s, "maxLength")) bad(path);
      if (s.containsKey("pattern") && !Pattern.compile(str(s, "pattern")).matcher(v).find())
        bad(path);
      try {
        switch (String.valueOf(s.get("format"))) {
          case "uuid" -> UUID.fromString(v);
          case "date" -> LocalDate.parse(v);
          case "date-time" -> {
            if (v.length() == 10 && path.endsWith("due_at")) LocalDate.parse(v);
            else OffsetDateTime.parse(v);
          }
          default -> {}
        }
      } catch (Exception e) {
        bad(path);
      }
    } else if ("integer".equals(type) || "number".equals(type)) {
      if (!(value instanceof Number n)
          || ("integer".equals(type) && n.doubleValue() != n.longValue())) bad(path);
      double v = ((Number) value).doubleValue();
      if (s.containsKey("minimum") && v < ((Number) s.get("minimum")).doubleValue()
          || s.containsKey("maximum") && v > ((Number) s.get("maximum")).doubleValue()) bad(path);
    } else if ("boolean".equals(type) && !(value instanceof Boolean)) bad(path);
  }
}
