package me.kezhenxu94.springagent.integration.feishu.usermodels;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
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

  static final String ACTIVE = "cfg_active";
  static final String NAME = "cfg_name";
  static final String BASE_URL = "cfg_baseurl";
  static final String MODEL = "cfg_model";
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
      final String activeName,
      final List<String> builtinModels,
      final String defaultModel)
      throws IOException {
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
      options.add(
          option(
              root, messages.get("config-option", config.name(), config.model()), config.name()));
      if (config.name().equals(activeName)) {
        selected = options.size();
      }
    }
    select.put("initial_index", selected);
    return select;
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
  public record Submission(String active, String name, String baseUrl, String model, String token) {

    /** Whether the add fields were filled in at all. */
    public boolean adding() {
      return name != null || baseUrl != null || model != null || token != null;
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
     */
    public boolean builtinByName() {
      return model != null && baseUrl == null && token == null && name == null;
    }
  }
}
