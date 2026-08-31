package me.kezhenxu94.springagent.integration.slack.usermodels;

import com.google.common.base.Strings;
import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.PlainTextInputElement;
import com.slack.api.model.block.element.StaticSelectElement;
import com.slack.api.model.view.View;
import com.slack.api.model.view.ViewClose;
import com.slack.api.model.view.ViewSubmit;
import com.slack.api.model.view.ViewTitle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.handler.SlackBlockKit;
import me.kezhenxu94.springagent.integration.slack.handler.SlackQuestionForm.ViewValue;

/**
 * The modal {@code /config} opens, and the reading back of what was submitted on it.
 *
 * <p>The Feishu card's counterpart. Both directions live here for the reason {@code
 * SlackQuestionForm} gives: the block ids written onto the form are the keys read back out of
 * {@code state.values}, and nothing at runtime would report a mismatch — the submission would
 * simply come back empty.
 *
 * <p><b>A modal, unlike the question form, and that is the whole reason {@code /config} is a slash
 * command.</b> {@code views.open} needs a {@code trigger_id}, which exists only in response to
 * something the user just did; the agent asks its questions unprompted mid-turn and so has none,
 * but a slash command carries one. It matters here more than it would there: this form takes an API
 * token, and a modal is private to the person who opened it, where input blocks in a message would
 * put the token into the channel history for everyone in it.
 */
@RequiredArgsConstructor
public class SlackConfigForm {

  /**
   * The modal's callback id, which is what Bolt routes its submission by.
   *
   * <p>Also the slash command's own name is {@code /config}; the two are unrelated strings and only
   * this one is coupled to the handler registration.
   */
  public static final String CALLBACK_ID = "sa_config";

  /**
   * What the in-message form's submit button carries, for the no-slash-command fallback.
   *
   * <p>The modal has no such button — a modal submits itself — so this is only ever pressed on the
   * form posted into a channel.
   */
  public static final String ACTION_ID = "sa_config_submit";

  /** The option standing for the application's own model, as configured. */
  public static final String DEFAULT_OPTION = "__default__";

  /**
   * Prefix marking an option as one of the application's own models rather than one of the user's.
   * What follows it is the model name as the endpoint reports it.
   */
  public static final String BUILTIN_OPTION = "__builtin__";

  /**
   * How many of the application's models the dropdown will show.
   *
   * <p>Slack refuses a static select with more than 100 options, and caps a message at 50 blocks. A
   * gateway serving a few hundred models would therefore mean no form at all — so the list is cut
   * well short of either, and the Model field doubles as a way to name any built-in model directly.
   */
  static final int MAX_BUILTIN_OPTIONS = 50;

  /**
   * The reasoning-effort option standing for the application's own setting.
   *
   * <p>An option of its own, distinct from leaving the select alone: untouched means "change
   * nothing about the effort", which is what a press that only switches model has to mean, while
   * this means "go back to the application's setting" and is the way out of an effort already
   * stored.
   */
  public static final String EFFORT_INHERIT_OPTION = "__inherit__";

  static final String ACTIVE_BLOCK = "sa_cfg_active";
  static final String NAME_BLOCK = "sa_cfg_name";
  static final String BASE_URL_BLOCK = "sa_cfg_baseurl";
  static final String MODEL_BLOCK = "sa_cfg_model";
  static final String EFFORT_BLOCK = "sa_cfg_effort";
  static final String TOKEN_BLOCK = "sa_cfg_token";

  private static final String ACTION_SUFFIX = "_a";

  private final SlackMessages messages;

  /**
   * The form, as the blocks of a message.
   *
   * @param configured every endpoint the user has registered
   * @param active the row their runs go through, or null for the application's own model as
   *     configured
   * @param builtinModels what the application's endpoint reports it can serve, empty where it would
   *     not say — an ordinary answer rather than a failure
   * @param defaultModel the model the application is configured with
   */
  public List<LayoutBlock> blocks(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final List<String> builtinModels,
      final String defaultModel) {
    return blocks(configured, active, builtinModels, defaultModel, false);
  }

