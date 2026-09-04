package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

@Slf4j
public class InterceptingToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final List<ToolCallInterceptor> interceptors;
  private final ToolInputFileRefs fileRefs;

  public InterceptingToolCallback(
      ToolCallback delegate, List<ToolCallInterceptor> interceptors, ToolInputFileRefs fileRefs) {
    this.delegate = delegate;
    this.interceptors = interceptors;
    this.fileRefs = fileRefs;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  /**
   * Delegated like the definition, and for a sharper reason: {@code ToolCallback} defaults this to
   * metadata with {@code returnDirect} off, and every callback a run carries passes through here.
   * Not forwarding it silently turned a tool that ends the turn into one that does not.
   */
  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return handle(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return handle(toolInput, toolContext);
  }

  /**
   * The chain, either side of the call.
   *
   * <p>A {@link ToolCallInterceptor.CallRefused} takes the place of the call rather than ending the
   * turn, and {@link #applyAfter} still runs: the interceptors that got as far as {@code
   * beforeCall} have already put something on a surface — a line on a card saying this call is out
   * — and only their {@code afterCall} takes it down again. The arguments handed on are the ones
   * the model wrote, since the transform that was in progress when the refusal came did not finish.
   */
  private String handle(final String toolInput, final ToolContext toolContext) {
    final String input;
    try {
      input = applyBefore(toolInput, toolContext);
    } catch (ToolCallInterceptor.CallRefused e) {
      log.info(
          "Tool '{}' was refused before it was called: {}",
          getToolDefinition().name(),
          e.getMessage());
      return applyAfter(toolInput, e.getMessage(), toolContext);
    }
    return applyAfter(input, invoke(input, toolContext), toolContext);
  }

  /**
   * The call itself, with the display description taken off the arguments and any {@code @file:}
   * reference among them resolved, on the way in.
   *
   * <p>Both transforms sit here rather than in a {@link ToolCallInterceptor} of their own, and the
   * input the chain goes on to see is the one before them. Both halves of that are deliberate. An
   * interceptor would leave the order it runs in deciding whether the CLI and the Feishu card
   * render a reference or a whole document — and, for {@link DisplayDescription}, whether they see
   * the sentence at all, which is the whole point of asking for it; and {@link #applyAfter} is
   * handed the arguments so that a surface can show what a call was given, which is the reference
   * the model actually wrote, not the payload it stood for.
   *
   * <p>The description comes off first. It is never a file reference, and leaving it in would only
   * give {@link ToolInputFileRefs} one more argument to rule on.
   *
   * <p>A reference that cannot be honoured answers the call rather than raising: the model asked
   * for something reasonable in a way that did not work, and the way to tell it so is the same way
   * it hears everything else about a tool call.
   */
  private String invoke(final String input, final ToolContext toolContext) {
    final var arguments = DisplayDescription.strip(input);
    final String expanded;
    try {
      expanded = fileRefs.expand(getToolDefinition().name(), arguments, toolContext);
    } catch (ToolInputFileRefs.UnresolvableReference e) {
      log.info(
          "Tool '{}' was called with a file reference that could not be resolved: {}",
          getToolDefinition().name(),
          e.getMessage());
      return e.getMessage();
    }
    return toolContext == null ? delegate.call(expanded) : delegate.call(expanded, toolContext);
  }

  private String applyBefore(String input, ToolContext ctx) {
    final var name = getToolDefinition().name();
    for (final var interceptor : interceptors) {
      input = interceptor.beforeCall(name, input, ctx);
    }
    return input;
  }

  private String applyAfter(String input, String result, ToolContext ctx) {
    final var name = getToolDefinition().name();
    for (final var interceptor : interceptors) {
      result = interceptor.afterCall(name, input, result, ctx);
    }
    return result;
  }
}
