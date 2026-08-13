package me.kezhenxu94.springagent.core.tools.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ShellCredential;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The {@link ShellCredentialStore} contract, and the encryption that is the point of this one. */
class DatabaseCredentialStoreTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final InMemoryRepo repo = new InMemoryRepo();
  private final DatabaseCredentialStore store = new DatabaseCredentialStore(repo, KEY);

  @Test
  @DisplayName("a stored credential comes back out intact")
  void roundTrips() {
    store.put("u1", "GITHUB_TOKEN", "ghp_secret");

    assertThat(store.resolve("u1")).containsExactly(Map.entry("GITHUB_TOKEN", "ghp_secret"));
  }

  @Test
  @DisplayName("what reaches the database is not the plaintext")
  void storesCiphertext() {
    store.put("u1", "GITHUB_TOKEN", "ghp_secret");

    assertThat(repo.rows.values())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.value()).isNotNull().doesNotContain("ghp_secret");
              assertThat(row.updatedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("the same value encrypts differently every time, so rows do not leak equality")
  void usesAFreshNonce() {
    store.put("u1", "A", "same");
    store.put("u1", "B", "same");

    final var values = repo.rows.values().stream().map(ShellCredential::value).toList();
    assertThat(values).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("listing names never exposes a value")
  void listsNamesOnly() {
    store.put("u1", "GITHUB_TOKEN", "ghp_secret");

    final var entries = store.list("u1");

    assertThat(entries)
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.name()).isEqualTo("GITHUB_TOKEN");
              assertThat(entry.updatedAt()).isNotNull();
            });
    assertThat(entries.toString()).doesNotContain("ghp_secret");
  }

  @Test
  @DisplayName("one user cannot see another's credentials")
  void isolatesUsers() {
    store.put("u1", "TOKEN", "mine");
    store.put("u2", "TOKEN", "yours");

    assertThat(store.resolve("u1")).containsExactly(Map.entry("TOKEN", "mine"));
    assertThat(store.resolve("u2")).containsExactly(Map.entry("TOKEN", "yours"));
  }

  @Test
  @DisplayName("storing the same name twice replaces rather than duplicates")
  void replacesOnRewrite() {
    store.put("u1", "TOKEN", "first");
    store.put("u1", "TOKEN", "second");

    assertThat(repo.rows).hasSize(1);
    assertThat(store.resolve("u1")).containsExactly(Map.entry("TOKEN", "second"));
  }

  @Test
  @DisplayName("delete reports whether there was anything to delete")
  void deleteReportsWhetherItFoundAnything() {
    store.put("u1", "TOKEN", "value");

    assertThat(store.delete("u1", "TOKEN")).isTrue();
    assertThat(store.delete("u1", "TOKEN")).isFalse();
    assertThat(store.resolve("u1")).isEmpty();
  }

  @Test
  @DisplayName("a changed key surfaces as an error rather than as a sandbox with no credentials")
  void refusesToDecryptUnderAnotherKey() {
    store.put("u1", "TOKEN", "value");

    final var otherKey = new byte[32];
    otherKey[0] = 1;
    final var rotated =
        new DatabaseCredentialStore(repo, Base64.getEncoder().encodeToString(otherKey));

    assertThatThrownBy(() -> rotated.resolve("u1"))
        .isInstanceOf(ShellCredentialStore.CredentialStoreException.class)
        .hasMessageContaining("TOKEN");
  }

  @Test
  @DisplayName("a missing or malformed key is refused at construction, not at first use")
  void requiresAUsableKey() {
    assertThatThrownBy(() -> new DatabaseCredentialStore(repo, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DatabaseCredentialStore(repo, "not base64!"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DatabaseCredentialStore(repo, Base64.getEncoder().encodeToString(new byte[7])))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("16, 24 or 32");
  }

  /** Enough of the repository to exercise the store; keyed the way the entity's id is built. */
  private static final class InMemoryRepo implements ShellCredentialRepo {

    private final Map<String, ShellCredential> rows = new LinkedHashMap<>();

    @Override
    public ShellCredential save(final ShellCredential credential) {
      rows.put(credential.id(), credential);
      return credential;
    }

    @Override
    public List<ShellCredential> findByOwnerId(final String ownerId) {
      return rows.values().stream().filter(row -> row.ownerId().equals(ownerId)).toList();
    }

    @Override
    public Optional<ShellCredential> findByOwnerIdAndName(final String ownerId, final String name) {
      return Optional.ofNullable(rows.get(ShellCredential.idFor(ownerId, name)));
    }

    @Override
    public void deleteByOwnerIdAndName(final String ownerId, final String name) {
      rows.remove(ShellCredential.idFor(ownerId, name));
    }
  }
}
