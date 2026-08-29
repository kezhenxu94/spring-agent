package me.kezhenxu94.springagent.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.Singular;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeRetrieval;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Everything an integration has to say to run the agent once. Purely descriptive: {@link
 * SpringAgent} turns this into a prompt, a tool set and a tool context, so an integration never
 * touches the tool provider, the MCP client lifecycle or reactor.
 *
 * @param requestId the key {@link SpringAgent#cancel(String)} stops this run by
 * @param parentRequestId the {@code requestId} of the run that started this one, null for a run
 *     nobody started — which is every run a surface receives. Naming a live parent ties the two
 *     together: cancelling the parent cancels this run, this run's token usage is reported to the
 *     parent's listeners as well as its own, and the parent does not report itself finished until
 *     this run has. Ignored where it names a run that has already ended.
 * @param description one line saying what this run is for, in the words of whoever started it.
 *     Reported to a parent run's listeners so a surface can name a run it is not streaming; null
 *     where nobody said, which is every run whose reason is the message that arrived.
 * @param brief the whole task this run was given, in the words of whoever started it — a subagent's
 *     brief, say. For a surface to show alongside {@code description}, which says what the run is
 *     for in one line where this says what it was actually asked to do; null wherever the run's own
 *     message is its brief, which is every run a surface receives.
 * @param scenario selects which tools the run gets and whether it accumulates conversation history
 * @param chatType free-form, defaults to {@code p2p}
 * @param conversationId groups runs that share chat memory
 * @param rootMessageId opaque thread identifier minted by the integration, never interpreted here
 * @param replyMessageId opaque identifier of the message this run responds to
 * @param groupId opaque group-chat identifier minted by the integration (e.g. a Feishu group chat
 *     id), never interpreted here; null/empty for a request with no group
 * @param tenantId opaque tenant/enterprise identifier minted by the integration (e.g. a Feishu
 *     tenant key), never interpreted here; null/empty for an integration with no tenant concept
 * @param background whether the run is unattended: a background run is not shown anywhere and its
 *     answer is not delivered — no reply, no card, no progress, nothing to stop it with — so it
 *     reaches a person only through what it sends while running, say a message tool it calls
 *     itself. The default is a foreground run, whose answer a surface streams back to whoever it is
 *     for. A surface may still report a run that failed, which is the one thing a background run
 *     cannot report for itself.
 * @param knowledgeRetrieval what the run's automatic retrieval should look at, for a run whose
 *     knowledge base is chosen by configuration rather than by who is asking. Null on every request
 *     a surface builds, and then the scope is the run's own identity and the query is the message —
 *     see {@link me.kezhenxu94.springagent.core.knowledge.KnowledgeRetrieval} for the case this is
 *     for. Ignored entirely where {@link AgentScenario#knowledgeRetrieval()} is false or the
 *     deployment has no knowledge base
 * @param promptVariables extra system-prompt variables; the identity ones are filled in by core,
 *     and {@code threadId}, {@code parentId} and {@code mentions} default to empty when not given
 * @param toolContext extra tool-context entries; the {@link
 *     me.kezhenxu94.springagent.core.tools.ToolContexts} identity keys are filled in by core and
 *     win on conflict
 */
@Builder
public record AgentRequest(
    String requestId,
    String parentRequestId,
    String description,
    String brief,
    AgentScenario scenario,
    String userId,
    String chatId,
    String chatType,
    String conversationId,
    String rootMessageId,
    String replyMessageId,
    String groupId,
    String tenantId,
    boolean background,
    KnowledgeRetrieval knowledgeRetrieval,
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
