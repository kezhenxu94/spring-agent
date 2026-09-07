package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.logging.RunMdc;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class InterceptingToolCallback implements ToolCallback {

  /** Arguments the model wrote, not a document: no application configuration reaches this. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ToolCallback delegate;
  private final List<ToolCallInterceptor> interceptors;
  private final ToolInputFileRefs fileRefs;
  private final CoreMessages messages;

  public InterceptingToolCallback(
      ToolCallback delegate,
      List<ToolCallInterceptor> interceptors,
      ToolInputFileRefs fileRefs,
      CoreMessages messages) {
    this.delegate = delegate;
    this.interceptors = interceptors;
    this.fileRefs = fileRefs;
    this.messages = messages;
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
   * Names the run this call belongs to before the chain runs, since every tool call in a run passes
   * through here.
   *
   * <p>Usually redundant, and deliberately kept: a call made on the run's own thread already
   * carries the run's MDC, but a tool called from anywhere else — a callback resolved outside the
   * run's chain, a manager invoking it directly — has only the tool context to say whose call this
   * is. What it does not reach is work a tool hands to an executor of its own; that thread is the
   * tool's to name.
   */
  private String handle(final String toolInput, final ToolContext toolContext) {
    try (var ignored = RunMdc.of(toolContext)) {
      return intercepted(toolInput, toolContext);
    }
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
  private String intercepted(final String toolInput, final ToolContext toolContext) {
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
   * it hears everything else about a tool call. Arguments that will not parse are answered the same
   * way, and for the same reason.
   */
  private String invoke(final String input, final ToolContext toolContext) {
    final var unreadable = unreadable(input);
    if (unreadable != null) {
      log.warn(
          "Tool '{}' was called with arguments that are not JSON, so the call was answered rather"
              + " than made: {} ({} characters were written)",
          getToolDefinition().name(),
          unreadable,
          input.length());
      log.debug("Arguments of the answered call to '{}': {}", getToolDefinition().name(), input);
      return messages.get("tool-arguments-malformed", getToolDefinition().name(), unreadable);
    }
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

  /**
   * Why the arguments cannot be read, or null where they can be — or were never JSON to begin with.
   *
   * <p>The case this is here for is a call the model did not finish writing: a provider that runs
   * out of output tokens mid-call still delivers the arguments it had got to, and they end wherever
   * the cut fell — inside a string, after a comma, anywhere. Nothing downstream reads that as the
   * accident it is. {@link DisplayDescription#strip} and {@link ToolInputFileRefs#expand} both pass
   * unparseable input through untouched, on the reasoning that input they cannot read is input they
   * have no business rewriting, and the tool then fails on it: a {@code MethodToolCallback} raises
   * with Jackson's own words, which name a {@code LinkedHashMap} and a Java type and say nothing
   * about writing the call again, and an MCP tool sends the broken payload to the server. So the
   * call is answered here instead, with a sentence saying what to do about it.
   *
   * <p><b>Only a payload that set out to be a JSON object is judged.</b> Everything else is left
   * exactly as it was, which keeps this from deciding that some callback whose input is not JSON is
   * being called wrongly — the same stance the two transforms in {@link #invoke} take. Every tool
   * schema the model is offered is an object schema, so in practice that is every real call.
   *
   * <p>It costs a parse per tool call, on top of the one the callback goes on to do. That is
   * microseconds against a model round trip, and it buys the one place where the arguments are
   * known to be readable before anything is done with them.
   */
  private static String unreadable(final String toolInput) {
    if (toolInput == null || !toolInput.stripLeading().startsWith("{")) {
      return null;
    }
    try {
      MAPPER.readTree(toolInput);
      return null;
    } catch (JacksonException e) {
      // The message without Jackson's source-and-offset tail, which is redacted anyway and reads
      // to the model as noise about a stream it knows nothing about.
      return e.getOriginalMessage();
    }
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
