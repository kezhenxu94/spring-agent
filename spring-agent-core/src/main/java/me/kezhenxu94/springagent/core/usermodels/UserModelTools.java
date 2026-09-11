package me.kezhenxu94.springagent.core.usermodels;

import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * A user's own chat models, as tools the agent can manage on their behalf.
 *
 * <p>Scoped to the caller throughout: the owner comes from the tool context rather than from an
 * argument, so there is no way to phrase a request that reads or changes somebody else's models.
 *
 * <p>Failures are returned as text rather than thrown, the house rule {@code KnowledgeBaseTools}
 * states: the model reads the result and can correct itself, which it cannot do with an exception.
 *
 * <p>Registering is deliberately not the same act as switching. {@code AddChatModel} stores an
 * endpoint and leaves the user where they were; {@code UseChatModel} moves them. A user who pastes
 * a token with a typo in it therefore stays on a working model, and the probe below means they are
 * told about the typo either way.
 */
@Slf4j
@RequiredArgsConstructor
public class UserModelTools {

  private final UserModelRegistry registry;
  private final UserModelProbe probe;

  /** Only for {@link UserChatClients#providers()}: which protocols this deployment can speak. */
  private final UserChatClients chatClients;

  private final CoreMessages messages;

  @Tool(
      name = "AddChatModel",
      description =
"""
Register an OpenAI-compatible chat model for this user, so they can have their conversations answered
by it instead of the application's own model. Needs a name to refer to it by, the endpoint base URL,
the model name as that endpoint spells it, and an API token.

The endpoint is tested before anything is stored: if it cannot be reached, the token is rejected or the
model name is unknown, nothing is saved and the error is returned. Registering does NOT switch the user
onto it — call UseChatModel for that, or tell them to type /model <name>. Re-registering an existing
name updates it in place.

How hard the model should think is optional and must be one of the listed values; leave it out to use
whatever the application is configured with. Changing it later, without the token, is done on the
/config form or by typing /config <name> <effort>.

The token is stored encrypted and is never shown again, not even to the user who set it.
""")
  public String addChatModel(
      @ToolParam(description = "Short name for this model, used to switch to it later")
          final String name,
      @ToolParam(
              required = false,
              description =
                  "The endpoint base URL, e.g. https://api.example.com/v1. Leave it out for a"
                      + " provider that has one well-known endpoint, such as Gemini's")
          final String baseUrl,
      @ToolParam(
              required = false,
              description =
                  "Which protocol the endpoint speaks. Omit for this deployment's own, which is"
                      + " almost always right. Ask the user only if they mention a provider whose"
                      + " protocol differs from it; call ListChatModels to see which are served")
          final String provider,
      @ToolParam(description = "The model name as the endpoint spells it") final String model,
      @ToolParam(description = "The API token for this endpoint") final String apiToken,
      @ToolParam(
              required = false,
              description =
                  "How hard the model should think: one of none, minimal, low, medium, high, xhigh,"
                      + " max, or not-sent to send no reasoning effort at all. Omit to use the"
                      + " application's own setting")
          final String reasoningEffort,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);

    if (isBlank(name) || isBlank(model) || isBlank(apiToken)) {
      return messages.get("user-model-add-incomplete");
    }
    // Asked of the provider rather than always required: Gemini's Developer API has one well-known
    // host, so an ordinary Gemini row is a key and a model and no URL, and refusing it would be
    // refusing a registration for want of a field with nothing to put in it.
    if (isBlank(baseUrl) && chatClients.requiresBaseUrl(provider)) {
      return messages.get("user-model-add-incomplete");
    }
    // Rejected rather than dropped: an effort the endpoint will not understand is a model that
    // answers nothing, and silently ignoring what was asked for would leave the agent telling the
    // user it had set something it had not.
    if (!isBlank(reasoningEffort) && !ReasoningEfforts.valid(reasoningEffort)) {
      return messages.get("user-model-bad-effort", reasoningEffort, ReasoningEfforts.listed());
    }
    final var modelName = name.trim();
    if (!UserModelRegistry.validName(modelName)) {
      return messages.get("user-model-bad-name", modelName);
    }
    if (registry.full(userId, modelName)) {
      return messages.get("user-model-too-many", registry.maxPerUser());
    }

    // Checked before the probe rather than after: a protocol nobody serves cannot be tested, and
    // the endpoint's silence would be reported as the user's URL being wrong.
    if (!isBlank(provider) && !chatClients.providers().contains(provider.trim())) {
      return messages.get(
          "user-model-bad-provider", provider.trim(), String.join(", ", chatClients.providers()));
    }

