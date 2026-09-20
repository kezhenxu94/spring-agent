package me.kezhenxu94.springagent.core.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.ScenarioMemos;
import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserPreferencesTest {

  private final InMemoryRepo repo = new InMemoryRepo();
  private final UserPreferences preferences =
      new UserPreferences(repo, new ScenarioMemos(List.of()));

  @Test
  void nobodyHasSetAnythingUntilTheyDo() {
    assertThat(preferences.scenarioFor("ou_1")).isEqualTo(BuiltInScenarios.CHAT);
    assertThat(preferences.scenarioMemoFor("ou_1")).isEmpty();
  }

  @Test
  void whatIsStoredIsWhatIsResolved() {
    preferences.setScenario("ou_1", "kb");

    assertThat(preferences.scenarioFor("ou_1")).isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
    // The word, not the enum: it is what the person typed and what a tool says back to them.
    assertThat(preferences.scenarioMemoFor("ou_1")).contains("kb");
  }

  @Test
  @DisplayName("a word is stored lower case, so the casing a person typed does not decide")
  void caseIsNotPartOfWhatIsStored() {
    preferences.setScenario("ou_1", "  KB  ");
    assertThat(preferences.scenarioMemoFor("ou_1")).contains("kb");
  }

  @Test
  @DisplayName("a word no scenario answers to is refused rather than stored")
  void anUnknownWordIsRefused() {
    // Storing it would fall back silently on every run afterwards, and the person would be told it
    // worked. The word itself is the message, because it is what they have to be told is wrong.
    assertThatThrownBy(() -> preferences.setScenario("ou_1", "wizard"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("wizard");
    assertThat(repo.rows).isEmpty();
  }

  @Test
  void clearingGoesBackToAnOrdinaryChat() {
    preferences.setScenario("ou_1", "kb");
    assertThat(preferences.setScenario("ou_1", "  ")).isEmpty();
    assertThat(preferences.scenarioFor("ou_1")).isEqualTo(BuiltInScenarios.CHAT);
  }

  @Test
  void resettingForgetsTheRow() {
    preferences.setScenario("ou_1", "mini");
    preferences.reset("ou_1");
    assertThat(repo.rows).isEmpty();
    assertThat(preferences.scenarioFor("ou_1")).isEqualTo(BuiltInScenarios.CHAT);
  }

  @Test
  @DisplayName("a stored word this deployment no longer knows falls back rather than failing")
  void aStaleWordFallsBack() {
    // A scenario can be removed, or the bean that contributed one can stop being registered, and
    // the row outlives both. The run still has to happen.
    repo.rows.put("ou_1", UserPreference.builder().id("ou_1").scenario("wizard").build());

    assertThat(preferences.scenarioFor("ou_1")).isEqualTo(BuiltInScenarios.CHAT);
    // But the tool still reports what is actually in the row, so a person can see what to fix.
    assertThat(preferences.scenarioMemoFor("ou_1")).contains("wizard");
  }

  @Test
  @DisplayName("a store that cannot be read costs a preference, never the run")
  void anUnreachableStoreFallsBack() {
    final var broken =
        new UserPreferences(
            new UserPreferenceRepo() {
              @Override
              public UserPreference save(final UserPreference preference) {
                throw new IllegalStateException("down");
              }

              @Override
              public Optional<UserPreference> findById(final String id) {
                throw new IllegalStateException("down");
              }

              @Override
              public void deleteById(final String id) {}
            },
            new ScenarioMemos(List.of()));

    assertThat(broken.scenarioFor("ou_1")).isEqualTo(BuiltInScenarios.CHAT);
  }

  @Test
  void anUnattendedRunHasNobodyToHaveAPreference() {
    assertThat(preferences.scenarioFor(null)).isEqualTo(BuiltInScenarios.CHAT);
    assertThat(preferences.scenarioFor("")).isEqualTo(BuiltInScenarios.CHAT);
  }

  @Test
  void theChoicesOfferedAreTheMemosThatExist() {
    assertThat(preferences.selectableScenarios())
        .containsExactlyInAnyOrder(
            "kb", "knowledge-base", "knowledge_base", "one-off", "one_off", "mini", "full");
  }

  private static final class InMemoryRepo implements UserPreferenceRepo {
    private final Map<String, UserPreference> rows = new HashMap<>();

    @Override
    public UserPreference save(final UserPreference preference) {
      rows.put(preference.id(), preference);
      return preference;
    }

    @Override
    public Optional<UserPreference> findById(final String id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public void deleteById(final String id) {
      rows.remove(id);
    }
  }
}
