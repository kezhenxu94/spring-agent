package me.kezhenxu94.springagent.core.agent;

import java.util.Map;
import java.util.function.Consumer;
import lombok.Builder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

@Builder
public record AgentRequest(
    Map<String, Object> promptVariables,
    Consumer<ChatClient.PromptUserSpec> userMessage,
    Object[] tools,
    ToolCallback[] toolCallbacks,
    Map<String, Object> toolContext,
    String conversationId,
    String memoriesRootDirectory,
    boolean conversationMemory,
    String requestId) {}
