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
  @Tool(name = "CurrentDateTime", description = "获取当前的日期和时间, 或者用于计算相对时间, 比如 '明天' 或 '下个月' 等等.")
  public CurrentDateTime currentDateTime(final ToolContext context) {
    final var currentDateTime = java.time.ZonedDateTime.now().toString();
    log.info("Current date and time: {}", currentDateTime);
    return new CurrentDateTime(currentDateTime);
  }
}
