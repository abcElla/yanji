package cn.yanji.agent.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface BackendClient {
  ObjectNode claim(String runId, String workerId);

  ObjectNode context(Lease lease);

  ObjectNode search(Lease lease, ObjectNode query);

  ObjectNode source(Lease lease, String sourceId, int revision, ObjectNode locator);

  void heartbeat(Lease lease);

  void event(Lease lease, String key, String step, String status, String message);

  void finalizeRun(Lease lease, ObjectNode body);

  void fail(Lease lease, String code, String message, boolean retryable);
}
