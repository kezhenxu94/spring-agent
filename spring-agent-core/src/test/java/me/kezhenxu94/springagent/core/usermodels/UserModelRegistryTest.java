package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Owner scoping, the sealing of tokens, and the write order that makes switching safe. */
class UserModelRegistryTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final InMemoryRepo repo = new InMemoryRepo();
  private final UserModelRegistry registry =
      new UserModelRegistry(repo, new AesGcmSealer(KEY, "test"), 3);

  @Test
  @DisplayName("a saved endpoint comes back with its token intact")
  void roundTrips() {
    final var saved = registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret", null);

    assertThat(registry.tokenOf(saved)).isEqualTo("sk-secret");
  }

  @Test
  @DisplayName("the token does not reach the database in the clear")
  void sealsTheToken() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret", null);

    assertThat(repo.rows.values())
        .singleElement()
        .satisfies(row -> assertThat(row.apiKeyCipher()).doesNotContain("sk-secret"));
  }

  @Test
  @DisplayName("a newly saved endpoint is not switched to")
  void savingDoesNotActivate() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret", null);

    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("re-saving the one in use keeps the user on it")
  void resavingKeepsActivation() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "old", null);
    registry.activate("u1", "kimi");

    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "rotated", null);

    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("kimi");
    assertThat(registry.tokenOf(registry.active("u1").orElseThrow())).isEqualTo("rotated");
  }

  @Test
  @DisplayName("switching leaves exactly one endpoint activated")
  void switchingIsExclusive() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a", null);
    registry.save("u1", "glm", "https://glm/v1", "glm-4", "b", null);

    registry.activate("u1", "kimi");
    registry.activate("u1", "glm");

    assertThat(repo.rows.values().stream().filter(UserModelConfig::activated)).hasSize(1);
    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("glm");
  }

  @Test
  @DisplayName("switching clears the old endpoint before setting the new one")
  void clearsBeforeSetting() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a", null);
    registry.save("u1", "glm", "https://glm/v1", "glm-4", "b", null);
    registry.activate("u1", "kimi");

    // The failure this ordering exists for: a switch that dies partway must never leave two rows
    // claiming to be in use, because nothing could then decide between them. Dying after the
    // clear leaves none, which reads as the application's own model.
    repo.failNextSaveOf("glm");
    try {
      registry.activate("u1", "glm");
    } catch (RuntimeException expected) {
      // the write we arranged to fail
    }

    assertThat(repo.rows.values().stream().filter(UserModelConfig::activated)).isEmpty();
    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("going back to the default leaves nothing activated")
  void useDefault() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a", null);
    registry.activate("u1", "kimi");

    registry.useDefault("u1");

    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("deleting the one in use needs no separate cleanup")
  void deletingActive() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a", null);
    registry.activate("u1", "kimi");

    assertThat(registry.delete("u1", "kimi")).isTrue();
    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("switching to a name the user does not have changes nothing")
  void unknownName() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a", null);
    registry.activate("u1", "kimi");

    assertThat(registry.activate("u1", "nope")).isFalse();
    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("kimi");
  }

  @Test
  @DisplayName("one user cannot see or switch another's endpoints")
  void ownerScoped() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a", null);

    assertThat(registry.list("u2")).isEmpty();
    assertThat(registry.activate("u2", "kimi")).isFalse();
    assertThat(registry.delete("u2", "kimi")).isFalse();
  }

  @Test
  @DisplayName("choosing one of the application's own models stores nothing secret")
  void builtinCarriesNoCredentials() {
    registry.activateBuiltin("u1", "gpt-4o");

    final var active = registry.active("u1").orElseThrow();
    assertThat(active.model()).isEqualTo("gpt-4o");
    // No base URL and no token: the client is built on the application's endpoint instead, so its
    // key is never copied per user into a table.
    assertThat(active.baseUrl()).isNull();
    assertThat(active.apiKeyCipher()).isNull();
    assertThat(registry.tokenOf(active)).isNull();
  }

  @Test
  @DisplayName("a built-in choice is not listed as an endpoint the user registered")
  void builtinNotListed() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    registry.activateBuiltin("u1", "gpt-4o");

    assertThat(registry.list("u1")).map(UserModelConfig::name).containsExactly("kimi");
  }

  @Test
  @DisplayName("switching between a built-in model and an endpoint stays exclusive")
  void builtinIsExclusive() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    registry.activate("u1", "kimi");

    registry.activateBuiltin("u1", "gpt-4o");

    assertThat(repo.rows.values().stream().filter(UserModelConfig::activated)).hasSize(1);
    assertThat(registry.active("u1")).map(UserModelConfig::model).contains("gpt-4o");

    registry.useDefault("u1");
    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("a name cannot impersonate a built-in choice")
  void nameValidation() {
    assertThat(UserModelRegistry.validName("@gpt-4o")).isFalse();
    assertThat(UserModelRegistry.validName("has space")).isFalse();
    assertThat(UserModelRegistry.validName("")).isFalse();
    assertThat(UserModelRegistry.validName("kimi-k2.1:free")).isTrue();
  }

  @Test
  @DisplayName("an effort is stored as it will be sent, whatever case it was given in")
  void storesEffort() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", "HIGH");

    assertThat(registry.find("u1", "kimi")).map(UserModelConfig::reasoningEffort).contains("high");
  }

  @Test
  @DisplayName("changing the effort keeps the token and whether the model is in use")
  void setEffortKeepsEverythingElse() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret", "low");
    registry.activate("u1", "kimi");

    assertThat(registry.setEffort("u1", "kimi", "max")).isTrue();

    final var row = registry.find("u1", "kimi").orElseThrow();
    assertThat(row.reasoningEffort()).isEqualTo("max");
    assertThat(row.activated()).isTrue();
    // The point of the method: the token is sealed and never shown again, so an edit that lost it
    // would leave the user unable to change the effort at all.
    assertThat(registry.tokenOf(row)).isEqualTo("sk-secret");
  }

  @Test
  @DisplayName("clearing the effort goes back to the application's setting")
  void setEffortClears() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", "high");

    assertThat(registry.setEffort("u1", "kimi", null)).isTrue();

    assertThat(registry.find("u1", "kimi")).map(UserModelConfig::reasoningEffort).isEmpty();
  }

  @Test
  @DisplayName("an effort cannot be set on a model somebody else owns, or on none at all")
  void setEffortScoped() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);

    assertThat(registry.setEffort("u2", "kimi", "high")).isFalse();
    assertThat(registry.setEffort("u1", "absent", "high")).isFalse();
    assertThat(registry.find("u1", "kimi")).map(UserModelConfig::reasoningEffort).isEmpty();
  }

  @Test
  @DisplayName("an effort chosen while on the built-in model does not pin the model")
  void defaultRowNamesNoModel() {
    final var row = registry.setActiveEffort("u1", "high");

    assertThat(row.name()).isEqualTo(UserModelRegistry.DEFAULT_ROW);
    assertThat(row.model()).isNull();
    assertThat(row.baseUrl()).isNull();
    assertThat(row.activated()).isTrue();
    assertThat(UserModelRegistry.displayName(row)).isNull();
  }

  @Test
  @DisplayName("going back to the built-in model keeps how hard it was asked to think")
  void defaultKeepsItsEffort() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    registry.setActiveEffort("u1", "high");
    registry.activate("u1", "kimi");

    registry.useDefault("u1");

    assertThat(registry.active("u1")).map(UserModelConfig::reasoningEffort).contains("high");
  }

  @Test
  @DisplayName("with nothing ever chosen, the built-in model is still no row at all")
  void defaultWithNothingStored() {
    registry.useDefault("u1");

    assertThat(registry.active("u1")).isEmpty();
    assertThat(repo.rows).isEmpty();
  }

  @Test
  @DisplayName("an effort set while on a built-in model stays with it across a switch")
  void builtinKeepsItsEffort() {
    registry.activateBuiltin("u1", "gpt-5");
    registry.setActiveEffort("u1", "max");

    registry.useDefault("u1");
    registry.activateBuiltin("u1", "gpt-5");

    assertThat(registry.active("u1")).map(UserModelConfig::reasoningEffort).contains("max");
  }

  @Test
  @DisplayName("models picked off the application's list do not fill the endpoint allowance")
  void builtinRowsDoNotCountTowardsTheCeiling() {
    registry.activateBuiltin("u1", "one");
    registry.activateBuiltin("u1", "two");
    registry.activateBuiltin("u1", "three");
    registry.setActiveEffort("u1", "high");

    assertThat(registry.full("u1", "kimi")).isFalse();
  }

  @Test
  @DisplayName("the ceiling counts endpoints, and replacing one is not adding one")
  void ceiling() {
    registry.save("u1", "a", "u", "m", "t", null);
    registry.save("u1", "b", "u", "m", "t", null);
    registry.save("u1", "c", "u", "m", "t", null);

    assertThat(registry.full("u1", "d")).isTrue();
    assertThat(registry.full("u1", "a")).isFalse();
  }

  /** Enough of the contract to exercise the registry, with one arranged failure. */
  private static final class InMemoryRepo implements UserModelConfigRepo {
    private final Map<String, UserModelConfig> rows = new LinkedHashMap<>();
    private String failSaveOf;

    void failNextSaveOf(final String name) {
      this.failSaveOf = name;
    }

    @Override
    public UserModelConfig save(final UserModelConfig config) {
      if (config.name().equals(failSaveOf)) {
        failSaveOf = null;
        throw new IllegalStateException("arranged failure");
      }
      rows.put(config.id(), config);
      return config;
    }

    @Override
    public List<UserModelConfig> findByOwnerId(final String ownerId) {
      return rows.values().stream().filter(row -> row.ownerId().equals(ownerId)).toList();
    }

    @Override
    public Optional<UserModelConfig> findByOwnerIdAndName(final String ownerId, final String name) {
      return Optional.ofNullable(rows.get(UserModelConfig.idFor(ownerId, name)));
    }

    @Override
    public void deleteByOwnerIdAndName(final String ownerId, final String name) {
      rows.remove(UserModelConfig.idFor(ownerId, name));
    }
  }
}
