package me.kezhenxu94.springagent.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Singular;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Everything an integration has to say to run the agent once. Purely descriptive: {@link
 * SpringAgent} turns this into a prompt, a tool set and a tool context, so an integration never
 * touches the tool provider, the MCP client lifecycle or reactor.
 *
 * @param requestId the key {@link SpringAgent#cancel(String)} stops this run by
 * @param scenario selects which tools the run gets and whether it accumulates conversation history
 * @param chatType free-form, defaults to {@code p2p}
 * @param conversationId groups runs that share chat memory
 * @param rootMessageId opaque thread identifier minted by the integration, never interpreted here
 * @param replyMessageId opaque identifier of the message this run responds to
 * @param background whether the run is unattended: a background run is not shown anywhere and its
 *     answer is not delivered — no reply, no card, no progress, nothing to stop it with — so it
 *     reaches a person only through what it sends while running, say a message tool it calls
 *     itself. The default is a foreground run, whose answer a surface streams back to whoever it is
 *     for. A surface may still report a run that failed, which is the one thing a background run
 *     cannot report for itself.
 * @param promptVariables extra system-prompt variables; the identity ones are filled in by core,
 *     and {@code threadId}, {@code parentId} and {@code mentions} default to empty when not given
 * @param toolContext extra tool-context entries; the {@link
 *     me.kezhenxu94.springagent.core.tools.ToolContexts} identity keys are filled in by core and
 *     win on conflict
 */
@Builder
public record AgentRequest(
    String requestId,
    AgentScenario scenario,
    String userId,
    String chatId,
    String chatType,
    String conversationId,
    String rootMessageId,
    String replyMessageId,
    boolean background,
    Map<String, Object> promptVariables,
    Consumer<ChatClient.PromptUserSpec> userMessage,
    Map<String, Object> toolContext,
    @Singular List<AgentResponseListener> listeners,
    @Singular List<TodoEventHandler> todoEventHandlers) {

  private static final String DEFAULT_CHAT_TYPE = "p2p";

  public AgentRequest {
    scenario = Objects.requireNonNull(scenario, "scenario");
    userMessage = Objects.requireNonNull(userMessage, "userMessage");
    chatType = Objects.toString(chatType, DEFAULT_CHAT_TYPE);
    // Left as given rather than copied: tool-context values are live objects an integration shares
    // with its own tools, and both maps may legitimately carry a null under a known key.
    promptVariables = promptVariables == null ? Map.of() : promptVariables;
    toolContext = toolContext == null ? Map.of() : toolContext;
  }
}
