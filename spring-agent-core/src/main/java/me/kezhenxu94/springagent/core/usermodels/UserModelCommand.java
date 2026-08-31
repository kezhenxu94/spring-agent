package me.kezhenxu94.springagent.core.usermodels;

import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;

/**
 * The {@code /model} command, written once for every surface that offers it.
 *
 * <p>This is the way out of a model that does not work, and it is the reason it exists: a user who
 * registers an endpoint with a wrong token or a name their gateway does not know would otherwise
 * have to ask the agent to fix it, through the very model that is broken. Nothing here goes near an
 * LLM — it is a repository read and at most two writes — so it answers whatever the state of the
 * user's endpoint.
 *
 * <p>Surfaces call {@link #handle} with the text after {@code /model} and print what comes back.
 * Keeping the parsing here rather than in each surface is what stops the command from meaning three
 * slightly different things.
 */
@RequiredArgsConstructor
public class UserModelCommand {

  /** The word that puts a user back on the application's own model. */
  private static final String DEFAULT = "default";

  private final UserModelRegistry registry;
  private final CoreMessages messages;

  /**
   * Runs the command and returns what to show the user.
   *
   * @param userId who is asking; their own models are the only ones they can see or switch to
   * @param argument what followed {@code /model}: empty to report what is in use, {@code default}
   *     to go back to the application's model, the name of one of their own to switch to it, or
   *     that name and a reasoning effort to change how hard it thinks
   */
  public String handle(final String userId, final String argument) {
    final var arg = argument == null ? "" : argument.trim();
    if (arg.isEmpty()) {
      return status(userId);
    }
    // A name can hold no whitespace (see UserModelRegistry.validName), so a second word is never
    // part of one and can only be an effort. Splitting here rather than in each surface is what
    // keeps the terminal and the chat forms setting the same thing.
    final var words = arg.split("\\s+", 2);
    if (words.length == 2) {
      return DEFAULT.equalsIgnoreCase(words[0])
          ? setBuiltinEffort(userId, words[1].trim())
          : setEffort(userId, words[0], words[1].trim());
    }
    if (DEFAULT.equalsIgnoreCase(arg)) {
      registry.useDefault(userId);
      return messages.get("user-model-now-default");
    }
    if (!registry.activate(userId, arg)) {
      // Deliberately does nothing else: naming a model that is not there is a typo far more often
      // than it is a request to create one, and creating an endpoint needs a URL and a token that
      // this command has no way to ask for.
      return messages.get("user-model-unknown", arg, names(userId));
    }
    return messages.get("user-model-now", arg);
  }

  /**
   * Puts the user on the application's own model and says how hard it should think.
   *
   * <p>Both at once because there is no other way to name the built-in model here: it is the model
   * a user has when they have chosen nothing, so asking about its reasoning effort is asking to be
   * on it.
   */
  private String setBuiltinEffort(final String userId, final String effort) {
    if (!ReasoningEfforts.valid(effort)) {
      return messages.get("user-model-bad-effort", effort, ReasoningEfforts.listed());
    }
    final var wanted = ReasoningEfforts.normalize(effort);
    registry.useDefault(userId);
    registry.setActiveEffort(userId, wanted);
    return messages.get("user-model-effort-set", messages.get("user-model-default-name"), wanted);
  }

  /**
   * Changes how hard one of the user's own models thinks.
   *
   * <p>Does not switch onto it: somebody turning reasoning up on a model they are not using has not
   * asked to start using it, and the two acts staying separate is the rule registration follows
   * too.
   */
  private String setEffort(final String userId, final String name, final String effort) {
    if (!ReasoningEfforts.valid(effort)) {
      return messages.get("user-model-bad-effort", effort, ReasoningEfforts.listed());
    }
    final var wanted = ReasoningEfforts.normalize(effort);
    if (!registry.setEffort(userId, name, wanted)) {
      return messages.get("user-model-unknown", name, names(userId));
    }
    return messages.get("user-model-effort-set", name, wanted);
  }

  /** What is in use, and what else could be. */
  private String status(final String userId) {
    final var active =
        registry
            .active(userId)
            .map(row -> nameOf(row) + effortOf(row))
            .orElse(messages.get("user-model-default-name"));
    final var configured = registry.list(userId);
    if (configured.isEmpty()) {
      return messages.get("user-model-status-none", active);
    }
    return messages.get("user-model-status", active, names(userId), ReasoningEfforts.listed());
  }

  /**
   * What to call the model in use: the application's model has no name of its own in the registry,
   * only a localized word here.
   */
  private String nameOf(final UserModelConfig config) {
    final var name = UserModelRegistry.displayName(config);
    return name == null ? messages.get("user-model-default-name") : name;
  }

  /** What the model in use is set to think at, and nothing where it is on the deployment's own. */
  private String effortOf(final UserModelConfig config) {
    return config.reasoningEffort() == null
        ? ""
        : messages.get("user-model-line-effort", config.reasoningEffort());
  }

  private String names(final String userId) {
    final var configured =
        registry.list(userId).stream().map(UserModelConfig::name).collect(Collectors.joining(", "));
    return configured.isEmpty() ? DEFAULT : configured + ", " + DEFAULT;
  }
}
