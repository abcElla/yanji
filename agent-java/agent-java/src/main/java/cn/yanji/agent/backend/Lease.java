package cn.yanji.agent.backend;

public record Lease(String runId, int attempt, String token) {
  @Override
  public String toString() {
    return "Lease[runId=" + runId + ", attempt=" + attempt + ", token=<redacted>]";
  }
}
