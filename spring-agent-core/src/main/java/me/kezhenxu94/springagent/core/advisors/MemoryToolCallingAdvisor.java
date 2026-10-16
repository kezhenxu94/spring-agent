package me.kezhenxu94.springagent.core.advisors;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

/**
 * The tool-calling loop and the conversation's memory as one advisor: memory is read once before
 * the turn and the whole turn — the user's message, every tool call with its results, any message
 * that arrived mid-run, and the answer — is written once after it.
 *
 * <p>In place of {@link MessageChatMemoryAdvisor}, because the two cannot share a turn. Spring AI
 * wires them as alternatives, each carrying the turn on its own: this advisor's own history in the
 * request, or the memory advisor inside the loop reading the store back on every round, in which
 * case the history has to be off. Both at once had the memory advisor prepend memory a second time
 * whenever what it read back was not {@code equals} to what the loop carried — always, on a store
 * that drops message metadata, which MongoDB here and Spring AI's JDBC repository both do — and the
 * request then held the turn's tool call twice, the first copy with no result after it, which
 * OpenAI rejects. Turning the history off instead costs what this runtime relies on it for: Spring
 * AI then forwards only the first system message and the single last message of a round, losing
 * every system-prompt part after the first and the tool results ahead of a message sent mid-run.
 * Owning both here keeps the history on and has nothing to prepend twice.
 *
 * <p>A run opts in by naming its {@link Conversation} under {@link #CONVERSATION} in the advisor
 * context; a run that does not is the plain tool-calling loop. Carried per request rather than
 * built in, so that one shared instance serves both kinds of run — which the tool-search subclass
 * needs, since its index lives on the instance.
 *
 * <p>Written once at the end, so a turn the process dies in the middle of is not kept. A turn that
 * fails or is stopped keeps what it completed: a tool call whose results never arrived is left out,
 * since a call without its results is what a provider rejects on the next turn.
 */
@Slf4j
public class MemoryToolCallingAdvisor extends ToolCallingAdvisor {

  /** The advisor-context key a run names its {@link Conversation} under. */
  public static final String CONVERSATION =
      MemoryToolCallingAdvisor.class.getName() + ".conversation";

  private static final String TURN = MemoryToolCallingAdvisor.class.getName() + ".turn";

  /** The memory a run reads and writes, and which of its conversations this is. */
  public record Conversation(ChatMemory memory, String id) {}

  protected MemoryToolCallingAdvisor(
      final ToolCallingManager toolCallingManager,
      final ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
      final int advisorOrder,
      final boolean conversationHistoryEnabled) {
    super(
        toolCallingManager,
        toolExecutionEligibilityChecker,
        advisorOrder,
        conversationHistoryEnabled);
  }

  @Override
  public ChatClientResponse adviseCall(
      final ChatClientRequest chatClientRequest, final CallAdvisorChain callAdvisorChain) {
    if (!(chatClientRequest.context().get(CONVERSATION) instanceof Conversation conversation)) {
      return super.adviseCall(chatClientRequest, callAdvisorChain);
    }
    final var turn = new Turn(conversation);
    var completed = false;
    ChatClientResponse response = null;
    try {
      response = super.adviseCall(turn.begin(chatClientRequest), callAdvisorChain);
      turn.observe(response);
      completed = true;
      return response;
    } finally {
      turn.save(completed);
    }
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      final ChatClientRequest chatClientRequest, final StreamAdvisorChain streamAdvisorChain) {
    if (!(chatClientRequest.context().get(CONVERSATION) instanceof Conversation conversation)) {
      return super.adviseStream(chatClientRequest, streamAdvisorChain);
    }
    return Flux.defer(
        () -> {
          final var turn = new Turn(conversation);
          final var begun = turn.begin(chatClientRequest);
          var responses = super.adviseStream(begun, streamAdvisorChain);
          if (!(begun.prompt().getOptions() instanceof ToolCallingChatOptions)) {
            // The loop passes such a request straight through and calls none of the hooks below,
            // so the one round it makes is read off the stream instead.
            responses =
                new ChatClientMessageAggregator()
                    .aggregateChatClientResponse(responses, turn::answered);
          }
          // Saved before the end of the stream reaches whoever is reading it, not after as
          // doFinally would: the next message in the conversation can arrive the moment a run is
          // seen to finish, and it has to find this turn already there.
          return responses
              .doOnNext(turn::observe)
              .doOnComplete(() -> turn.save(true))
              .doOnError(error -> turn.save(false))
              .doOnCancel(() -> turn.save(false));
        });
  }

