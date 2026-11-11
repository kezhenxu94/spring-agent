package me.kezhenxu94.springagent.integration.websocket.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

/** The round trip a device tool call makes, and every way it can end. */
class DeviceToolsTest {

  private static final String RUN = "run-1";
  private static final String ME = "ou_me";

  private final WebProperties properties =
      new WebProperties(
          null, null, null, Locale.ENGLISH, null, null, null, null, false, null, null);
  private final RunJournals journals = new RunJournals(properties, List.of());
  private final WebMessages messages = new WebMessages(properties);
  private final LinkedBlockingQueue<RunEvent> events = new LinkedBlockingQueue<>();
  private final List<PendingDeviceCall> saved = new CopyOnWriteArrayList<>();
  private final PendingDeviceCallRepo pending = mock(PendingDeviceCallRepo.class);

  {
    when(pending.save(any()))
        .thenAnswer(
            i -> {
              saved.add(i.getArgument(0));
              return i.getArgument(0);
            });
  }

  private DeviceTools tools(final Duration claim, final Duration answer) {
    final var journal = journals.open(RUN, "conv-1", ME);
    journal.attach(
        new RunJournal.Reader() {
          @Override
          public void onEvent(final RunEvent event) {
            events.add(event);
          }

          @Override
          public void onClosed() {}
        },
        0);
    return new DeviceTools(
        journals,
        messages,
        JsonMapper.builder().build(),
        pending,
        claim,
        answer,
        Duration.ofHours(24));
  }

  private static List<DeviceTools.Spec> location() {
    return List.of(
        new DeviceTools.Spec(
            "device_location",
            "Where the phone is.",
            Map.of("type", "object", "properties", Map.of())));
  }

  private ToolCallback callback(final DeviceTools tools) {
    return tools.callbacks(RUN, ME, location(), Locale.ENGLISH).getFirst();
  }

  private RunEvent next(final String type) throws InterruptedException {
    final var event = events.poll(5, TimeUnit.SECONDS);
    assertThat(event).as("a %s event", type).isNotNull();
    assertThat(event.type()).isEqualTo(type);
    return event;
  }

  @Test
  @DisplayName("the first claim runs it, a second conflicts, and the result reaches the model")
  void roundTrip() throws Exception {
    final var tools = tools(Duration.ofSeconds(5), Duration.ofSeconds(5));
    final var callback = callback(tools);
    final var returned = CompletableFuture.supplyAsync(() -> callback.call("{\"precise\":true}"));

    final var asked = next(RunEvent.DEVICE_TOOL);
    final var callId = (String) asked.data().get("callId");
    assertThat(asked.data())
        .containsEntry("name", "device_location")
        .containsEntry("input", "{\"precise\":true}");

    // Not claimed yet, so there is nothing to answer.
    assertThat(tools.answer(RUN, callId, ME, "early", false))
        .isEqualTo(DeviceTools.Outcome.CONFLICT);
    // Somebody else's run is not found, whatever the call id.
    assertThat(tools.claim(RUN, callId, "ou_other")).isEqualTo(DeviceTools.Outcome.NOT_FOUND);
    assertThat(tools.claim(RUN, callId, ME)).isEqualTo(DeviceTools.Outcome.ACCEPTED);
    assertThat(tools.claim(RUN, callId, ME)).isEqualTo(DeviceTools.Outcome.CONFLICT);
    assertThat(tools.answer(RUN, callId, ME, "{\"lat\":1}", false))
        .isEqualTo(DeviceTools.Outcome.ACCEPTED);
    assertThat(tools.answer(RUN, callId, ME, "again", false))
        .isEqualTo(DeviceTools.Outcome.CONFLICT);

    assertThat(returned.get(5, TimeUnit.SECONDS)).isEqualTo("{\"lat\":1}");
    assertThat(next(RunEvent.DEVICE_TOOL_DONE).data())
        .containsEntry("callId", callId)
        .containsEntry("outcome", "answered");
    // Over, so a replaying device is told it conflicts rather than that the run is not theirs.
    assertThat(tools.claim(RUN, callId, ME)).isEqualTo(DeviceTools.Outcome.CONFLICT);
  }

  @Test
  @DisplayName("an error from the device is told to the model as a failure")
  void deviceError() throws Exception {
    final var tools = tools(Duration.ofSeconds(5), Duration.ofSeconds(5));
    final var callback = callback(tools);
    final var returned = CompletableFuture.supplyAsync(() -> callback.call("{}"));
    final var callId = (String) next(RunEvent.DEVICE_TOOL).data().get("callId");
    tools.claim(RUN, callId, ME);
    tools.answer(RUN, callId, ME, "permission denied", true);
    assertThat(returned.get(5, TimeUnit.SECONDS))
        .contains("device_location")
        .contains("permission denied");
    assertThat(next(RunEvent.DEVICE_TOOL_DONE).data()).containsEntry("outcome", "error");
  }

