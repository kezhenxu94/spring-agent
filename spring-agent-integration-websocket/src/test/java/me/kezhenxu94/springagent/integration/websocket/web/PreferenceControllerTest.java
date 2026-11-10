package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.ScenarioMemos;
import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import me.kezhenxu94.springagent.core.preferences.UserPreferences;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.server.ResponseStatusException;

class PreferenceControllerTest {

  private final Map<String, UserPreference> rows = new HashMap<>();
  private final UserPreferenceRepo repo = mock(UserPreferenceRepo.class);
  private final PreferenceController controller;

  PreferenceControllerTest() {
    when(repo.findById(anyString()))
        .thenAnswer(it -> Optional.ofNullable(rows.get(it.getArgument(0, String.class))));
    when(repo.save(any()))
        .thenAnswer(
            it -> {
              final UserPreference row = it.getArgument(0);
              rows.put(row.id(), row);
              return row;
            });
    controller =
        new PreferenceController(
            new UserPreferences(repo, new ScenarioMemos(List.of())),
            new WebMessages(
                new WebProperties(
                    null, null, null, Locale.ENGLISH, null, null, null, null, false, null)));
  }

  private static DefaultOAuth2User user(final String id) {
    return new DefaultOAuth2User(List.of(), Map.of("open_id", id, "name", id), "open_id");
  }

  @Test
  @DisplayName("nothing stored reads as no preference, with every memo offered, slashed and sorted")
  void unset() {
    final var body = controller.get(user("ou_me"));
    assertThat(body.get("scenario")).isNull();
    @SuppressWarnings("unchecked")
    final var scenarios = (List<String>) body.get("scenarios");
    assertThat(scenarios).contains("/kb", "/full", "/mini").isSorted();
  }

  @Test
  @DisplayName("a memo is stored with or without its slash, and read back with it")
  void setAndClear() {
    assertThat(controller.put(user("ou_me"), new PreferenceController.Update("/KB")))
        .containsEntry("scenario", "/kb");
    assertThat(controller.get(user("ou_me"))).containsEntry("scenario", "/kb");
    // Only the caller's own row.
    assertThat(controller.get(user("ou_other")).get("scenario")).isNull();

    assertThat(controller.put(user("ou_me"), new PreferenceController.Update("mini")))
        .containsEntry("scenario", "/mini");
    assertThat(controller.put(user("ou_me"), new PreferenceController.Update("")).get("scenario"))
        .isNull();
    assertThat(controller.put(user("ou_me"), new PreferenceController.Update(null)).get("scenario"))
        .isNull();
  }

  @Test
  @DisplayName("an unknown memo is a 400 that names it and what would have worked")
  void unknown() {
    assertThatThrownBy(
            () -> controller.put(user("ou_me"), new PreferenceController.Update("/nope")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400")
        .hasMessageContaining("/nope")
        .hasMessageContaining("/kb");
  }

  @Test
  @DisplayName("a store that cannot be read answers with no preference rather than an error")
  void readIsLenient() {
    when(repo.findById("ou_broken")).thenThrow(new IllegalStateException("down"));
    assertThat(controller.get(user("ou_broken")).get("scenario")).isNull();
  }
}
