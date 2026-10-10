package cn.yanji.agent.contract;

/** Stable public error; never include provider bodies, headers, or credentials. */
public class AgentFailure extends RuntimeException {
  private final String code;
  private final boolean retryable;
  private final boolean ownershipLost;

  public AgentFailure(String code, String message, boolean retryable) {
    this(code, message, retryable, false);
  }

  public AgentFailure(String code, String message, boolean retryable, boolean ownershipLost) {
    super(message);
    this.code = code;
    this.retryable = retryable;
    this.ownershipLost = ownershipLost;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public boolean ownershipLost() {
    return ownershipLost;
  }
}