  @Test
  @DisplayName("nobody claiming it in time makes the call pending, and the model is told to stop")
  void unclaimedBecomesPending() throws Exception {
    final var tools = tools(Duration.ofMillis(100), Duration.ofSeconds(5));
    final var output =
        callback(tools)
            .call(
                "{\"precise\":true}",
                new ToolContext(Map.of("chatId", "conv-1", "rootMessageId", "root-1")));
    assertThat(output).contains("device_location").contains("End your turn");

    final var callId = (String) next(RunEvent.DEVICE_TOOL).data().get("callId");
    assertThat(next(RunEvent.DEVICE_TOOL_DONE).data())
        .containsEntry("callId", callId)
        .containsEntry("outcome", "pending");
    assertThat(next(RunEvent.DEVICE_TOOL_PENDING).data())
        .containsEntry("callId", callId)
        .containsEntry("name", "device_location")
        .containsEntry("input", "{\"precise\":true}");

    assertThat(saved)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(callId);
              assertThat(row.userId()).isEqualTo(ME);
              assertThat(row.conversationId()).isEqualTo("conv-1");
              assertThat(row.chatId()).isEqualTo("conv-1");
              assertThat(row.rootMessageId()).isEqualTo("root-1");
              assertThat(row.toolName()).isEqualTo("device_location");
              assertThat(row.input()).isEqualTo("{\"precise\":true}");
              assertThat(row.status()).isEqualTo(PendingDeviceCall.Status.PENDING);
              assertThat(Duration.between(row.createdAt(), row.expiresAt()))
                  .isEqualTo(Duration.ofHours(24));
            });
    // The live call is released: a late claim on it is a conflict, not a second run of the tool.
    assertThat(tools.claim(RUN, callId, ME)).isEqualTo(DeviceTools.Outcome.CONFLICT);
  }

  @Test
  @DisplayName("a claimed call nobody answers in time becomes pending too")
  void unansweredBecomesPending() throws Exception {
    final var tools = tools(Duration.ofSeconds(5), Duration.ofMillis(200));
    final var callback = callback(tools);
    final var returned = CompletableFuture.supplyAsync(() -> callback.call("{}"));
    final var callId = (String) next(RunEvent.DEVICE_TOOL).data().get("callId");
    tools.claim(RUN, callId, ME);
    assertThat(returned.get(5, TimeUnit.SECONDS)).contains("End your turn");
    assertThat(next(RunEvent.DEVICE_TOOL_DONE).data()).containsEntry("outcome", "pending");
    next(RunEvent.DEVICE_TOOL_PENDING);
    // Without a tool context the conversation stands in for the chat and the root message.
    assertThat(saved).singleElement().extracting(PendingDeviceCall::chatId).isEqualTo("conv-1");
  }

  @Test
  @DisplayName("where the call cannot be kept, it ends as timed out, with the old sentence")
  void unsavableTimesOut() throws Exception {
    when(pending.save(any())).thenThrow(new IllegalStateException("store down"));
    final var tools = tools(Duration.ofMillis(100), Duration.ofSeconds(5));
    assertThat(callback(tools).call("{}")).contains("did not pick up");
    next(RunEvent.DEVICE_TOOL);
    assertThat(next(RunEvent.DEVICE_TOOL_DONE).data()).containsEntry("outcome", "timeout");
    assertThat(events.poll(200, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  @DisplayName("stopping the run releases the wait")
  void cancelReleases() throws Exception {
    final var tools = tools(Duration.ofMinutes(1), Duration.ofMinutes(1));
    final var callback = callback(tools);
    final var returned = CompletableFuture.supplyAsync(() -> callback.call("{}"));
    next(RunEvent.DEVICE_TOOL);
    tools.cancel(RUN);
    assertThat(returned.get(5, TimeUnit.SECONDS)).contains("stopped");
    assertThat(next(RunEvent.DEVICE_TOOL_DONE).data()).containsEntry("outcome", "cancelled");
    assertThat(saved).isEmpty();
  }

  @Test
  @DisplayName("a declaration this server will not hand a model is refused")
  void validation() {
    final var tools = tools(Duration.ofSeconds(1), Duration.ofSeconds(1));
    final var schema = Map.<String, Object>of("type", "object");
    assertThatThrownBy(
            () ->
                tools.callbacks(
                    RUN,
                    ME,
                    List.of(new DeviceTools.Spec("location", "x", schema)),
                    Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("device_");
    assertThatThrownBy(
            () ->
                tools.callbacks(
                    RUN,
                    ME,
                    List.of(new DeviceTools.Spec("device_" + "x".repeat(58), "x", schema)),
                    Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tools.callbacks(
                    RUN,
                    ME,
                    List.of(
                        new DeviceTools.Spec("device_a", "x", schema),
                        new DeviceTools.Spec("device_a", "y", schema)),
                    Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("twice");
    assertThatThrownBy(
            () ->
                tools.callbacks(
                    RUN,
                    ME,
                    List.of(new DeviceTools.Spec("device_a", "x".repeat(2001), schema)),
                    Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tools.callbacks(
                    RUN,
                    ME,
                    List.of(
                        new DeviceTools.Spec(
                            "device_a", "x", Map.of("description", "x".repeat(17 * 1024)))),
                    Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tools.callbacks(
                    RUN,
                    ME,
                    java.util.stream.IntStream.range(0, 65)
                        .mapToObj(i -> new DeviceTools.Spec("device_" + i, "x", schema))
                        .toList(),
                    Locale.ENGLISH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            tools.callbacks(
                RUN,
                ME,
                List.of(new DeviceTools.Spec("device_" + "x".repeat(57), "x", schema)),
                Locale.ENGLISH))
        .hasSize(1);
  }
}
