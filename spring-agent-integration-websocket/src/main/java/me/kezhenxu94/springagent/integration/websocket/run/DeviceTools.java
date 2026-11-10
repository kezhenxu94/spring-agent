package me.kezhenxu94.springagent.integration.websocket.run;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tools that run on the person's own device — a phone's location, its calendar, its camera — which
 * a native app declares with the message it sends and executes when the model calls one.
 *
 * <p>The device is never connected to the run; it only reads its journal. So a call goes out the
 * way everything else a run does goes out, as a {@link RunEvent#DEVICE_TOOL} event, and comes back
 * over HTTP in two steps:
 *
 * <ol>
 *   <li><b>Claim.</b> The first app to claim the call runs it, and any other is told 409. Two
 *       phones signed in as one person both read the event, and a phone replaying the journal after
 *       a reconnect reads it again; without a claim each would run the tool, and a tool that does
 *       something rather than reads something would do it twice.
 *   <li><b>Result.</b> What the device produced, or that it failed, handed to the model as the
 *       tool's output.
 * </ol>
 *
 * <p><b>Two waits, because they answer two different questions.</b> A minute for a claim, since an
 * app that is open claims within a second and one that is not will not claim at all — a short wait
 * is what lets the model tell the person to open the app rather than sit there. Five minutes for a
 * result, since a device tool may well be asking the person something (a permission prompt, a photo
 * to take). Every way a call ends is told to the model as a sentence it can relay, never as an
 * exception, and journalled as {@link RunEvent#DEVICE_TOOL_DONE}.
 *
 * <p><b>Blocking is the point, and it is on the run's own thread.</b> Spring AI calls a tool
 * synchronously, so the run is paused exactly as long as the device takes. Stopping the run cannot
 * reach a blocked tool call by itself — {@code SpringAgent} checks its cancel flag between chunks —
 * so whoever stops a run here also calls {@link #cancel}, which is what releases the wait.
 */
@Slf4j
@Component
public class DeviceTools {

  /**
   * A prefix of its own so a device tool cannot be named like anything this runtime offers, and a
   * length that keeps the whole name inside the 64 characters every provider accepts.
   */
  static final Pattern NAME = Pattern.compile("^device_[A-Za-z0-9_]{1,57}$");

  static final int MAX_TOOLS = 64;
  static final int MAX_DESCRIPTION = 2000;
  static final int MAX_SCHEMA_BYTES = 16 * 1024;

  /**
   * One tool as a native app declares it.
   *
   * @param inputSchema a JSON Schema object, passed to the model as the tool's parameters
   */
  public record Spec(String name, String description, Map<String, Object> inputSchema) {}

  /** What a claim or a result came to. */
  public enum Outcome {
    ACCEPTED,
    /** Already claimed, already answered, not claimed yet, or no such call in this run. */
    CONFLICT,
    /** Not the caller's run, or no such run — deliberately the same answer. */
    NOT_FOUND
  }

  private final RunJournals journals;
  private final WebMessages messages;
  private final JsonMapper om;
  private final Duration claimTimeout;
  private final Duration answerTimeout;

  private final ConcurrentMap<String, Call> calls = new ConcurrentHashMap<>();

  @Autowired
  public DeviceTools(final RunJournals journals, final WebMessages messages, final JsonMapper om) {
    this(journals, messages, om, Duration.ofSeconds(60), Duration.ofMinutes(5));
  }

  DeviceTools(
      final RunJournals journals,
      final WebMessages messages,
      final JsonMapper om,
      final Duration claimTimeout,
      final Duration answerTimeout) {
    this.journals = journals;
    this.messages = messages;
    this.om = om;
    this.claimTimeout = claimTimeout;
    this.answerTimeout = answerTimeout;
  }

  /**
   * The callbacks a run with {@code requestId} offers for {@code specs}.
   *
   * <p>Building them registers nothing: a call exists only once the model makes one. That is what
   * lets a message that ends up queued into a run already going simply drop them — the run it joins
   * was composed without them, and nothing was left behind for anybody to clean up.
   *
   * @param locale what the sentences the model reads on a timeout are written in — the sender's,
   *     resolved now, since the tool runs later on a thread with no request to resolve it from
   * @throws IllegalArgumentException with a sentence for the sender, where a spec is not one this
   *     server will hand a model
   */
  public List<ToolCallback> callbacks(
      final String requestId, final String userId, final List<Spec> specs, final Locale locale) {
    if (specs == null || specs.isEmpty()) {
      return List.of();
    }
    if (specs.size() > MAX_TOOLS) {
      throw new IllegalArgumentException(messages.get(locale, "device-tools-too-many", MAX_TOOLS));
    }
    final var names = new HashSet<String>();
    final var out = new ArrayList<ToolCallback>();
    for (final var spec : specs) {
      final var name = spec == null || spec.name() == null ? "" : spec.name();
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(messages.get(locale, "device-tools-bad-name", name));
      }
      if (!names.add(name)) {
        throw new IllegalArgumentException(messages.get(locale, "device-tools-duplicate", name));
      }
      final var description = spec.description();
      // Required as well as bounded: Spring AI refuses a definition with no description, and the
      // model has nothing else to decide by whether this is the tool it wants.
      if (description == null || description.isBlank() || description.length() > MAX_DESCRIPTION) {
        throw new IllegalArgumentException(
            messages.get(locale, "device-tools-bad-description", name, MAX_DESCRIPTION));
      }
      final var schema =
          spec.inputSchema() == null ? null : om.writeValueAsString(spec.inputSchema());
      if (schema == null || schema.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
        throw new IllegalArgumentException(
            messages.get(locale, "device-tools-bad-schema", name, MAX_SCHEMA_BYTES));
      }
      final var definition =
          ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
      out.add(new DeviceToolCallback(definition, requestId, userId, locale));
    }
    return out;
  }

  /** Claims a call for the device asking. The first claim wins and every later one conflicts. */
  public Outcome claim(final String requestId, final String callId, final String userId) {
    final var call = find(requestId, callId, userId);
    if (call == null) {
      return ownedRun(requestId, userId) ? Outcome.CONFLICT : Outcome.NOT_FOUND;
    }
    return call.claim() ? Outcome.ACCEPTED : Outcome.CONFLICT;
  }

  /** Answers a call the asking device claimed. */
  public Outcome answer(
      final String requestId,
      final String callId,
      final String userId,
      final String output,
      final boolean error) {
    final var call = find(requestId, callId, userId);
    if (call == null) {
      return ownedRun(requestId, userId) ? Outcome.CONFLICT : Outcome.NOT_FOUND;
    }
    return call.answer(output == null ? "" : output, error) ? Outcome.ACCEPTED : Outcome.CONFLICT;
  }

  /** Releases every call {@code requestId} is waiting on, for a run that has been stopped. */
  public void cancel(final String requestId) {
    calls.values().stream()
        .filter(it -> it.requestId.equals(requestId))
        .forEach(it -> it.resolve(new Result(DONE_CANCELLED, null)));
  }

  private Call find(final String requestId, final String callId, final String userId) {
    final var call = callId == null ? null : calls.get(callId);
    if (call == null || !call.requestId.equals(requestId) || !call.userId.equals(userId)) {
      return null;
    }
    return call;
  }

  /**
   * Whether the run is the caller's, for a call id it does not hold: a call that has already ended,
   * which an app replaying the journal will ask about, is a conflict in a run the caller owns and
   * the same not-found as anything else in a run they do not.
   */
  private boolean ownedRun(final String requestId, final String userId) {
    return journals.byRequestId(requestId).filter(it -> userId.equals(it.userId())).isPresent();
  }

  static final String DONE_ANSWERED = "answered";
  static final String DONE_ERROR = "error";
  static final String DONE_TIMEOUT = "timeout";
  static final String DONE_CANCELLED = "cancelled";

  /**
   * @param outcome one of the {@code DONE_*} values
   * @param output what the device said, null where it said nothing
   */
  private record Result(String outcome, String output) {}

  /** One call in flight. Its two transitions are synchronized so a claim cannot race a timeout. */
  private static final class Call {
    final String requestId;
    final String userId;
    final CompletableFuture<Void> claimed = new CompletableFuture<>();
    final CompletableFuture<Result> result = new CompletableFuture<>();

    Call(final String requestId, final String userId) {
      this.requestId = requestId;
      this.userId = userId;
    }

    synchronized boolean claim() {
      if (claimed.isDone() || result.isDone()) {
        return false;
      }
      claimed.complete(null);
      return true;
    }

    synchronized boolean answer(final String output, final boolean error) {
      if (!claimed.isDone() || result.isDone()) {
        return false;
      }
      return result.complete(new Result(error ? DONE_ERROR : DONE_ANSWERED, output));
    }

    /** Ends the call unless something already has. */
    synchronized void resolve(final Result ending) {
      result.complete(ending);
    }

    /** Ends an unclaimed call as timed out, and says whether it was in fact still unclaimed. */
    synchronized boolean expireUnclaimed() {
      if (claimed.isDone()) {
        return false;
      }
      result.complete(new Result(DONE_TIMEOUT, null));
      return true;
    }
  }

  private final class DeviceToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final String requestId;
    private final String userId;
    private final Locale locale;

    DeviceToolCallback(
        final ToolDefinition definition,
        final String requestId,
        final String userId,
        final Locale locale) {
      this.definition = definition;
      this.requestId = requestId;
      this.userId = userId;
      this.locale = locale;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(final String toolInput, final ToolContext toolContext) {
      return call(toolInput);
    }

    @Override
    public String call(final String toolInput) {
      final var name = definition.name();
      final var journal = journals.byRequestId(requestId).orElse(null);
      if (journal == null) {
        // No journal is no way for a device to hear of the call. Not expected — the run that was
        // given these tools is one this module journals — but the model is owed a sentence.
        return messages.get(locale, "device-tool-unclaimed", name);
      }
      final var callId = UUID.randomUUID().toString();
      final var call = new Call(requestId, userId);
      calls.put(callId, call);
      try {
        journal.append(
            RunEvent.of(
                RunEvent.DEVICE_TOOL,
                Map.of(
                    "callId",
                    callId,
                    "name",
                    name,
                    "input",
                    toolInput == null ? "{}" : toolInput)));
        final var result = await(call);
        journal.append(
            RunEvent.of(
                RunEvent.DEVICE_TOOL_DONE, Map.of("callId", callId, "outcome", result.outcome())));
        log.info("Device tool {} ({}) in run {}: {}", name, callId, requestId, result.outcome());
        return switch (result.outcome()) {
          case DONE_ANSWERED -> result.output();
          case DONE_ERROR -> messages.get(locale, "device-tool-failed", name, result.output());
          case DONE_CANCELLED -> messages.get(locale, "device-tool-cancelled", name);
          default ->
              messages.get(
                  locale,
                  result.output() == null && !call.claimed.isDone()
                      ? "device-tool-unclaimed"
                      : "device-tool-unanswered",
                  name);
        };
      } finally {
        calls.remove(callId);
      }
    }

    private Result await(final Call call) {
      try {
        try {
          CompletableFuture.anyOf(call.claimed, call.result)
              .get(claimTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
          call.expireUnclaimed();
        }
        try {
          return call.result.get(answerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
          call.resolve(new Result(DONE_TIMEOUT, null));
          return call.result.join();
        }
      } catch (final InterruptedException e) {
        // The run's thread being interrupted is the run being torn down; the call ends with it.
        Thread.currentThread().interrupt();
        call.resolve(new Result(DONE_CANCELLED, null));
        return call.result.join();
      } catch (final ExecutionException e) {
        // Nothing completes either future exceptionally; this is here for the compiler.
        throw new IllegalStateException(e.getCause());
      }
    }
  }
}
