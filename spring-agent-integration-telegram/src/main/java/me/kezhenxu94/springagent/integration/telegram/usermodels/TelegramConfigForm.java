package me.kezhenxu94.springagent.integration.telegram.usermodels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardButton;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramKeyboards;

/**
 * The model settings as a message, and the reading back of {@code /config add …}.
 *
 * <p>The Slack modal's counterpart, split differently because a Telegram message holds buttons and
 * nothing else. Choosing — which model, how hard it thinks, which protocol — is a button each,
 * applied on the press. Adding an endpoint takes typing, so it is a command written in one line:
 *
 * <pre>/config add name=kimi url=https://… model=kimi-k2 token=sk-… [provider=…] [effort=…]
 * [temperature=…] [strict=on]</pre>
 *
 * and {@code /config model=<name>} names one of the deployment's own models the keyboard had no
 * room for. Both halves live here for the reason {@code SlackConfigForm} gives: the data a button
 * carries is what the press is read back from, and nothing at runtime reports a mismatch.
 */
@RequiredArgsConstructor
public class TelegramConfigForm {

  /** The option standing for the application's own model, as configured. */
  static final String DEFAULT_OPTION = "__default__";

  /** Prefix marking an option as one of the application's own models — see the Slack form. */
  static final String BUILTIN_OPTION = "__builtin__";

  /** The effort option standing for the application's own setting. */
  static final String EFFORT_INHERIT_OPTION = "__inherit__";

  /** The provider option standing for "whatever this deployment itself speaks". */
  static final String PROVIDER_INHERIT_OPTION = "__provider_default__";

  /** {@code sa_cfg:m:<value>}, a model. */
  static final String MODEL = TelegramKeyboards.CONFIG + "m:";

  /** {@code sa_cfg:e:<value>}, an effort. */
  static final String EFFORT = TelegramKeyboards.CONFIG + "e:";

  /** {@code sa_cfg:p:<value>}, a protocol. */
  static final String PROVIDER = TelegramKeyboards.CONFIG + "p:";

  /** How many of the application's models get a button; a long list is a wall on a phone. */
  static final int MAX_BUILTIN_OPTIONS = 20;

  private final TelegramMessages messages;

  /** What the settings are now, and how to change them. */
  public TelegramText text(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final String defaultModel) {
    final var current =
        active == null
            ? messages.get("config-default-option") + " (" + defaultModel + ")"
            : Objects.requireNonNullElse(
                UserModelRegistry.displayName(active), messages.get("config-default-option"));
    final var effort =
        active == null || active.reasoningEffort() == null
            ? messages.get("config-effort-inherit")
            : active.reasoningEffort();
    return TelegramText.builder()
        .styled("bold", messages.get("config-title"))
        .text("\n\n")
        .text(
            configured.isEmpty()
                ? messages.get("config-intro-empty")
                : messages.get("config-intro", configured.size()))
        .text("\n")
        .text(messages.get("config-current", current, effort))
        .text("\n\n")
        .styled("italic", messages.get("config-add-hint"))
        .build();
  }

  /**
   * The buttons: every model the user could be on, then the efforts, then — where there is more
   * than one — the protocols. What is in force is marked, so the keyboard is also the answer to
   * "what am I on".
   */
  public InlineKeyboardMarkup keyboard(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final List<String> builtinModels,
      final String defaultModel,
      final List<String> providers,
      final String defaultProvider) {
    final var activeName = active == null ? null : active.name();
    final var rows = new ArrayList<List<InlineKeyboardButton>>();

    final var models = new LinkedHashMap<String, String>();
    if (builtinModels.isEmpty() || !builtinModels.contains(defaultModel)) {
      models.put(DEFAULT_OPTION, messages.get("config-default-option"));
    }
    for (final var model : capped(builtinModels, activeName, defaultModel)) {
      models.put(
          model.equals(defaultModel) ? DEFAULT_OPTION : BUILTIN_OPTION + model,
          messages.get("config-builtin-option", model));
    }
    for (final var config : configured) {
      models.put(config.name(), messages.get("config-option", config.name(), config.model()));
    }
    models.forEach(
        (value, label) -> {
          final var data = MODEL + value;
          if (TelegramKeyboards.fits(data)) {
            rows.add(List.of(button(label, data, isActive(value, activeName))));
          }
        });

    final var effort = active == null ? null : active.reasoningEffort();
    final var efforts = new ArrayList<InlineKeyboardButton>();
    efforts.add(
        button(
            messages.get("config-effort-inherit-short"),
            EFFORT + EFFORT_INHERIT_OPTION,
            effort == null));
    ReasoningEfforts.VALUES.forEach(
        value -> efforts.add(button(value, EFFORT + value, value.equals(effort))));
    efforts.add(
        button(
            messages.get("config-effort-not-sent-short"),
            EFFORT + ReasoningEfforts.NOT_SENT,
            ReasoningEfforts.NOT_SENT.equals(effort)));
    for (var i = 0; i < efforts.size(); i += 3) {
      rows.add(efforts.subList(i, Math.min(i + 3, efforts.size())));
    }

    // Only where there is a choice — see the Slack form.
    if (providers != null && providers.size() > 1) {
      final var provider = active == null ? null : active.provider();
      final var protocols = new ArrayList<InlineKeyboardButton>();
      protocols.add(
          button(
              messages.get("config-provider-inherit", String.valueOf(defaultProvider)),
              PROVIDER + PROVIDER_INHERIT_OPTION,
              provider == null));
      providers.forEach(
          value -> protocols.add(button(value, PROVIDER + value, value.equals(provider))));
      for (var i = 0; i < protocols.size(); i += 3) {
        rows.add(protocols.subList(i, Math.min(i + 3, protocols.size())));
      }
    }
    return TelegramKeyboards.rows(rows);
  }

