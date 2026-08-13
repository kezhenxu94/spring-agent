package me.kezhenxu94.springagent.core.tools.interceptors;

import com.google.common.base.Strings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LargeResponseInterceptor implements ToolCallInterceptor {

  final SpringAgentProperties appConfiguration;
  final UserWorkspaceFactory userWorkspaceFactory;

  @Override
  @SneakyThrows
  public String afterCall(
      String toolName, String toolInput, String toolResult, ToolContext toolContext) {
    if (toolResult == null
        || toolResult.length() <= appConfiguration.ai().botInterceptor().guideThreshold()) {
      return toolResult;
    }

    final var size = toolResult.length();
    log.info(
        "Tool '{}' returned {} chars, guide-threshold={}",
        toolName,
        size,
        appConfiguration.ai().botInterceptor().guideThreshold());

    final var userId = ToolContexts.get(toolContext, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(userId)) {
      log.warn(
          "Tool '{}' returned large result ({} chars) but no userId in ToolContext; "
              + "returning raw result without persisting.",
          toolName,
          size);
      return toolResult;
    }

    final var userHome = userWorkspaceFactory.forOwner(userId);
    final var artifactsDir = userHome.artifacts().resolve("tool-results");
    Files.createDirectories(artifactsDir);
    final var file = artifactsDir.resolve(toolName + "-" + Instant.now().toEpochMilli() + ".txt");
    Files.writeString(file, toolResult);
    log.info("Saved large tool result for '{}' to: {}", toolName, file.toAbsolutePath());

    return buildGuide(toolName, size, file.toAbsolutePath());
  }

  private String buildGuide(String toolName, int size, Path path) {
    return Strings.lenientFormat(
        """
        The result of '%s' was too large (%d chars) to include directly.
        It has been saved to: %s
        Inspect it with the tools available to you:
          - Read %s (use offset/limit for slices)
          - Grep for patterns inside the file
          - Bash: jq / yq / grep / head / tail / wc on %s
        If the user asked for the raw file, use sendFile %s.\
        """,
        toolName, size, path, path, path, path);
  }
}
