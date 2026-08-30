package me.kezhenxu94.springagent.appcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import org.junit.jupiter.api.Test;

class CliRunListenerTest {

  private final CliConsole console = mock(CliConsole.class);
  private final CliQuestionHandler questionHandler = mock(CliQuestionHandler.class);
  private final CliMessages messages = mock(CliMessages.class);
  private final CliRunListener listener = new CliRunListener(console, questionHandler, messages);

  @Test
  void attachesEverythingAChatRunNeeds() {
    final var registry = new AgentRunRegistry(request(BuiltInScenarios.CHAT));

    listener.onStart(registry);

    assertThat(responseListeners(registry)).hasSize(1).allMatch(CliRenderer.class::isInstance);
    assertThat(todoEventHandlers(registry)).hasSize(1);
    // The interceptor finds the renderer through the tool context and nowhere else, so a run
    // without this entry shows no tool calls at all.
    assertThat(toolContext(registry))
        .containsKey(CliRenderer.TOOL_CONTEXT_KEY.key())
        .extractingByKey(CliRenderer.TOOL_CONTEXT_KEY.key())
        .isInstanceOf(CliRenderer.class);
    assertThat(questionHandlers(registry)).containsExactly(questionHandler);
  }

  @Test
  void doesNotOfferToAskOnAScheduledTask() {
    final var registry = new AgentRunRegistry(request(BuiltInScenarios.SCHEDULED_TASK));

    listener.onStart(registry);

    // Registering a handler is what decides whether the agent gets the tool. A task fires whether
    // or not anyone is at the terminal, so it must not be offered one.
    assertThat(questionHandlers(registry)).isEmpty();
    // It still gets a renderer: if somebody is watching, they should see it happen.
    assertThat(responseListeners(registry)).hasSize(1);
  }

  private static AgentRequest request(final AgentScenario scenario) {
    return AgentRequest.builder()
        .requestId("run-1")
        .scenario(scenario)
        .userId("kez")
        .chatId("cli")
        .conversationId("cli-1")
        .userMessage(user -> user.text("hello"))
        .build();
  }

  /**
   * The registry's accessors are package-private to core, so a test outside that package reads the
   * fields directly. What is under test is this integration's wiring, not the registry.
   */
  @SuppressWarnings("unchecked")
  private static <T> T field(final AgentRunRegistry registry, final String name) {
    try {
      final Field field = AgentRunRegistry.class.getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(registry);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("AgentRunRegistry has no field " + name, e);
    }
  }

  private static List<Object> responseListeners(final AgentRunRegistry registry) {
    return field(registry, "responseListeners");
  }

  private static List<Object> todoEventHandlers(final AgentRunRegistry registry) {
    return field(registry, "todoEventHandlers");
  }

  private static List<Object> questionHandlers(final AgentRunRegistry registry) {
    return field(registry, "questionHandlers");
  }

  private static Map<String, Object> toolContext(final AgentRunRegistry registry) {
    return field(registry, "toolContext");
  }
}
