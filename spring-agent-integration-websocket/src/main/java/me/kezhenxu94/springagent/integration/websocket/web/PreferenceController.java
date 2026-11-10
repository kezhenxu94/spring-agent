package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.preferences.UserPreferences;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A person's own defaults, reached without a run — what {@code UserPreferenceTools} lets the model
 * change for them in a chat, offered here to a settings screen.
 *
 * <p>Only the scenario today, since that is all a preference holds. Memos travel with their slash
 * ({@code /kb}), because that is how a person types them and how a settings screen should show
 * them; the store keeps them without one, the way {@code AgentScenario.memoNames()} declares them,
 * so the slash is added and taken off here and nowhere else.
 *
 * <p>Read and write follow {@link UserPreferences}' own asymmetry: a read that cannot reach the
 * store answers with no preference rather than an error, and a write that cannot reach it fails and
 * says so — a person told "saved" must find it saved.
 */
@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferenceController {

  private final UserPreferences preferences;
  private final WebMessages messages;

  /**
   * @param scenario the memo to start runs in, with or without its slash; null or blank goes back
   *     to the deployment's default
   */
  public record Update(String scenario) {}

  @GetMapping
  public Map<String, Object> get(@AuthenticationPrincipal final OAuth2User principal) {
    final var user = ChatController.user(principal);
    return body(preferences.scenarioMemoFor(user.id()));
  }

  @PutMapping
  public Map<String, Object> put(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestBody(required = false) final Update update) {
    final var user = ChatController.user(principal);
    final var raw = update == null || update.scenario() == null ? "" : update.scenario().strip();
    final var word = raw.startsWith("/") ? raw.substring(1) : raw;
    try {
      return body(preferences.setScenario(user.id(), word));
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          messages.get(
              "preference-unknown-scenario", "/" + e.getMessage(), String.join(", ", choices())));
    }
  }

  private Map<String, Object> body(final Optional<String> stored) {
    final var out = new LinkedHashMap<String, Object>();
    out.put("scenario", stored.map(it -> "/" + it).orElse(null));
    out.put("scenarios", choices());
    return out;
  }

  private java.util.List<String> choices() {
    return preferences.selectableScenarios().stream().sorted().map(it -> "/" + it).toList();
  }
}
