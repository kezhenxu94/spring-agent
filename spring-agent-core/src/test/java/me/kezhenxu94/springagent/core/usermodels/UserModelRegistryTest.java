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
    final var saved = registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret");

    assertThat(registry.tokenOf(saved)).isEqualTo("sk-secret");
  }

  @Test
  @DisplayName("the token does not reach the database in the clear")
  void sealsTheToken() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret");

    assertThat(repo.rows.values())
        .singleElement()
        .satisfies(row -> assertThat(row.apiKeyCipher()).doesNotContain("sk-secret"));
  }

  @Test
  @DisplayName("a newly saved endpoint is not switched to")
  void savingDoesNotActivate() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "sk-secret");

    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("re-saving the one in use keeps the user on it")
  void resavingKeepsActivation() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "old");
    registry.activate("u1", "kimi");

    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "rotated");

    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("kimi");
    assertThat(registry.tokenOf(registry.active("u1").orElseThrow())).isEqualTo("rotated");
  }

  @Test
  @DisplayName("switching leaves exactly one endpoint activated")
  void switchingIsExclusive() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a");
    registry.save("u1", "glm", "https://glm/v1", "glm-4", "b");

    registry.activate("u1", "kimi");
    registry.activate("u1", "glm");

    assertThat(repo.rows.values().stream().filter(UserModelConfig::activated)).hasSize(1);
    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("glm");
  }

  @Test
  @DisplayName("switching clears the old endpoint before setting the new one")
  void clearsBeforeSetting() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a");
    registry.save("u1", "glm", "https://glm/v1", "glm-4", "b");
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
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a");
    registry.activate("u1", "kimi");

    registry.useDefault("u1");

    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("deleting the one in use needs no separate cleanup")
  void deletingActive() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a");
    registry.activate("u1", "kimi");

    assertThat(registry.delete("u1", "kimi")).isTrue();
    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("switching to a name the user does not have changes nothing")
  void unknownName() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a");
    registry.activate("u1", "kimi");

    assertThat(registry.activate("u1", "nope")).isFalse();
    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("kimi");
  }

  @Test
  @DisplayName("one user cannot see or switch another's endpoints")
  void ownerScoped() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "a");

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
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t");
    registry.activateBuiltin("u1", "gpt-4o");

    assertThat(registry.list("u1")).map(UserModelConfig::name).containsExactly("kimi");
  }

  @Test
  @DisplayName("switching between a built-in model and an endpoint stays exclusive")
  void builtinIsExclusive() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t");
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
  @DisplayName("the ceiling counts endpoints, and replacing one is not adding one")
  void ceiling() {
    registry.save("u1", "a", "u", "m", "t");
    registry.save("u1", "b", "u", "m", "t");
    registry.save("u1", "c", "u", "m", "t");

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
