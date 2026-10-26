package me.kezhenxu94.springagent.integration.telegram.usermodels;

import com.google.common.base.Strings;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.Temperatures;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.CallbackQuery;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springframework.core.task.TaskExecutor;

/**
 * {@code /config}: showing the model settings, and acting on a press or a command.
 *
 * <p>The escape hatch, as on Slack and Feishu: no path here reaches a model, so it works whatever
 * state the user's chosen endpoint is in.
 *
 * <p><b>Private chats only.</b> The settings are one person's, a keyboard in a group can be pressed
 * by anybody in it, and adding an endpoint means typing a token — which in a private chat the bot
 * can delete the moment it has read it, and in a group it cannot unless it is an admin there. So a
 * group is told to ask in private, and a token typed into one anyway is deleted where the bot is
 * able to and the sender told to revoke it where it is not.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramConfigHandler {

  private final TelegramBotApi telegram;
  private final UserModelRegistry registry;
  private final UserModelProbe probe;
  private final BuiltinModels builtins;
  private final UserChatClients chatClients;
  private final TelegramConfigForm form;
  private final TelegramMessages messages;
  private final TaskExecutor taskExecutor;

  /** Whether {@code text} is a {@code /config} command, with or without arguments. */
  public static boolean isConfigCommand(final String text) {
    if (text == null) {
      return false;
    }
    final var stripped = text.strip();
    return stripped.equalsIgnoreCase("/config")
        || (stripped.length() > 7
            && stripped.substring(0, 7).equalsIgnoreCase("/config")
            && Character.isWhitespace(stripped.charAt(7)));
  }

  /** Acts on {@code /config …}, {@code text} being the message with the bot's name taken off. */
  public void onCommand(final Message message, final String text) {
    final var chatId = String.valueOf(message.chat().id());
    final var userId = String.valueOf(message.from().id());
    final var submission = TelegramConfigForm.read(text.strip().substring("/config".length()));
    if (submission.secret()) {
      // First, whatever else happens: a token sitting in a chat is a token anybody who scrolls
      // back can read.
      final var deleted = delete(message);
      if (!message.chat().isPrivate()) {
        say(chatId, messages.get(deleted ? "config-private-only" : "config-token-exposed"));
        return;
      }
      if (!deleted) {
        say(chatId, messages.get("config-token-exposed"));
      }
    }
    if (!message.chat().isPrivate()) {
      say(chatId, messages.get("config-private-only"));
      return;
    }
    if (submission.empty()) {
      taskExecutor.execute(() -> show(chatId, userId));
      return;
    }
    say(chatId, apply(userId, chatId, submission));
  }

  /** Acts on a press of one of the settings' buttons, and redraws them to show what is now so. */
  public void onPress(final CallbackQuery query) {
    final var shown = query.message();
    final var press = TelegramConfigForm.press(query.data());
    if (press == null || shown == null || query.from() == null || !shown.chat().isPrivate()) {
      answer(query, null);
      return;
    }
    final var userId = String.valueOf(query.from().id());
    final var chatId = String.valueOf(shown.chat().id());
    final var said =
        switch (press.kind()) {
          case 'm' -> switchTo(userId, press.value());
          case 'e' -> effortFor(userId, press.value());
          case 'p' -> protocolFor(userId, chatId, press.value());
          default -> null;
        };
    answer(query, said == null ? messages.get("config-nothing") : said);
    taskExecutor.execute(() -> redraw(chatId, userId, shown.messageId()));
  }

  private void show(final String chatId, final String userId) {
    try {
      final var configured = registry.list(userId);
      final var active = registry.active(userId).orElse(null);
      telegram.sendMessage(
          chatId,
          null,
          form.text(configured, active, builtins.defaultModel()),
          null,
          form.keyboard(
              configured,
              active,
              builtins.list(),
              builtins.defaultModel(),
              chatClients.providers(),
              chatClients.defaultProvider()));
    } catch (Exception e) {
      log.error("Could not show the model settings to {}", userId, e);
      say(chatId, messages.get("config-failed"));
    }
  }

  private void redraw(final String chatId, final String userId, final int messageId) {
    try {
      final var configured = registry.list(userId);
      final var active = registry.active(userId).orElse(null);
      telegram.editMessageText(
          chatId,
          messageId,
          form.text(configured, active, builtins.defaultModel()),
          form.keyboard(
              configured,
              active,
              builtins.list(),
              builtins.defaultModel(),
              chatClients.providers(),
              chatClients.defaultProvider()));
    } catch (Exception e) {
      log.debug("Could not redraw the model settings for {}: {}", userId, e.getMessage());
    }
  }

  /**
   * Applies a command and returns what to tell the user — the same decisions, in the same order, as
   * {@code SlackConfigHandler#apply}, so the two surfaces cannot come to mean different things.
   */
  String apply(
      final String userId, final String chatId, final TelegramConfigForm.Submission submission) {
    if (submission.effort() != null
        && !TelegramConfigForm.EFFORT_INHERIT_OPTION.equals(submission.effort())
        && !ReasoningEfforts.valid(submission.effort())) {
      return messages.get("config-bad-effort", submission.effort(), ReasoningEfforts.listed());
    }
    if (!submission.adding()) {
      if (submission.builtinByName()) {
        registry.activateBuiltin(userId, submission.model());
        return join(
            messages.get("config-switched-builtin", submission.model()),
            effortFor(userId, submission.effort()));
      }
      final var said =
          join(
              effortFor(userId, submission.effort()),
              protocolFor(userId, chatId, submission.provider()));
      return said == null ? messages.get("config-usage") : said;
    }
    if (!submission.complete()) {
      return messages.get("config-incomplete");
    }
    if (!Temperatures.valid(submission.temperature())) {
      return messages.get("config-bad-temperature", submission.temperature(), Temperatures.MAX);
    }
    taskExecutor.execute(() -> add(chatId, userId, submission));
    return messages.get("config-testing", submission.name());
  }

  /** Applies the button or option naming a model, and says what it did. */
  private String switchTo(final String userId, final String value) {
    if (TelegramConfigForm.DEFAULT_OPTION.equals(value)) {
      registry.useDefault(userId);
      return messages.get("config-switched-default");
    }
    if (value.startsWith(TelegramConfigForm.BUILTIN_OPTION)) {
      final var model = value.substring(TelegramConfigForm.BUILTIN_OPTION.length());
      registry.activateBuiltin(userId, model);
      return messages.get("config-switched-builtin", model);
    }
    if (!registry.activate(userId, value)) {
      // Offered when drawn and deleted since; the built-in model is where they are either way.
      return messages.get("config-switched-default");
    }
    return messages.get("config-switched", value);
  }

  /** Applies an effort to the model in use, or null where it is already that — or none given. */
  private String effortFor(final String userId, final String chosen) {
    if (chosen == null) {
      return null;
    }
    final var effort =
        TelegramConfigForm.EFFORT_INHERIT_OPTION.equals(chosen)
            ? null
            : ReasoningEfforts.normalize(chosen);
    final var current = registry.active(userId).map(UserModelConfig::reasoningEffort).orElse(null);
    if (Objects.equals(effort, current)) {
      return null;
    }
    final var row = registry.setActiveEffort(userId, effort);
    final var name =
        Optional.ofNullable(UserModelRegistry.displayName(row))
            .orElse(messages.get("config-default-option"));
    return effort == null
        ? messages.get("config-effort-inherited", name)
        : messages.get("config-effort-set", name, effort);
  }

  /**
   * Moves the endpoint in use to another protocol after probing it there — see {@code
   * SlackConfigHandler#protocolFor}. The outcome arrives as a message of its own.
   */
  private String protocolFor(final String userId, final String chatId, final String chosen) {
    if (chosen == null) {
      return null;
    }
    final var provider = TelegramConfigForm.PROVIDER_INHERIT_OPTION.equals(chosen) ? null : chosen;
    final var active = registry.active(userId).orElse(null);
    if (active == null || Objects.equals(provider, active.provider())) {
      return null;
    }
    final var name = active.name();
    if (Strings.isNullOrEmpty(active.baseUrl())) {
      return messages.get(
          "config-provider-builtin",
          Optional.ofNullable(UserModelRegistry.displayName(active))
              .orElse(messages.get("config-default-option")));
    }
    final var spoken = provider == null ? chatClients.defaultProvider() : provider;
    taskExecutor.execute(
        () -> {
          final var failure =
              probe.check(
                  provider,
                  active.baseUrl(),
                  active.model(),
                  registry.tokenOf(active),
                  active.reasoningEffort(),
                  active.temperature(),
                  Boolean.TRUE.equals(active.strictTools()),
                  registry.headersOf(active));
          if (failure != null) {
            final var current =
                active.provider() == null ? chatClients.defaultProvider() : active.provider();
            say(chatId, messages.get("config-provider-failed", name, spoken, current, failure));
            return;
          }
          registry.setProvider(userId, name, provider);
          say(chatId, messages.get("config-provider-set", name, spoken));
        });
    return messages.get("config-provider-testing", name, spoken);
  }

  /** Tests an endpoint and stores it, then puts the user on it — only once the probe passed. */
  private void add(
      final String chatId, final String userId, final TelegramConfigForm.Submission submission) {
    if (registry.full(userId, submission.name())) {
      say(chatId, messages.get("config-too-many", registry.maxPerUser()));
      return;
    }
    if (!UserModelRegistry.validName(submission.name())) {
      say(chatId, messages.get("config-bad-name", submission.name()));
      return;
    }
    final var effort = ReasoningEfforts.normalize(submission.storedEffort());
    final var provider = submission.storedProvider();
    final var temperature = Temperatures.parse(submission.temperature());
    final var failure =
        probe.check(
            provider,
            submission.baseUrl(),
            submission.model(),
            submission.token(),
            effort,
            temperature,
            submission.strict(),
            null);
    if (failure != null) {
      say(chatId, messages.get("config-add-failed", submission.name(), failure));
      return;
    }
    registry.save(
        userId,
        submission.name(),
        provider,
        submission.baseUrl(),
        submission.model(),
        submission.token(),
        effort,
        temperature,
        submission.strict(),
        null);
    registry.activate(userId, submission.name());
    say(chatId, messages.get("config-added", submission.name()));
  }

  private boolean delete(final Message message) {
    try {
      telegram.deleteMessage(String.valueOf(message.chat().id()), message.messageId());
      return true;
    } catch (Exception e) {
      log.warn("Could not delete a /config message carrying a token: {}", e.getMessage());
      return false;
    }
  }

  private void say(final String chatId, final String text) {
    try {
      telegram.sendMessage(chatId, null, TelegramText.plain(text), null, null);
    } catch (Exception e) {
      log.error("Could not send the model settings result to {}", chatId, e);
    }
  }

  private void answer(final CallbackQuery query, final String text) {
    try {
      telegram.answerCallbackQuery(query.id(), text);
    } catch (Exception e) {
      log.debug("Could not answer a settings press: {}", e.getMessage());
    }
  }

  private static String join(final String first, final String second) {
    if (first == null) {
      return second;
    }
    return second == null ? first : first + " " + second;
  }
}
