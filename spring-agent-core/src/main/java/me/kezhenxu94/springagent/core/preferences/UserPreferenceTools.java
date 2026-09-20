package me.kezhenxu94.springagent.core.preferences;

import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Lets a person change what this runtime decides for them, by asking rather than by finding a
 * settings page.
 *
 * <p>Whose preferences are being changed is never a parameter. It comes from {@link ToolContexts}
 * like every other identity here, which {@code SpringAgent.toolContextFor} overwrites on the way in
 * — so neither the model nor anybody talking to it can name somebody else's row. That is the whole
 * of the access control, and it is the reason there is no {@code userId} argument to be tempted by.
 *
 * <p>Every answer is localized prose rather than a code, because the model reads it and then says
 * it back to the person. A refusal in particular has to name what is wrong <em>and</em> what to
 * pass instead, or the model's next attempt is a guess.
 */
@AgentTool
@Component
@RequiredArgsConstructor
public class UserPreferenceTools {

  private final UserPreferences preferences;
  private final CoreMessages messages;

  @Tool(
      name = "MyPreferences",
      description = "What this person has asked me to do by default, and what else they could set.")
  public String myPreferences(final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    final var scenario = preferences.scenarioMemoFor(userId);
    final var choices = String.join(", ", preferences.selectableScenarios());
    return scenario
        .map(memo -> messages.get("preference-scenario-is", memo, choices))
        .orElseGet(() -> messages.get("preference-scenario-unset", choices));
  }

  @Tool(
      name = "SetMyPreference",
      description =
          "Set what I do by default for this person. Only they can change their own settings.")
  public String setMyPreference(
      @ToolParam(
              description =
                  "Which setting to change. Only 'scenario' exists today: which kind of run their"
                      + " messages start as when they do not say otherwise.")
          final String name,
      @ToolParam(
              description =
                  "What to set it to. For 'scenario', one of the words a message can select a run"
                      + " with — kb, mini, full — without the leading slash. Pass an empty value to"
                      + " clear the setting and go back to the default.")
          final String value,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    if (!"scenario".equalsIgnoreCase(name == null ? "" : name.strip())) {
      return messages.get("preference-no-such-setting", name, "scenario");
    }
    final var choices = String.join(", ", preferences.selectableScenarios());
    try {
      return preferences
          .setScenario(userId, value)
          .map(memo -> messages.get("preference-scenario-set", memo))
          .orElseGet(() -> messages.get("preference-scenario-cleared"));
    } catch (IllegalArgumentException e) {
      // The word itself, not the exception's message: what the person typed is what they have to
      // be told is wrong, beside the list of what would have worked.
      return messages.get("preference-no-such-scenario", e.getMessage(), choices);
    }
  }

  @Tool(
      name = "ResetMyPreferences",
      description = "Forget everything this person has set, putting every default back.")
  public String resetMyPreferences(final ToolContext context) {
    preferences.reset(ToolContexts.require(context, ToolContexts.USER_ID));
    return messages.get("preference-reset");
  }
}