  /**
   * The same form as a message, for the case where the slash command was never declared on the
   * Slack app and so {@code /config} never reaches this application at all.
   *
   * <p>Slack sends a message beginning with a space verbatim rather than treating it as a command,
   * which is what makes {@code " /config"} a way in that needs no setup. It is the poorer of the
   * two: with no {@code trigger_id} there can be no modal, so the API token is typed into the
   * channel — which is why the handler deletes the form the moment it is submitted, and why the
   * token field says so.
   */
  public List<LayoutBlock> messageBlocks(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final List<String> builtinModels,
      final String defaultModel) {
    return blocks(configured, active, builtinModels, defaultModel, true);
  }

  private List<LayoutBlock> blocks(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final List<String> builtinModels,
      final String defaultModel,
      final boolean inMessage) {
    final var activeName = active == null ? null : active.name();
    final var blocks = new ArrayList<LayoutBlock>();
    if (inMessage) {
      // A modal carries its title in its frame; a message has to say what it is itself.
      blocks.add(SlackBlockKit.header(messages.get("config-title")));
    }
    blocks.add(
        SlackBlockKit.markdown(
            configured.isEmpty()
                ? messages.get("config-intro-empty")
                : messages.get("config-intro", configured.size())));

    blocks.add(
        InputBlock.builder()
            .blockId(ACTIVE_BLOCK)
            // Optional, because somebody may be here only to add an endpoint and a form that
            // insists on every field cannot be submitted at all.
            .optional(true)
            .label(label(messages.get("config-active-label")))
            .element(
                StaticSelectElement.builder()
                    .actionId(ACTIVE_BLOCK + ACTION_SUFFIX)
                    .options(options(configured, activeName, builtinModels, defaultModel))
                    .build())
            .build());

    blocks.add(SlackBlockKit.divider());
    blocks.add(SlackBlockKit.markdown(messages.get("config-add-hint")));
    blocks.add(input(NAME_BLOCK, "config-name-label", "config-name-placeholder", false));
    blocks.add(input(BASE_URL_BLOCK, "config-baseurl-label", "config-baseurl-placeholder", false));
    blocks.add(input(MODEL_BLOCK, "config-model-label", "config-model-placeholder", false));
    blocks.add(effortInput(active == null ? null : active.reasoningEffort()));
    blocks.add(input(TOKEN_BLOCK, "config-token-label", "config-token-placeholder", inMessage));

    if (inMessage) {
      blocks.add(
          SlackBlockKit.actions(
              "sa_cfg_actions",
              List.of(
                  SlackBlockKit.button(ACTION_ID, messages.get("config-submit"), "1", "primary"))));
    }
    return blocks;
  }

  /**
   * The whole modal, ready for {@code views.open}.
   *
   * <p>No submit button among the blocks: a modal has its own, and Slack sends the submission as a
   * {@code view_submission} rather than as a block action.
   */
  public View view(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final List<String> builtinModels,
      final String defaultModel) {
    return View.builder()
        .type("modal")
        .callbackId(CALLBACK_ID)
        .title(viewTitle(messages.get("config-title")))
        .submit(ViewSubmit.builder().type("plain_text").text(messages.get("config-submit")).build())
        .close(ViewClose.builder().type("plain_text").text(messages.get("config-close")).build())
        .blocks(blocks(configured, active, builtinModels, defaultModel))
        .build();
  }

  private static ViewTitle viewTitle(final String text) {
    // Slack caps a modal title at 24 characters and refuses the whole view over a longer one.
    return ViewTitle.builder().type("plain_text").text(SlackBlockKit.clamp(text, 24)).build();
  }