    final var effort = ReasoningEfforts.normalize(reasoningEffort);
    final var failure =
        probe.check(
            provider,
            isBlank(baseUrl) ? null : baseUrl.trim(),
            model.trim(),
            apiToken.trim(),
            effort);
    if (failure != null) {
      return messages.get("user-model-unreachable", modelName, failure);
    }

    registry.save(
        userId,
        modelName,
        provider,
        isBlank(baseUrl) ? null : baseUrl.trim(),
        model.trim(),
        apiToken.trim(),
        effort);
    return messages.get("user-model-added", modelName, model.trim());
  }

  @Tool(
      name = "ListChatModels",
      description =
"""
List the chat models this user has registered and which one their conversations currently go through.
API tokens are never included. A user with none registered is using the application's own model.
""")
  public String listChatModels(final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var configured = registry.list(userId);
    if (configured.isEmpty()) {
      return messages.get("user-model-list-empty");
    }
    final var active = registry.active(userId).map(UserModelConfig::name).orElse(null);
    final var lines =
        configured.stream()
            .map(
                config ->
                    messages.get(
                        config.name().equals(active) ? "user-model-line-active" : "user-model-line",
                        config.name(),
                        config.model(),
                        config.baseUrl(),
                        effortOf(config)))
            .collect(Collectors.joining("\n"));
    return active == null
        ? messages.get("user-model-list-on-default", lines)
        : messages.get("user-model-list", lines);
  }

  @Tool(
      name = "UseChatModel",
      description =
"""
Switch this user's conversations to one of the chat models they have registered. Pass the name they gave
it, or "default" to go back to the application's own model. Takes effect from their next message.

How hard that model should think can be set at the same time, and works for the application's own models
too. Pass it on its own, with no name, to change it for whichever model they are already on.
""")
  public String useChatModel(
      @ToolParam(
              required = false,
              description =
                  "The registered model name, or \"default\"; omit to stay on the current one")
          final String name,
      @ToolParam(
              required = false,
              description =
                  "How hard the model should think: one of none, minimal, low, medium, high, xhigh,"
                      + " max, or not-sent to send no reasoning effort at all. Omit to leave it as"
                      + " it is")
          final String reasoningEffort,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (isBlank(name) && isBlank(reasoningEffort)) {
      return messages.get("user-model-add-incomplete");
    }
    if (!isBlank(reasoningEffort) && !ReasoningEfforts.valid(reasoningEffort)) {
      return messages.get("user-model-bad-effort", reasoningEffort, ReasoningEfforts.listed());
    }
    final var wanted = isBlank(name) ? null : name.trim();
    // Switching first, so that an effort given alongside a name lands on the model just chosen
    // rather than on the one being left behind.
    var switched = "";
    if (wanted != null) {
      if ("default".equalsIgnoreCase(wanted)) {
        registry.useDefault(userId);
        switched = messages.get("user-model-now-default");
      } else if (!registry.activate(userId, wanted)) {
        return messages.get("user-model-unknown", wanted, names(userId));
      } else {
        switched = messages.get("user-model-switched", wanted);
      }
    }
    if (isBlank(reasoningEffort)) {
      return switched;
    }
    final var effort = ReasoningEfforts.normalize(reasoningEffort);
    final var row = registry.setActiveEffort(userId, effort);
    final var on = UserModelRegistry.displayName(row);
    final var set =
        messages.get(
            "user-model-effort-set",
            on == null ? messages.get("user-model-default-name") : on,
            effort);
    return switched.isEmpty() ? set : switched + " " + set;
  }

  @Tool(
      name = "DeleteChatModel",
      description =
"""
Remove a chat model this user registered, and its stored token. If it was the one in use, their
conversations go back to the application's own model.
""")
  public String deleteChatModel(
      @ToolParam(description = "The registered model name") final String name,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (isBlank(name)) {
      return messages.get("user-model-add-incomplete");
    }
    final var wanted = name.trim();
    final var wasActive =
        registry.active(userId).map(config -> config.name().equals(wanted)).orElse(false);
    if (!registry.delete(userId, wanted)) {
      return messages.get("user-model-unknown", wanted, names(userId));
    }
    return wasActive
        ? messages.get("user-model-deleted-active", wanted)
        : messages.get("user-model-deleted", wanted);
  }

  /**
   * How the listing says what effort a model is set to, and says nothing where it is set to none:
   * an endpoint on the application's own setting has nothing of its own to report.
   */
  private String effortOf(final UserModelConfig config) {
    return config.reasoningEffort() == null
        ? ""
        : messages.get("user-model-line-effort", config.reasoningEffort());
  }

  private String names(final String userId) {
    final var configured =
        registry.list(userId).stream().map(UserModelConfig::name).collect(Collectors.joining(", "));
    return configured.isEmpty() ? "default" : configured + ", default";
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