  @Override
  protected ChatClientResponse doAfterCall(
      final ChatClientResponse chatClientResponse, final CallAdvisorChain callAdvisorChain) {
    turnOf(chatClientResponse).ifPresent(turn -> turn.answered(chatClientResponse));
    return super.doAfterCall(chatClientResponse, callAdvisorChain);
  }

  @Override
  protected ChatClientResponse doAfterStream(
      final ChatClientResponse chatClientResponse, final StreamAdvisorChain streamAdvisorChain) {
    turnOf(chatClientResponse).ifPresent(turn -> turn.answered(chatClientResponse));
    return super.doAfterStream(chatClientResponse, streamAdvisorChain);
  }

  @Override
  protected List<Message> doGetNextInstructionsForToolCall(
      final ChatClientRequest chatClientRequest,
      final ChatClientResponse chatClientResponse,
      final ToolExecutionResult toolExecutionResult) {
    turnOf(chatClientRequest).ifPresent(turn -> turn.ranTools(toolExecutionResult));
    return super.doGetNextInstructionsForToolCall(
        chatClientRequest, chatClientResponse, toolExecutionResult);
  }

  @Override
  protected List<Message> doGetNextInstructionsForToolCallStream(
      final ChatClientRequest chatClientRequest,
      final ChatClientResponse chatClientResponse,
      final ToolExecutionResult toolExecutionResult) {
    turnOf(chatClientRequest).ifPresent(turn -> turn.ranTools(toolExecutionResult));
    return super.doGetNextInstructionsForToolCallStream(
        chatClientRequest, chatClientResponse, toolExecutionResult);
  }

  /**
   * The turn a request or response belongs to. Spring AI copies the advisor context at every hop,
   * but only shallowly, so the one object put there in {@link Turn#begin} is the same one every
   * round of the loop sees.
   */
  private static Optional<Turn> turnOf(final ChatClientRequest request) {
    return request.context().get(TURN) instanceof Turn turn ? Optional.of(turn) : Optional.empty();
  }

  private static Optional<Turn> turnOf(final ChatClientResponse response) {
    return response.context().get(TURN) instanceof Turn turn ? Optional.of(turn) : Optional.empty();
  }

  /** One turn of one conversation, from the memory read before it to the write after it. */
  private static final class Turn {

    private final Conversation conversation;

    /** The messages of this turn as the request brought them: the user's, in practice. */
    private List<Message> opening = List.of();

    /** How many messages of memory were read in ahead of the turn. */
    private int remembered;

    /** The loop's history as of the last round whose tool results arrived, preceding included. */
    private @Nullable List<Message> history;

    /** The assistant message of the latest round, tool calls or answer. */
    private @Nullable AssistantMessage latest;

    /** The results of the tools that ended the turn, which no later round carries. */
    private final List<ToolResponse> endedTurn = new ArrayList<>();

    private boolean saved;

    Turn(final Conversation conversation) {
      this.conversation = conversation;
    }

    /** {@code request} with memory read in after its system messages, and this turn attached. */
    synchronized ChatClientRequest begin(final ChatClientRequest request) {
      final var memory = conversation.memory().get(conversation.id());
      final var instructions = request.prompt().getInstructions();
      final var system = instructions.stream().filter(SystemMessage.class::isInstance).toList();
      opening = instructions.stream().filter(m -> !(m instanceof SystemMessage)).toList();
      remembered = memory.size();
      final var messages = new ArrayList<Message>(instructions.size() + memory.size());
      messages.addAll(system);
      messages.addAll(memory);
      messages.addAll(opening);
      return request
          .mutate()
          .prompt(request.prompt().mutate().messages(messages).build())
          .context(TURN, this)
          .build();
    }

