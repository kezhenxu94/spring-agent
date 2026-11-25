package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.support.ResourceBundleMessageSource;
import reactor.core.publisher.Flux;

class UserModelProbeTest {

  private final SpringAgentProperties properties = properties();

  private static SpringAgentProperties properties() {
    final var ai = mock(SpringAgentProperties.Ai.class);
    when(ai.systemPromptParts()).thenReturn(List.of());
    when(ai.systemPrompt()).thenReturn("You are a test.");
    final var properties = mock(SpringAgentProperties.class);
    when(properties.ai()).thenReturn(ai);
    when(properties.locale()).thenReturn(Locale.ENGLISH);
    return properties;
  }

  @Test
  @DisplayName("an endpoint that only serves streaming requests passes the probe")
  void streamingOnlyEndpointPasses() {
    assertThat(probeAgainst(new StreamingOnlyModel(Flux.just(answer("Hello"))))).isNull();
  }

  @Test
  @DisplayName("a streaming failure is reported in the endpoint's own words")
  void streamingFailureIsReported() {
    final var refused =
        new StreamingOnlyModel(Flux.error(new IllegalStateException("401: invalid token")));

    assertThat(probeAgainst(refused)).isEqualTo("401: invalid token");
  }

  @Test
  @DisplayName(
      "switching to a built-in model that refuses the temperature stores that it is sent none, and"
          + " says so")
  void builtinThatRefusesTheTemperature() {
    final var builtin =
        me.kezhenxu94.springagent.core.dao.models.UserModelConfig.builder()
            .ownerId("u1")
            .name(UserModelRegistry.BUILTIN_PREFIX + "listed-model")
            .model("listed-model")
            .activated(true)
            .build();
    final var registry = mock(UserModelRegistry.class);
    when(registry.active("u1")).thenReturn(java.util.Optional.of(builtin));
    when(registry.setTemperature("u1", builtin.name(), Temperatures.NOT_SENT)).thenReturn(true);
    final var clients = mock(UserChatClients.class);
    final var refusing =
        ChatClient.builder(
                new StreamingOnlyModel(
                    Flux.error(
                        new IllegalStateException(
                            "400 invalid_request_error: \"temperature\" is deprecated for this"
                                + " model"))))
            .build();
    final var answering =
        ChatClient.builder(new StreamingOnlyModel(Flux.just(answer("Hello")))).build();
    when(clients.probeClient(any(), any(), any()))
        .thenAnswer(
            call ->
                Temperatures.omitted(
                        ((me.kezhenxu94.springagent.core.dao.models.UserModelConfig)
                                call.getArgument(0))
                            .temperature())
                    ? answering
                    : refusing);

    final var said = probeWith(clients).builtinSwitched(registry, "u1");

    assertThat(said).contains("listed-model").contains("does not take a temperature");
    org.mockito.Mockito.verify(registry)
        .setTemperature("u1", builtin.name(), Temperatures.NOT_SENT);
  }

  @Test
  @DisplayName("a built-in model that answers is switched to without a word")
  void builtinThatAnswers() {
    final var builtin =
        me.kezhenxu94.springagent.core.dao.models.UserModelConfig.builder()
            .ownerId("u1")
            .name(UserModelRegistry.BUILTIN_PREFIX + "listed-model")
            .model("listed-model")
            .activated(true)
            .build();
    final var registry = mock(UserModelRegistry.class);
    when(registry.active("u1")).thenReturn(java.util.Optional.of(builtin));
    final var clients = mock(UserChatClients.class);
    when(clients.probeClient(any(), any(), any()))
        .thenReturn(ChatClient.builder(new StreamingOnlyModel(Flux.just(answer("Hi")))).build());

    assertThat(probeWith(clients).builtinSwitched(registry, "u1")).isNull();
    org.mockito.Mockito.verify(registry, org.mockito.Mockito.never())
        .setTemperature(any(), any(), any());
  }

  private UserModelProbe probeWith(final UserChatClients clients) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new UserModelProbe(
        clients, new CoreMessages(source, properties), Duration.ofSeconds(10), properties);
  }

  private String probeAgainst(final ChatModel model) {
    final var clients = mock(UserChatClients.class);
    when(clients.probeClient(any(), any(), any())).thenReturn(ChatClient.builder(model).build());
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    final var probe =
        new UserModelProbe(
            clients, new CoreMessages(source, properties), Duration.ofSeconds(10), properties);
    return probe.check(
        "openai-responses",
        "https://example.test/v1",
        "example-model",
        "token",
        null,
        null,
        false,
        null);
  }

  private static ChatResponse answer(final String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /** Answers only when asked to stream, like a gateway that requires {@code stream: true}. */
  private record StreamingOnlyModel(Flux<ChatResponse> streamed) implements ChatModel {

    @Override
    public ChatResponse call(final Prompt prompt) {
      throw new IllegalStateException("400: {\"detail\":\"Stream must be set to true\"}");
    }

    @Override
    public Flux<ChatResponse> stream(final Prompt prompt) {
      return streamed;
    }
  }
}