  /**
   * How hard the model should think, as a list rather than a field to type into: Spring AI takes
   * {@code reasoning_effort} as a bare string, so a typo would be an endpoint that fails on every
   * message rather than a validation error.
   *
   * <p>Preselected with what the model in use is set to, so the form reads as a statement of the
   * configuration rather than a question. The consequence is worth knowing: a submission applies
   * the effort shown to whatever model it leaves the user on, so switching model and leaving this
   * alone carries the shown effort across. A value already in force is treated as nothing to do,
   * which is what keeps an untouched form from rewriting anything.
   *
   * @param current what the model in use is set to, or null for the application's own setting
   */
  private InputBlock effortInput(final String current) {
    final var options = new ArrayList<OptionObject>();
    options.add(option(messages.get("config-effort-inherit"), EFFORT_INHERIT_OPTION));
    ReasoningEfforts.VALUES.forEach(effort -> options.add(option(effort, effort)));
    options.add(option(messages.get("config-effort-not-sent"), ReasoningEfforts.NOT_SENT));
    final var initial =
        options.stream()
            .filter(
                option ->
                    option.getValue().equals(current == null ? EFFORT_INHERIT_OPTION : current))
            .findFirst()
            .orElse(options.get(0));
    return InputBlock.builder()
        .blockId(EFFORT_BLOCK)
        .optional(true)
        .label(label(messages.get("config-effort-label")))
        .element(
            StaticSelectElement.builder()
                .actionId(EFFORT_BLOCK + ACTION_SUFFIX)
                .placeholder(label(messages.get("config-effort-hint")))
                .options(options)
                .initialOption(initial)
                .build())
        .build();
  }

  /**
   * What the user could be on: the application's own models first, then their own endpoints.
   *
   * <p>The configured model is offered as the plain default rather than as a second entry beside
   * itself — picking it means having no choice of your own recorded at all, which is the state to
   * be able to get back to.
   */
  private List<OptionObject> options(
      final List<UserModelConfig> configured,
      final String activeName,
      final List<String> builtinModels,
      final String defaultModel) {
    final var options = new ArrayList<OptionObject>();
    if (builtinModels.isEmpty()) {
      options.add(option(messages.get("config-default-option"), DEFAULT_OPTION));
    } else {
      final var shown = capped(builtinModels, activeName, defaultModel);
      if (!shown.contains(defaultModel)) {
        // A gateway that lists its models need not list the one this application was pointed at,
        // and a form with no way back to the built-in model is the one thing this form may not be.
        options.add(option(messages.get("config-default-option"), DEFAULT_OPTION));
      }
      for (final var model : shown) {
        options.add(
            option(
                messages.get("config-builtin-option", model),
                model.equals(defaultModel) ? DEFAULT_OPTION : BUILTIN_OPTION + model));
      }
    }
    for (final var config : configured) {
      // The effort goes in the label so the select is also where you see what is set: it is stored
      // per model and nothing else on this form would show it.
      final var label =
          config.reasoningEffort() == null
              ? messages.get("config-option", config.name(), config.model())
              : messages.get(
                  "config-option-effort", config.name(), config.model(), config.reasoningEffort());
      options.add(option(label, config.name()));
    }
    return options;
  }

  /**
   * At most {@link #MAX_BUILTIN_OPTIONS} of the models on offer, keeping the two that must be
   * reachable however long the list is: the one the application is configured with, and the one
   * this user is on.
   */
  private static List<String> capped(
      final List<String> models, final String activeName, final String defaultModel) {
    if (models.size() <= MAX_BUILTIN_OPTIONS) {
      return models;
    }
    final var kept = new LinkedHashSet<String>();
    if (models.contains(defaultModel)) {
      kept.add(defaultModel);
    }
    models.stream().filter(model -> isBuiltinActive(activeName, model)).forEach(kept::add);
    for (final var model : models) {
      if (kept.size() >= MAX_BUILTIN_OPTIONS) {
        break;
      }
      kept.add(model);
    }
    return List.copyOf(kept);
  }

  private static boolean isBuiltinActive(final String activeName, final String model) {
    return activeName != null
        && activeName.startsWith(UserModelRegistry.BUILTIN_PREFIX)
        && activeName.substring(UserModelRegistry.BUILTIN_PREFIX.length()).equals(model);
  }

