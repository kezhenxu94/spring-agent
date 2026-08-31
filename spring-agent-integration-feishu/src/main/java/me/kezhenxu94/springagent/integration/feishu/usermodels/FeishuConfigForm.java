package me.kezhenxu94.springagent.integration.feishu.usermodels;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the card {@code /config} opens, and reads back what was submitted on it.
 *
 * <p>Both directions live here for the reason {@code FeishuQuestionForm} gives: the names written
 * onto the form elements are the keys read back out of {@code form_value}, and splitting them would
 * turn a change to either into a silent mismatch rather than a compile error.
 *
 * <p>Nothing here talks to Feishu — JSON in, JSON out — so the layout and the parsing are testable
 * without a tenant.
 */
@RequiredArgsConstructor
public class FeishuConfigForm {

  /** The {@code button} value that brings a submission of this form back to us. */
  public static final String ACTION = "config";

  /**
   * The option standing for the application's own model. Not a name a user could collide with:
   * {@link UserModelConfig} names come from a text input, and this one never goes through it.
   */
  public static final String DEFAULT_OPTION = "__default__";

  /**
   * Prefix marking an option as one of the application's own models rather than one of the user's.
   * What follows it is the model name as the endpoint reports it.
   */
  public static final String BUILTIN_OPTION = "__builtin__";

  /**
   * How many of the application's models the dropdown will show.
   *
   * <p>Feishu caps how many elements one card may hold, and refuses the whole card with {@code
   * 11310 element exceeds the limit} rather than truncating — so a gateway serving a few hundred
   * models would mean no card at all, which is the one failure this card must not have. The number
   * is not documented, so this is deliberately well under anything plausible.
   *
   * <p>Truncating alphabetically would be useless on such a gateway on its own, which is why the
   * Model field doubles as a way to name any built-in model directly: the dropdown is the
   * convenience, not the only route.
   */
  static final int MAX_BUILTIN_OPTIONS = 50;

  /**
   * The reasoning-effort option standing for the application's own setting.
   *
   * <p>Needed as an option of its own, distinct from leaving the dropdown alone: untouched means
   * "change nothing about the effort", which is what a press that only switches model has to mean,
   * while this means "go back to the application's setting" and is the way out of an effort already
   * stored on a model.
   */
  public static final String EFFORT_INHERIT_OPTION = "__inherit__";

  static final String ACTIVE = "cfg_active";
  static final String NAME = "cfg_name";
  static final String BASE_URL = "cfg_baseurl";
  static final String MODEL = "cfg_model";
  static final String EFFORT = "cfg_effort";
  static final String TOKEN = "cfg_token";

  private final JsonMapper objectMapper;
  private final FeishuMessages messages;

  /** Supplied by {@code FeishuUserModelsConfiguration}, which is where the property is read. */
  private final Resource template;

  /**
   * The card as this user should see it: their endpoints as options, the one in use preselected,
   * and empty fields for adding another.
   *
   * @param configured every endpoint they have registered, in the order to show them
   * @param activeName the one their runs go through, or null for the application's own model
   * @param builtinModels what the application's own endpoint reports it can serve, empty where it
   *     would not say — which is an ordinary answer, not a failure, and shows as the single
   *     built-in entry
   * @param defaultModel the model the application is configured with, so it can be offered as the
   *     plain default rather than as a second entry beside itself
   */
  public String card(
      final List<UserModelConfig> configured,
      final UserModelConfig active,
      final List<String> builtinModels,
      final String defaultModel)
      throws IOException {
    final var activeName = active == null ? null : active.name();
    final var root = (ObjectNode) objectMapper.readTree(rendered());
    final var card = (ObjectNode) root.path("card");
    final var elements = (ArrayNode) card.path("body").path("elements");

    final var intro = (ObjectNode) root.path("intro").deepCopy();
    intro.put(
        "content",
        configured.isEmpty()
            ? messages.get("config-intro-empty")
            : messages.get("config-intro", configured.size()));
    elements.add(intro);

    final var form = (ObjectNode) root.path("form").deepCopy();
    final var formElements = (ArrayNode) form.path("elements");

    // The caption is a row of its own: select_static has no label field, and Feishu rejects the
    // whole card rather than ignoring one.
    formElements.add(root.path("activeLabel").deepCopy());
    formElements.add(activeSelect(root, configured, activeName, builtinModels, defaultModel));
    formElements.add(root.path("addHint").deepCopy());
    formElements.add(root.path("nameInput").deepCopy());
    formElements.add(root.path("baseUrlInput").deepCopy());
    formElements.add(root.path("modelInput").deepCopy());
    formElements.add(root.path("effortLabel").deepCopy());
    formElements.add(effortSelect(root, active == null ? null : active.reasoningEffort()));
    formElements.add(root.path("tokenInput").deepCopy());
    formElements.add(root.path("submit").deepCopy());

    elements.add(form);
    return objectMapper.writeValueAsString(card);
  }

