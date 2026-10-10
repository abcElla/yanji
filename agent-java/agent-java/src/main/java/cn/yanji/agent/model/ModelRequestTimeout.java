package cn.yanji.agent.model;

/** Per-thread transport selection: no headers or credentials are modified. */
public final class ModelRequestTimeout implements AutoCloseable {
  private static final ThreadLocal<Boolean> PLANNING = new ThreadLocal<>();
  private final Boolean previous;

  private ModelRequestTimeout() {
    previous = PLANNING.get();
    PLANNING.set(true);
  }

  public static ModelRequestTimeout planning() {
    return new ModelRequestTimeout();
  }

  public static boolean isPlanning() {
    return Boolean.TRUE.equals(PLANNING.get());
  }

  @Override
  public void close() {
    if (previous == null) PLANNING.remove();
    else PLANNING.set(previous);
  }
}
