package cn.yanji.agent.contract;

/** A rejected requirement needs an authoritative source; no rejected model text is reused. */
public final class UnsupportedRequirementEvidence extends AgentFailure {
  public UnsupportedRequirementEvidence(String message) {
    super("MODEL_OUTPUT_INVALID", message, false);
  }
}