  /**
   * The dropdown of what this user could be on, with the application's own model always first so
   * that getting back to it never depends on having registered anything.
   *
   * <p>{@code initial_index} rather than {@code initial_option}: it is 1-based and unambiguous,
   * where {@code initial_option} is documented as matching the option's displayed text and would
   * break the moment two endpoints were labelled alike.
   */
  private ObjectNode activeSelect(
      final ObjectNode root,
      final List<UserModelConfig> configured,
      final String activeName,
      final List<String> builtinModels,
      final String defaultModel) {
    final var select = (ObjectNode) root.path("activeSelect").deepCopy();
    final var options = (ArrayNode) select.path("options");
    var selected = 1;

    if (builtinModels.isEmpty()) {
      // The endpoint would not say what it serves — plenty do not — so the only built-in choice
      // anybody can express is the one the application is configured with.
      options.add(option(root, messages.get("config-default-option"), DEFAULT_OPTION));
    } else {
      for (final var model : capped(builtinModels, activeName, defaultModel)) {
        // The configured one is offered as the plain default rather than as a second entry beside
        // itself: picking it means having no choice of your own recorded at all, which is the
        // state to be able to get back to.
        final var isDefault = model.equals(defaultModel);
        options.add(
            option(
                root,
                messages.get("config-builtin-option", model),
                isDefault ? DEFAULT_OPTION : BUILTIN_OPTION + model));
        if (isBuiltinActive(activeName, model)) {
          selected = options.size();
        }
      }
      // A gateway that lists its models need not list the one this application was pointed at, and
      // a card with no way back to the built-in model is the one thing this card may not be.
      if (!capped(builtinModels, activeName, defaultModel).contains(defaultModel)) {
        options.insert(0, option(root, messages.get("config-default-option"), DEFAULT_OPTION));
        if (selected > 1) {
          selected++;
        }
      }
    }

    for (final var config : configured) {
      // The effort is in the label so the dropdown is also where you see what is set: it is stored
      // per model and nothing else on this card would show it.
      final var label =
          config.reasoningEffort() == null
              ? messages.get("config-option", config.name(), config.model())
              : messages.get(
                  "config-option-effort", config.name(), config.model(), config.reasoningEffort());
      options.add(option(root, label, config.name()));
      if (config.name().equals(activeName)) {
        selected = options.size();
      }
    }
    // The row that says "the application's model, whatever it is" names no model, so none of the
    // built-in options above matched it. It is the plain default option, wherever that ended up in
    // the list — which is not always first, since a gateway that lists the configured model has it
    // among the others.
    if (activeName == null || UserModelRegistry.DEFAULT_ROW.equals(activeName)) {
      for (var index = 0; index < options.size(); index++) {
        if (DEFAULT_OPTION.equals(options.get(index).path("value").asString(""))) {
          selected = index + 1;
          break;
        }
      }
    }
    select.put("initial_index", selected);
    return select;
  }

  /**
   * How hard the model should think, as a list rather than a field to type into: Spring AI takes
   * {@code reasoning_effort} as a bare string, so a typo here is an endpoint that fails on every
   * message rather than a validation error.
   *
   * <p>Preselected with what the model in use is actually set to, so the card reads as a statement
   * of the configuration rather than as a question. That has a consequence worth knowing: a press
   * applies the effort shown to whatever model it leaves the user on, so switching model and
   * leaving this alone carries the shown effort over to the model switched to. The handler treats a
   * value that is already in force as nothing to do, which is what keeps an untouched card from
   * rewriting anything.
   *
   * @param current what the model in use is set to, or null for the application's own setting
   */
  private ObjectNode effortSelect(final ObjectNode root, final String current) {
    final var select = (ObjectNode) root.path("effortSelect").deepCopy();
    final var options = (ArrayNode) select.path("options");
    options.add(option(root, messages.get("config-effort-inherit"), EFFORT_INHERIT_OPTION));
    for (final var effort : ReasoningEfforts.VALUES) {
      options.add(option(root, effort, effort));
    }
    options.add(option(root, messages.get("config-effort-not-sent"), ReasoningEfforts.NOT_SENT));
    // initial_index is 1-based, and the first option is the application's own setting — which is
    // what a model with no effort of its own is on.
    var selected = 1;
    if (current != null) {
      final var values = new ArrayList<String>();
      values.add(EFFORT_INHERIT_OPTION);
      values.addAll(ReasoningEfforts.VALUES);
      values.add(ReasoningEfforts.NOT_SENT);
      final var found = values.indexOf(current);
      if (found >= 0) {
        selected = found + 1;
      }
    }
    select.put("initial_index", selected);
    return select;
  }