  private static OptionObject option(final String text, final String value) {
    return OptionObject.builder()
        .text(PlainTextObject.builder().text(SlackBlockKit.clamp(text, 75)).build())
        .value(SlackBlockKit.clamp(value, 150))
        .build();
  }

  /**
   * @param warnInChannel whether to say that this field is about to be visible to the channel,
   *     which is true only of the token and only on the in-message form
   */
  private InputBlock input(
      final String blockId,
      final String labelKey,
      final String placeholderKey,
      final boolean warnInChannel) {
    final var element = PlainTextInputElement.builder().actionId(blockId + ACTION_SUFFIX);
    element.placeholder(
        PlainTextObject.builder()
            .text(SlackBlockKit.clamp(messages.get(placeholderKey), 150))
            .build());
    return InputBlock.builder()
        .blockId(blockId)
        .optional(true)
        .label(label(messages.get(labelKey)))
        // Slack has no masked text input at all, in a modal or out of it. In a modal that is
        // tolerable, the view being private to whoever opened it; in a channel it is not, so the
        // in-message form warns and the handler deletes it on submit.
        .hint(
            warnInChannel && TOKEN_BLOCK.equals(blockId)
                ? label(messages.get("config-token-hint"))
                : null)
        .element(element.build())
        .build();
  }

  private static PlainTextObject label(final String text) {
    return PlainTextObject.builder().text(SlackBlockKit.clamp(text, 75)).build();
  }

  /** What the user filled in, as the handler needs it. */
  public Submission read(final Map<String, Map<String, ViewValue>> state) {
    return new Submission(
        value(state, ACTIVE_BLOCK),
        value(state, NAME_BLOCK),
        value(state, BASE_URL_BLOCK),
        value(state, MODEL_BLOCK),
        value(state, EFFORT_BLOCK),
        value(state, TOKEN_BLOCK));
  }

  private static String value(
      final Map<String, Map<String, ViewValue>> state, final String blockId) {
    if (state == null) {
      return null;
    }
    final var block = state.get(blockId);
    if (block == null) {
      return null;
    }
    final var held = block.get(blockId + ACTION_SUFFIX);
    if (held == null) {
      return null;
    }
    if (!Strings.isNullOrEmpty(held.value())) {
      return held.value().trim();
    }
    return held.selectedOption() == null || Strings.isNullOrEmpty(held.selectedOption().value())
        ? null
        : held.selectedOption().value();
  }

  /**
   * One press of the form's submit button.
   *
   * <p>The two halves are independent on purpose: a user may switch without adding, add without
   * switching, or do both — which is what lets somebody locked out by a bad endpoint switch away
   * from it in the same press that they correct it.
   */
  public record Submission(
      String active, String name, String baseUrl, String model, String effort, String token) {

    /**
     * Whether the add fields were filled in at all.
     *
     * <p>The effort is not one of them: it is not part of what identifies an endpoint, and a
     * submission carrying only an effort is a change to the model already in use rather than an
     * incomplete attempt to register one.
     */
    public boolean adding() {
      return name != null || baseUrl != null || model != null || token != null;
    }

    /**
     * The effort as it should be stored: null both for an untouched select and for the option
     * standing for the application's setting, the two being told apart by {@link #effort()} itself.
     */
    public String storedEffort() {
      return effort == null || SlackConfigForm.EFFORT_INHERIT_OPTION.equals(effort) ? null : effort;
    }

    /** Whether they were filled in completely enough to register an endpoint. */
    public boolean complete() {
      return name != null && baseUrl != null && model != null && token != null;
    }

    /**
     * Whether this names one of the application's own models rather than an endpoint: the model
     * alone, with no URL and no token, because none is needed to reach it. This is how any built-in
     * model stays reachable on a gateway serving more than the dropdown can hold.
     *
     * <p>Says nothing about the effort: one chosen alongside is applied to that built-in model once
     * it is the one in use, the same as it would be to an endpoint of the user's own.
     */
    public boolean builtinByName() {
      return model != null && baseUrl == null && token == null && name == null;
    }
  }
}
