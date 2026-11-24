package me.kezhenxu94.springagent.core.advisors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Tells the model that it has a memory and how to use it.
 *
 * <p>Forked from {@code org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor}, and narrower
 * than it: that one also merges the memory tools' callbacks into the request, whereas here the
 * tools are an ordinary bean ({@code core.memory.MemoryTools}, which says why) and all that is left
 * is the paragraph. It is still an advisor because a system message is the one thing a tool cannot
 * add to, and it still travels with the tools in the composition because it is false without them:
 * a run offered no tools must not be told it has a memory it can save to.
 *
 * <p>Which memories the run reaches is not here: those name the user's and the group's own
 * directories, and the system prompt has to be the same for every chat to be served from a prompt
 * cache. They travel in the turn context instead — see {@code MemoryScopesPromptVariables}.
 *
 * <p>Deliberately free of any type of this project's own, so that it could move to {@code
 * spring-ai-agent-utils} beside the advisor it was forked from — this fork is a candidate to be
 * contributed back. What it needs of this deployment, the prompt and the block, arrives through the
 * builder.
 */
public final class MemoryToolsAdvisor implements BaseAdvisor {

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

    // Not Prompt.augmentSystemMessage: it mutates the *first* SystemMessage it finds and stops
    // there, which was harmless while a run ever carried exactly one — see SpringAgentProperties.Ai
    // for how it can now carry several, one per configured system-prompt part. Appended to the
    // last instead, so this paragraph lands after whatever an operator put last regardless of how
    // many pieces came before it, and a request assembled without any system message still gets
    // one rather than an NPE from reading getSystemMessage().getText() the way upstream does.
    final var augmented = appendToLastSystemMessage(chatClientRequest.prompt(), memoryPrompt);
    return chatClientRequest.mutate().prompt(augmented).build();
  }

  /**
   * {@code text} appended to the last {@link SystemMessage} in {@code prompt}, or a new one at the
   * front where the prompt carries none. See {@link #before} for why this is not {@link
   * Prompt#augmentSystemMessage}.
   */
  private static Prompt appendToLastSystemMessage(final Prompt prompt, final String text) {
    final var messages = new ArrayList<>(prompt.getInstructions());
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof SystemMessage systemMessage) {
        messages.set(
            i,
            systemMessage
                .mutate()
                .text(
                    systemMessage.getText()
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + text)
                .build());
        return new Prompt(messages, prompt.getOptions());
      }
    }
    messages.add(0, SystemMessage.builder().text(text).build());
    return new Prompt(messages, prompt.getOptions());
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

    private Resource memorySystemPrompt;

    private Builder() {}

    public Builder order(final int order) {
      this.order = order;
      return this;
    }

    /** What is appended. */
    public Builder memorySystemPrompt(final Resource memorySystemPrompt) {
      Assert.notNull(memorySystemPrompt, "Memory system prompt must not be null");
      this.memorySystemPrompt = memorySystemPrompt;
      return this;
    }

    public MemoryToolsAdvisor build() {
      Assert.notNull(this.memorySystemPrompt, "Memory system prompt must not be null");
      // Read once, here, because before() runs on a Reactor worker.
      try {
        return new MemoryToolsAdvisor(
            this.order, this.memorySystemPrompt.getContentAsString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
