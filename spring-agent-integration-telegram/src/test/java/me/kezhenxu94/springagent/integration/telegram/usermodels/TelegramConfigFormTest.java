package me.kezhenxu94.springagent.integration.telegram.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelegramConfigFormTest {

  private final TelegramConfigForm form =
      new TelegramConfigForm(
          new TelegramMessages(
              new TelegramProperties(
                  "1:t", null, Locale.ENGLISH, null, null, null, null, null, null)));

  @Test
  @DisplayName("an add command is read field by field, in any order")
  void shouldReadAnAdd() {
    final var submission =
        TelegramConfigForm.read(
            " add token=sk-1 name=kimi url=https://api.example.com/v1 model=kimi-k2 effort=high");

    assertThat(submission.adding()).isTrue();
    assertThat(submission.complete()).isTrue();
    assertThat(submission.secret()).isTrue();
    assertThat(submission.baseUrl()).isEqualTo("https://api.example.com/v1");
    assertThat(submission.storedEffort()).isEqualTo("high");
  }

  @Test
  @DisplayName("a model alone names one of the deployment's own")
  void shouldReadABuiltinByName() {
    final var submission = TelegramConfigForm.read("model=gpt-5");

    assertThat(submission.builtinByName()).isTrue();
    assertThat(submission.secret()).isFalse();
  }

  @Test
  @DisplayName("nothing after /config is a request to see the settings")
  void shouldReadNothing() {
    assertThat(TelegramConfigForm.read("").empty()).isTrue();
  }

  @Test
  @DisplayName("no button carries more than Telegram allows, however long a model's name")
  void shouldKeepButtonsWithinTelegramsLimit() {
    final var keyboard =
        form.keyboard(
            List.of(),
            null,
            List.of(
                "short",
                "accounts/fireworks/models/a-model-whose-name-is-far-too-long-for-a-button"),
            "short",
            List.of("openai", "anthropic"),
            "openai");

    assertThat(keyboard.inlineKeyboard())
        .flatMap(row -> row)
        .allSatisfy(
            button ->
                assertThat(button.callbackData().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64));
  }

  @Test
  @DisplayName("a press reads back as the kind of choice and its value")
  void shouldReadAPress() {
    assertThat(TelegramConfigForm.press("sa_cfg:m:__default__"))
        .isEqualTo(new TelegramConfigForm.Press('m', "__default__"));
    assertThat(TelegramConfigForm.press("sa_cfg:e:high"))
        .isEqualTo(new TelegramConfigForm.Press('e', "high"));
    assertThat(TelegramConfigForm.press("sa_stop:1:2")).isNull();
  }
}
