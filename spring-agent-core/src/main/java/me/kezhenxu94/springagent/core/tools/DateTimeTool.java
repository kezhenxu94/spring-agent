package me.kezhenxu94.springagent.core.tools;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class DateTimeTool {

  @Builder
  @Jacksonized
  public static record CurrentDateTime(String dateTime) {}

  @SneakyThrows
  @Tool(
      name = "CurrentDateTime",
      description =
          "The current date and time, and the basis for working out anything relative such as"
              + " 'tomorrow' or 'next month'.")
  public CurrentDateTime currentDateTime(final ToolContext context) {
    final var currentDateTime = java.time.ZonedDateTime.now().toString();
    log.info("Current date and time: {}", currentDateTime);
    return new CurrentDateTime(currentDateTime);
  }
}