    synchronized void ranTools(final ToolExecutionResult result) {
      history = result.conversationHistory();
    }

    synchronized void answered(final ChatClientResponse response) {
      final var chatResponse = response.chatResponse();
      if (chatResponse == null) {
        return;
      }
      latest =
          chatResponse.getResults().stream()
              .map(Generation::getOutput)
              .filter(AssistantMessage::hasToolCalls)
              .findFirst()
              .orElseGet(
                  () ->
                      chatResponse.getResult() == null
                          ? null
                          : chatResponse.getResult().getOutput());
    }

    /**
     * Picks out the results of a {@code returnDirect} tool, which end the loop and come back as the
     * response itself — one generation per result, carrying the call's id and name.
     */
    synchronized void observe(final ChatClientResponse response) {
      final ChatResponse chatResponse = response.chatResponse();
      if (chatResponse == null) {
        return;
      }
      for (final var generation : chatResponse.getResults()) {
        final var metadata = generation.getMetadata();
        if (ToolExecutionResult.FINISH_REASON.equals(metadata.getFinishReason())
            && metadata.get(ToolExecutionResult.METADATA_TOOL_ID) instanceof String id) {
          endedTurn.add(
              new ToolResponse(
                  id,
                  metadata.get(ToolExecutionResult.METADATA_TOOL_NAME),
                  generation.getOutput().getText()));
        }
      }
    }

    /**
     * {@code history} without what preceded the turn: the system messages, counted here rather than
     * when the turn began because a subclass may add one — the tool search does where a request
     * carries none — and then the memory read in.
     */
    private List<Message> ofThisTurn(final List<Message> history) {
      var start = 0;
      while (start < history.size() && history.get(start) instanceof SystemMessage) {
        start++;
      }
      return history.subList(Math.min(start + remembered, history.size()), history.size());
    }

    synchronized void save(final boolean completed) {
      if (saved) {
        return;
      }
      saved = true;
      final var messages = messages();
      if (messages.isEmpty()) {
        return;
      }
      try {
        conversation.memory().add(conversation.id(), messages);
      } catch (RuntimeException e) {
        // The run has answered by now, or failed for its own reason; a write that fails is not a
        // reason to fail it again, and the next turn simply reads a conversation without this one.
        log.warn(
            "Could not save a {} turn of conversation {}",
            completed ? "finished" : "partial",
            conversation.id(),
            e);
      }
    }

    private List<Message> messages() {
      final var messages = new ArrayList<Message>(history == null ? opening : ofThisTurn(history));
      if (latest == null) {
        return messages;
      }
      if (!latest.hasToolCalls()) {
        messages.add(latest);
      } else if (!endedTurn.isEmpty()) {
        messages.add(latest);
        messages.add(ToolResponseMessage.builder().responses(List.copyOf(endedTurn)).build());
      }
      // Otherwise the round's calls never got their results — the run failed or was stopped while
      // they ran, or a tool-call limit ended it — and a call without results is left out.
      return messages;
    }
  }

  public static Builder<?> builder() {
    return new Builder<>();
  }

  public static class Builder<T extends Builder<T>> extends ToolCallingAdvisor.Builder<T> {

    protected Builder() {}

    @Override
    protected ToolCallingAdvisor.Builder<?> newCopy() {
      return new Builder<>();
    }

    @Override
    public MemoryToolCallingAdvisor build() {
      return new MemoryToolCallingAdvisor(
          getToolCallingManager(),
          getToolExecutionEligibilityChecker(),
          getAdvisorOrder(),
          isConversationHistoryEnabled());
    }
  }
}
