package cn.yanji.agent.workflow;

public final class ModelCallBudget {
  private final int limit;
  private int used;

  public ModelCallBudget(int limit) {
    if (limit < 1) throw new IllegalArgumentException("Invalid model call limit");
    this.limit = limit;
  }

  public synchronized void reserve() {
    if (used >= limit)
      throw new cn.yanji.agent.contract.AgentFailure(
          "MODEL_CALL_BUDGET_EXCEEDED", "模型调用次数已达到本次预算", false);
    used++;
  }

  public synchronized int remaining() {
    return limit - used;
  }
}