  private static boolean isActive(final String value, final String activeName) {
    if (activeName == null) {
      return DEFAULT_OPTION.equals(value);
    }
    if (value.startsWith(BUILTIN_OPTION)) {
      return activeName.equals(
          UserModelRegistry.BUILTIN_PREFIX + value.substring(BUILTIN_OPTION.length()));
    }
    return value.equals(activeName);
  }

  private static InlineKeyboardButton button(
      final String label, final String data, final boolean chosen) {
    return TelegramKeyboards.button(chosen ? TelegramKeyboards.CHOSEN + label : label, data);
  }

  /** At most {@link #MAX_BUILTIN_OPTIONS}, keeping the configured one and the one in use. */
  private static List<String> capped(
      final List<String> models, final String activeName, final String defaultModel) {
    if (models.size() <= MAX_BUILTIN_OPTIONS) {
      return models;
    }
    final var kept = new LinkedHashSet<String>();
    if (models.contains(defaultModel)) {
      kept.add(defaultModel);
    }
    models.stream()
        .filter(model -> (UserModelRegistry.BUILTIN_PREFIX + model).equals(activeName))
        .forEach(kept::add);
    for (final var model : models) {
      if (kept.size() >= MAX_BUILTIN_OPTIONS) {
        break;
      }
      kept.add(model);
    }
    return List.copyOf(kept);
  }

  /**
   * What {@code arguments} — everything after {@code /config} — asks for. Values are {@code
   * key=value}, separated by spaces; a value cannot itself contain one, which no URL, model name or
   * token does.
   */
  public static Submission read(final String arguments) {
    final var words = arguments == null ? new String[0] : arguments.strip().split("\\s+");
    final var values = new LinkedHashMap<String, String>();
    var adding = false;
    for (final var word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if ("add".equalsIgnoreCase(word)) {
        adding = true;
        continue;
      }
      final var equals = word.indexOf('=');
      if (equals > 0 && equals < word.length() - 1) {
        values.put(word.substring(0, equals).toLowerCase(Locale.ROOT), word.substring(equals + 1));
      }
    }
    return new Submission(
        adding,
        values.get("name"),
        values.get("provider"),
        values.getOrDefault("url", values.get("baseurl")),
        values.get("model"),
        values.get("effort"),
        values.get("temperature"),
        values.get("strict"),
        values.get("token"));
  }

  /** What one {@code /config …} command asks for. */
  public record Submission(
      boolean adding,
      String name,
      String provider,
      String baseUrl,
      String model,
      String effort,
      String temperature,
      String strictTools,
      String token) {

    /** Whether there was anything after {@code /config} at all. */
    public boolean empty() {
      return !adding
          && name == null
          && provider == null
          && baseUrl == null
          && model == null
          && effort == null
          && temperature == null
          && strictTools == null
          && token == null;
    }

    public boolean strict() {
      return "on".equalsIgnoreCase(strictTools) || "true".equalsIgnoreCase(strictTools);
    }

    /** Whether it carries a token, which decides whether the message has to go. */
    public boolean secret() {
      return token != null;
    }

    public boolean complete() {
      return name != null && baseUrl != null && model != null && token != null;
    }

    /** A built-in model named directly — see {@code SlackConfigForm.Submission#builtinByName}. */
    public boolean builtinByName() {
      return !adding && model != null && baseUrl == null && token == null && name == null;
    }

    /** The effort as stored: null for none given and for the deployment's own. */
    public String storedEffort() {
      return effort == null || EFFORT_INHERIT_OPTION.equals(effort) ? null : effort;
    }

    /** The provider as stored — see the Slack form for why the default is stored as null. */
    public String storedProvider() {
      return provider == null || PROVIDER_INHERIT_OPTION.equals(provider) ? null : provider;
    }
  }

  /** A press of one of the keyboard's buttons. */
  public record Press(char kind, String value) {}

  /** The press {@code data} describes, or null where it is not one of these. */
  public static Press press(final String data) {
    for (final var prefix : List.of(MODEL, EFFORT, PROVIDER)) {
      if (data != null && data.startsWith(prefix) && data.length() > prefix.length()) {
        return new Press(prefix.charAt(prefix.length() - 2), data.substring(prefix.length()));
      }
    }
    return null;
  }
}
