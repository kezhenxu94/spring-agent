package me.kezhenxu94.springagent.core.advisors;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Tells the model that it has a memory, which memories this run reaches, and which of them it may
 * write to.
 *
 * <p>Forked from {@code org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor}, and narrower
 * than it: that one also merges the memory tools' callbacks into the request, whereas here the
 * tools are an ordinary bean ({@code core.memory.MemoryTools}, which says why) and all that is left
 * is the paragraph. It is still an advisor because a system message is the one thing a tool cannot
 * add to, and it still travels with the tools in the composition because it is false without them:
 * a run offered no tools must not be told it has a memory it can save to.
 *
 * <p>What it appends is a rendered block rather than a path, {@link #SCOPES_VARIABLE} — one line
 * per scope, naming the word that addresses it, where it is, who else reads it and whether this run
 * may write there. One variable rather than one per scope because the number of scopes varies per
 * request while the prompt is rendered once: a slot per scope would put a blank into whatever prose
 * surrounded it, and a one-to-one chat would read a sentence about a group that does not exist.
 *
 * <p>Deliberately free of any type of this project's own, so that it could move to {@code
 * spring-ai-agent-utils} beside the advisor it was forked from — this fork is a candidate to be
 * contributed back. What it needs of this deployment, the prompt and the block, arrives through the
 * builder.
 */
public final class MemoryToolsAdvisor implements BaseAdvisor {

  /** The one placeholder the memory prompt is rendered with. */
  public static final String SCOPES_VARIABLE = "MEMORY_SCOPES";

  /**
   * Before {@link ToolCallingAdvisor}, so this is called once per turn rather than once per
   * iteration of the tool-calling loop.
   *
   * <p>Which is the whole reason for the number. An advisor ordered after the tool advisor is
   * re-entered on every iteration, and this one appends to the system message, so a turn that made
   * twenty tool calls would carry twenty copies of the same paragraph. {@link
   * AutoSkillToolsAdvisor#DEFAULT_ORDER} chooses the opposite side of the same line for the
   * opposite reason: it has to count the calls a turn has already made, which an advisor before the
   * loop cannot see.
   */
  public static final int DEFAULT_ORDER = BaseAdvisor.HIGHEST_PRECEDENCE + 200;

  private final int order;

  private final String memoryPrompt;

  private MemoryToolsAdvisor(final int order, final String memoryPrompt) {
    this.order = order;
    this.memoryPrompt = memoryPrompt;
  }

  @Override
  public ChatClientRequest before(
      final ChatClientRequest chatClientRequest, final AdvisorChain advisorChain) {
    // Without tool calling options the run has no memory tools, so the paragraph would describe
    // tools the model cannot call — the same guard AutoSkillToolsAdvisor opens with.
    if (!(chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions)) {
      return chatClientRequest;
    }

    // Through the mutating form, not by reading getSystemMessage().getText() as upstream does: a
    // request assembled without a system message has none to read, and the NPE would surface from
    // inside a Reactor operator with nothing in the trace pointing here.
    final var augmented =
        chatClientRequest
            .prompt()
            .augmentSystemMessage(
                systemMessage ->
                    systemMessage
                        .mutate()
                        .text(
                            systemMessage.getText()
                                + System.lineSeparator()
                                + System.lineSeparator()
                                + memoryPrompt)
                        .build());

    return chatClientRequest.mutate().prompt(augmented).build();
  }

  @Override
  public ChatClientResponse after(
      final ChatClientResponse chatClientResponse, final AdvisorChain advisorChain) {
    // Memory is written by the model's own tool calls during the turn. There is nothing to do to a
    // response, which is also why this is a BaseAdvisor and not the BaseChatMemoryAdvisor upstream
    // declared: it never touched chat memory.
    return chatClientResponse;
  }

  @Override
  public int getOrder() {
    return order;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private int order = DEFAULT_ORDER;

    private String memoryScopes;

    private Resource memorySystemPrompt;

    private Builder() {}

    public Builder order(final int order) {
      this.order = order;
      return this;
    }

    /**
     * The block describing this request's memories, filling {@link #SCOPES_VARIABLE}.
     *
     * <p>A rendered string rather than the scopes themselves, so that this class stays free of this
     * project's types and so that whoever assembles it decides its language.
     */
    public Builder memoryScopes(final String memoryScopes) {
      Assert.hasText(memoryScopes, "Memory scopes block must not be empty");
      this.memoryScopes = memoryScopes;
      return this;
    }

    /** What is appended, as a template over {@link #SCOPES_VARIABLE}. */
    public Builder memorySystemPrompt(final Resource memorySystemPrompt) {
      Assert.notNull(memorySystemPrompt, "Memory system prompt must not be null");
      this.memorySystemPrompt = memorySystemPrompt;
      return this;
    }

    public MemoryToolsAdvisor build() {
      Assert.notNull(this.memorySystemPrompt, "Memory system prompt must not be null");
      Assert.hasText(this.memoryScopes, "Memory scopes block must not be empty");
      // Rendered once, here, because the block is fixed for the request this advisor belongs to and
      // before() runs on a Reactor worker.
      final var rendered =
          PromptTemplate.builder()
              .resource(this.memorySystemPrompt)
              .variables(Map.of(SCOPES_VARIABLE, this.memoryScopes))
              .build()
              .render();
      return new MemoryToolsAdvisor(this.order, rendered);
    }
  }
}
