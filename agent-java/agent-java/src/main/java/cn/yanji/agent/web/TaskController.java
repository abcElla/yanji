package cn.yanji.agent.web;

import cn.yanji.agent.workflow.TaskDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class TaskController {
  private final TaskDispatcher dispatcher;

  public TaskController(TaskDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  public record Submission(
      @Min(1) @Max(1) int schema_version, @NotNull UUID run_id, @NotNull UUID event_id) {}

  @PostMapping("/internal/v1/tasks")
  public ResponseEntity<?> submit(@Valid @RequestBody Submission task) {
    var result = dispatcher.submit(task.run_id().toString());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            Map.of(
                "code",
                0,
                "message",
                "success",
                "data",
                Map.of(
                    "run_id",
                    task.run_id(),
                    "event_id",
                    task.event_id(),
                    "acceptance",
                    result.name().toLowerCase(),
                    "durable",
                    false)));
  }
}
