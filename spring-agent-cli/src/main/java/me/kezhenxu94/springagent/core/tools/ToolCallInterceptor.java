package me.kezhenxu94.springagent.core.tools;

import org.springframework.ai.chat.model.ToolContext;

public interface ToolCallInterceptor {

  default String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
    return toolInput;
  }

  default String afterCall(
      String toolName, String toolInput, String toolResult, ToolContext toolContext) {
    return toolResult;
  }
}
