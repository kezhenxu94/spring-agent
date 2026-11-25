package me.kezhenxu94.springagent.core.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import me.kezhenxu94.springagent.core.usermodels.Temperatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** A model refusing the temperature parameter itself is asked once more without one. */
class TemperatureRetryAdvisorTest {

  private static final String REFUSED =
      "400 invalid_request_error: \"temperature\" is deprecated for this model";

  /** A model that answers, or fails, by what each request it is sent carries. */
  private static final class Model implements ChatModel {
    private final Function<Double, RuntimeException> failure;
    private final List<Double> temperatures = new ArrayList<>();

    private Model(final Function<Double, RuntimeException> failure) {
      this.failure = failure;
    }

    @Override
    public ChatResponse call(final Prompt prompt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Flux<ChatResponse> stream(final Prompt prompt) {
      final var temperature = prompt.getOptions().getTemperature();
      temperatures.add(temperature);
      final var refused = failure.apply(temperature);
      return refused != null
          ? Flux.error(refused)
          : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
    }
  }

  private static String ask(final Model model) {
    return ChatClient.builder(model)
        .defaultOptions(ChatOptions.builder().temperature(0.0))
        .defaultAdvisors(new TemperatureRetryAdvisor())
        .build()
        .prompt()
        .user("hi")
        .stream()
        .content()
        .blockLast();
  }

  @Test
  @DisplayName("refused over its temperature, the call is made once more without one, and answers")
  void retriesWithoutTheTemperature() {
    final var model =
        new Model(temperature -> temperature == null ? null : new RuntimeException(REFUSED));

    assertThat(ask(model)).isEqualTo("ok");
    assertThat(model.temperatures).containsExactly(0.0, null);
  }

  @Test
  @DisplayName("refused again, it does not ask a third time")
  void retriesOnce() {
    final var model = new Model(temperature -> new RuntimeException(REFUSED));

    assertThatThrownBy(() -> ask(model)).hasMessageContaining("temperature");
    assertThat(model.temperatures).containsExactly(0.0, null);
  }

  @Test
  @DisplayName("any other refusal stands as it is")
  void otherRefusalsStand() {
    final var model =
        new Model(temperature -> new RuntimeException("400 invalid_request_error: bad tools"));

    assertThatThrownBy(() -> ask(model)).hasMessageContaining("bad tools");
    assertThat(model.temperatures).containsExactly(0.0);
  }

  @Test
  @DisplayName("what counts as the parameter being refused, and what does not")
  void whatIsARefusal() {
    assertThat(Temperatures.rejected(REFUSED)).isTrue();
    assertThat(Temperatures.rejected("400: Unsupported parameter: 'temperature'")).isTrue();
    assertThat(Temperatures.rejected("400: temperature must be between 0 and 2")).isFalse();
    assertThat(Temperatures.rejected("500: temperature is deprecated")).isFalse();
    assertThat(Temperatures.rejected((String) null)).isFalse();
  }
}
