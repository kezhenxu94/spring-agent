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
   *     to go back to the application's model, otherwise the name of one of their own
   */
  public String handle(final String userId, final String argument) {
    final var arg = argument == null ? "" : argument.trim();
    if (arg.isEmpty()) {
      return status(userId);
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

  /** What is in use, and what else could be. */
  private String status(final String userId) {
    final var active =
        registry
            .active(userId)
            .map(UserModelConfig::name)
            .orElse(messages.get("user-model-default-name"));
    final var configured = registry.list(userId);
    if (configured.isEmpty()) {
      return messages.get("user-model-status-none", active);
    }
    return messages.get("user-model-status", active, names(userId));
  }

  private String names(final String userId) {
    final var configured =
        registry.list(userId).stream().map(UserModelConfig::name).collect(Collectors.joining(", "));
    return configured.isEmpty() ? DEFAULT : configured + ", " + DEFAULT;
  }
}
