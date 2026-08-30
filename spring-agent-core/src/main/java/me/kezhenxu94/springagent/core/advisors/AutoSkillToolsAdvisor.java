package me.kezhenxu94.springagent.core.advisors;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

/**
 * Tells the model, once a turn has cost more tool calls than anyone would want to pay twice, to
 * offer the user a skill made out of what it just worked out.
 *
 * <p>Nothing here writes a skill, and nothing here asks the user anything. All it does is append a
 * paragraph to the system message; the offer is a sentence the model adds to the answer it was
 * already giving, and the skill is written on a later turn, with the tools the run already has,
 * only if the user says yes. That is the whole design: a run that was expensive is the only
 * evidence available that the same question will be expensive again, and the person who asked it is
 * the only one who knows whether they will ask it again.
 *
 * <p>The offer is prose rather than a question tool on purpose. On a surface whose questions are
 * answered later the ask ends the turn — see {@code AskUserQuestionTool} and the pending question
 * it persists — so a model that asked here would stop before saying what it had spent thirty tool
 * calls finding out. A sentence at the end of a reply ends nothing and reads the same everywhere.
 *
 * <p>Deliberately free of any type of this project's own, so that it can move to {@code
 * spring-ai-agent-utils} beside {@code AutoMemoryToolsAdvisor} unchanged. What it needs of this
 * deployment — which prompt, in which language, and how many calls are too many — arrives through
 * the builder.
 */
public final class AutoSkillToolsAdvisor implements BaseAdvisor {

  /**
   * How many tool calls a turn has to make before the model is asked to offer anything.
   *
   * <p>Twenty is well past what an ordinary answer costs and well short of what a run has to reach
   * before a person notices it is slow, which is the window where an offer is both warranted and
   * still welcome.
   */
  public static final int DEFAULT_TOOL_CALL_THRESHOLD = 20;

  /**
   * Inside the tool-calling loop, since {@link ToolCallingAdvisor} runs it and only advisors
   * ordered after it see an iteration at all. An advisor ordered before it — where the memory
   * advisor sits — is called once per turn, before that turn has made a single tool call, and so
   * could only ever react to the turn before. A complex question that is asked once would never be
   * offered anything.
   *
   * <p>Ahead of chat memory and knowledge retrieval, which this neither reads nor disturbs.
   */
  public static final int DEFAULT_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 50;

  private final int order;

  private final int toolCallThreshold;

  private final PromptTemplate skillPrompt;

  private AutoSkillToolsAdvisor(
      final int order, final int toolCallThreshold, final PromptTemplate skillPrompt) {
    this.order = order;
    this.toolCallThreshold = toolCallThreshold;
    this.skillPrompt = skillPrompt;
  }

  @Override
  public ChatClientRequest before(
      final ChatClientRequest chatClientRequest, final AdvisorChain advisorChain) {
    // The same guard the memory advisor uses: without tool calling options there are no tool calls
    // to count, and the run has nothing to write a skill with either.
    if (!(chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions)) {
      return chatClientRequest;
    }

    final var toolCalls = toolCallCount(chatClientRequest.prompt().getInstructions());
    if (toolCalls < toolCallThreshold) {
      // Nothing is appended below the threshold, which is where almost every turn stays. This is
      // the one place it differs from the memory advisor, and for a reason: memory's paragraph has
      // to be in front of the model before it decides whether something is worth remembering,
      // whereas this one is a reaction to work already done, so a cheap turn pays nothing for it.
      return chatClientRequest;
    }

    // Rendered here rather than at build() because the count is what makes the offer concrete, and
    // only the iterations that actually fire pay for it.
    final var reminder = skillPrompt.render(Map.of("TOOL_CALL_COUNT", toolCalls));
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
                                + reminder)
                        .build());

    return chatClientRequest.mutate().prompt(augmented).build();
  }

  @Override
  public ChatClientResponse after(
      final ChatClientResponse chatClientResponse, final AdvisorChain advisorChain) {
    // The offer is the model's own text, and any skill that follows is written by its own tool
    // calls on a later turn. There is nothing to do to a response.
    return chatClientResponse;
  }

  @Override
  public int getOrder() {
    return order;
  }

  /**
   * Every tool call the messages carry, which inside the loop is every tool call of this turn.
   *
   * <p>Stateless, and it can be, because the tool advisor is built with its conversation history on
   * — see {@code SpringAgent.toolCallingAdvisor()}, which sets it explicitly and says why. With
   * that history off the loop forwards only the system message and the last one, this count
   * collapses to roughly zero, and the offer silently stops being made rather than failing.
   */
  private static int toolCallCount(final List<Message> messages) {
    var count = 0;
    for (final var message : messages) {
      if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
        count += assistant.getToolCalls().size();
      }
    }
    return count;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private int order = DEFAULT_ORDER;

    private int toolCallThreshold = DEFAULT_TOOL_CALL_THRESHOLD;

    private Resource skillSystemPrompt;

    private Builder() {}

    public Builder order(final int order) {
      this.order = order;
      return this;
    }

    public Builder toolCallThreshold(final int toolCallThreshold) {
      Assert.isTrue(toolCallThreshold > 0, "Tool call threshold must be greater than zero");
      this.toolCallThreshold = toolCallThreshold;
      return this;
    }

    /**
     * What is appended once the threshold is passed, as a template over {@code TOOL_CALL_COUNT} —
     * how many tool calls the turn has made by then.
     */
    public Builder skillSystemPrompt(final Resource skillSystemPrompt) {
      Assert.notNull(skillSystemPrompt, "Skill system prompt must not be null");
      this.skillSystemPrompt = skillSystemPrompt;
      return this;
    }

    public AutoSkillToolsAdvisor build() {
      Assert.notNull(this.skillSystemPrompt, "Skill system prompt must not be null");
      // Read once, at assembly, so that the only work a firing iteration does is fill in a number.
      // Every iteration past the threshold renders, and they run on a Reactor worker.
      final var template = PromptTemplate.builder().resource(this.skillSystemPrompt).build();
      return new AutoSkillToolsAdvisor(this.order, this.toolCallThreshold, template);
    }
  }
}
