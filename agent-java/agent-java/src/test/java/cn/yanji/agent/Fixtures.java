package cn.yanji.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class Fixtures {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String RUN = "10000000-0000-4000-8000-000000000001";
  static final String STUDENT = "10000000-0000-4000-8000-000000000002";
  static final String SOURCE = "10000000-0000-4000-8000-000000000003";
  static final String STAGE = "10000000-0000-4000-8000-000000000004";

  static ObjectNode object(String value) {
    try {
      return (ObjectNode) JSON.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  static ObjectNode context(String kind) {
    ObjectNode c =
        object(
            """
            {"schema_version":2,"kind":"research_qa","student_id":"%s","run_id":"%s",
             "subject":{},"input":{"question":"结果"},"allowed_actions":[],"tasks":[],
             "confirmed_experiments":[],"confirmed_notes":[],"business_versions":[],
             "source_versions":[{"source_id":"%s","revision":1}],"limits":{"max_proposals":20}}
            """
                .formatted(STUDENT, RUN, SOURCE));
    c.put("kind", kind);
    return c;
  }

  static ObjectNode reference() {
    return object(
        """
            {"source_id":"%s","source_revision":1,"chunk_id":"chunk-1",
             "locator":{"kind":"pdf_page","page":1},"quote":"结果为10"}
            """
            .formatted(SOURCE));
  }

  static ArrayNode evidence() {
    ArrayNode rows = JSON.createArrayNode();
    ObjectNode row = reference();
    row.remove("quote");
    row.put("text", "实验结果为10，尚未重复验证。");
    rows.add(row);
    return rows;
  }

  static ObjectNode answer() {
    ObjectNode draft =
        object(
            """
            {"result":{"outcome":"ok","summary":"有实验结果，尚需验证。","claims":[],
             "missing_inputs":[],"limitations":[]},"proposal_drafts":[]}
            """);
    ObjectNode claim = JSON.createObjectNode().put("text", "结果为10");
    claim.putArray("source_refs").add(reference());
    ((ArrayNode) draft.path("result").path("claims")).add(claim);
    return draft;
  }

  static ObjectNode plan() {
    ObjectNode p =
        object(
            """
            {"action_type":"task.create_batch","reason":"补充验证","source_refs":[],
             "payload":{"tasks":[{"local_key":"a","title":"验证实验","type":"experiment",
             "stage_id":"%s","acceptance_criteria":["有记录"],"depends_on_local_keys":[],"existing_dependency_ids":[]}]}}
            """
                .formatted(STAGE));
    p.withArray("source_refs").add(reference());
    return p;
  }

  static ObjectNode planningContext() {
    ObjectNode c = context("stage_plan");
    c.withArray("allowed_actions").add("task.create_batch");
    c.set("stage", object("{\"id\":\"" + STAGE + "\",\"version\":1}"));
    c.withArray("business_versions")
        .add(object("{\"type\":\"stage\",\"id\":\"" + STAGE + "\",\"version\":1}"));
    return c;
  }
}
