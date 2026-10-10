package cn.yanji.core;

/** The shared public and internal JSON envelope. */
public record Result<T>(int code, T data, String message) {
  public static <T> Result<T> success(T data) {
    return new Result<>(0, data, "success");
  }
}