  /**
   * The card a press leaves behind: what the configuration now is, in words, and no form.
   *
   * <p>Replacing the form matters twice over. A form that stays on screen can be submitted again
   * later against a configuration that has moved on, and its token field would leave whatever was
   * typed there sitting in the chat history. Nothing here repeats the token — it is write-only on
   * every other path too.
   *
   * @param headline what the press did, in the words the toast used
   * @param active the row the user is now on, or null for the application's model as configured
   * @param defaultModel the model the application is configured with, for the row that names none
   */
  public String summary(
      final String headline, final UserModelConfig active, final String defaultModel)
      throws IOException {
    final var root = (ObjectNode) objectMapper.readTree(rendered());
    final var card = (ObjectNode) root.path("card");
    final var elements = (ArrayNode) card.path("body").path("elements");

    final var name =
        active == null
            ? messages.get("config-default-option")
            : Optional.ofNullable(UserModelRegistry.displayName(active))
                .orElse(messages.get("config-default-option"));
    final var model =
        active == null || active.model() == null || active.model().isBlank()
            ? defaultModel
            : active.model();
    final var endpoint =
        active == null || active.baseUrl() == null || active.baseUrl().isBlank()
            ? messages.get("config-summary-own-endpoint")
            : active.baseUrl();
    final var effort =
        active == null || active.reasoningEffort() == null
            ? messages.get("config-effort-inherit")
            : active.reasoningEffort();

    final var summary = (ObjectNode) root.path("summary").deepCopy();
    summary.put("content", messages.get("config-summary", headline, name, model, endpoint, effort));
    elements.add(summary);
    return objectMapper.writeValueAsString(card);
  }

  /**
   * At most {@link #MAX_BUILTIN_OPTIONS} of the models on offer, keeping the two that must be
   * reachable however long the list is: the one the application is configured with, and the one
   * this user is on. Without those two pinned, a gateway with hundreds of models would leave
   * somebody unable to see what they are using or to get back to the default.
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

  /** Whether the row in use is the built-in choice of {@code model}. */
  private static boolean isBuiltinActive(final String activeName, final String model) {
    return activeName != null
        && activeName.startsWith(UserModelRegistry.BUILTIN_PREFIX)
        && activeName.substring(UserModelRegistry.BUILTIN_PREFIX.length()).equals(model);
  }

  private ObjectNode option(final ObjectNode root, final String text, final String value) {
    final var option = (ObjectNode) root.path("selectOption").deepCopy();
    ((ObjectNode) option.path("text")).put("content", text);
    option.put("value", value);
    return option;
  }

  /** What the user filled in, as the handler needs it. */
  public Submission read(final Map<String, Object> formValue) {
    return new Submission(
        text(formValue, ACTIVE),
        text(formValue, NAME),
        text(formValue, BASE_URL),
        text(formValue, MODEL),
        text(formValue, EFFORT),
        text(formValue, TOKEN));
  }

  private static String text(final Map<String, Object> formValue, final String key) {
    if (formValue == null) {
      return null;
    }
    return formValue.get(key) instanceof String value && !value.isBlank() ? value.trim() : null;
  }

  private String rendered() throws IOException {
    return messages.renderConfigForm(template.getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * One press of the form's submit button.
   *
   * <p>The two halves are independent on purpose. A user may switch model without adding one, add
   * one without switching, or do both at once, and the handler treats each half on its own — which
   * is what lets somebody locked out by a bad endpoint switch away from it in the same press that
   * they correct it.
   *
   * @param active the option chosen in the dropdown, {@link #DEFAULT_OPTION} for the application's
   *     own model, or null if the dropdown was left alone
   */
  public record Submission(
      String active, String name, String baseUrl, String model, String effort, String token) {

    /**
     * Whether the add fields were filled in at all.
     *
     * <p>The effort is not one of them: it is not part of what identifies an endpoint, and a press
     * that chose only an effort is a change to the model already in use rather than an incomplete
     * attempt to register one.
     */
    public boolean adding() {
      return name != null || baseUrl != null || model != null || token != null;
    }

    /**
     * The effort as it should be stored: null both for an untouched dropdown and for the option
     * standing for the application's setting, the two being told apart by {@link #effort()} itself.
     */
    public String storedEffort() {
      return effort == null || FeishuConfigForm.EFFORT_INHERIT_OPTION.equals(effort)
          ? null
          : effort;
    }

    /** Whether they were filled in completely enough to register an endpoint. */
    public boolean complete() {
      return name != null && baseUrl != null && model != null && token != null;
    }

    /**
     * Whether this names one of the application's own models rather than an endpoint: the model
     * alone, with no URL and no token, because none is needed to reach it.
     *
     * <p>This is how any built-in model stays reachable on a gateway serving more of them than the
     * dropdown can hold — see {@link FeishuConfigForm#MAX_BUILTIN_OPTIONS}.
     *
     * <p>Says nothing about the effort: one chosen alongside is applied to that built-in model once
     * it is the one in use, the same as it would be to an endpoint of the user's own.
     */
    public boolean builtinByName() {
      return model != null && baseUrl == null && token == null && name == null;
    }
  }
}
