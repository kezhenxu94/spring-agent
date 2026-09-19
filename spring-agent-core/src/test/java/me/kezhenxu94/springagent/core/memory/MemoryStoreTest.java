package me.kezhenxu94.springagent.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a memory file says about itself, and what happens to one that says nothing sensible.
 *
 * <p>The scoping, the refusals and the endpoints are {@code MemoryControllerTest}'s. This is about
 * the front matter reader, which is deliberately not a YAML parser, and about the two files that
 * are in a memories directory without being memories.
 */
class MemoryStoreTest {

  @TempDir Path root;

  private final MemoryStore store = new MemoryStore();

  @Test
  @DisplayName("the flat shape the memory prompt asks for is read")
  void readsTheFlatShape() throws Exception {
    write(
        "flat.md",
        """
        ---
        name: gpg-has-no-dirmngr
        description: keyserver commands fail on this machine
        type: reference
        ---
        Use curl against the HKP endpoint.
        """);

    assertThat(only())
        .satisfies(
            entry -> {
              assertThat(entry.name()).isEqualTo("gpg-has-no-dirmngr");
              assertThat(entry.description()).isEqualTo("keyserver commands fail on this machine");
              assertThat(entry.type()).isEqualTo("reference");
            });
  }

  @Test
  @DisplayName("a nested metadata block is read too, because both shapes are written in practice")
  void readsTheNestedShape() throws Exception {
    // Indentation is ignored rather than interpreted. A real parser would tell these two apart and
    // would then have to be told which one this runtime means — while the only thing any reader
    // wants from either is the same three values.
    write(
        "nested.md",
        """
        ---
        name: testcontainers-flakes
        description: local contention, not your change
        metadata:
          type: feedback
        ---
        Body.
        """);

    assertThat(only())
        .satisfies(
            entry -> {
              assertThat(entry.name()).isEqualTo("testcontainers-flakes");
              assertThat(entry.type()).isEqualTo("feedback");
            });
  }

  @Test
  @DisplayName("quotes around a value are not part of it")
  void stripsQuotes() throws Exception {
    write("quoted.md", "---\nname: \"web-ui\"\ndescription: 'compactness wins'\n---\nx");

    assertThat(only().name()).isEqualTo("web-ui");
    assertThat(only().description()).isEqualTo("compactness wins");
  }

  @Test
  @DisplayName("the first answer wins, so a nested block cannot overwrite the top level")
  void firstOccurrenceWins() throws Exception {
    write("twice.md", "---\ntype: user\nmetadata:\n  type: project\n---\nx");

    assertThat(only().type()).isEqualTo("user");
  }

  @Test
  @DisplayName("a file whose front matter never closes is read as far as it goes, not refused")
  void toleratesAnUnclosedFence() throws Exception {
    write("open.md", "---\nname: half-written\ndescription: somebody stopped typing\n");

    assertThat(only().name()).isEqualTo("half-written");
  }

  @Test
  @DisplayName("prose that merely contains a colon is not mistaken for front matter")
  void ignoresBodyText() throws Exception {
    write("prose.md", "name: not front matter\n\nJust a note.");

    assertThat(only().name()).isEmpty();
    // Still listed: the path is the identity, and a memory the agent wrote badly has to stay
    // visible to whoever would fix it.
    assertThat(only().path()).isEqualTo("prose.md");
  }

  @Test
  @DisplayName("MEMORY.md is marked as the index and sorts before every memory")
  void indexComesFirst() throws Exception {
    write("aaa.md", "first alphabetically");
    write(MemoryStore.INDEX, "- [aaa](aaa.md)");

    final var entries = store.list(root);

    assertThat(entries).extracting(MemoryEntry::path).containsExactly(MemoryStore.INDEX, "aaa.md");
    assertThat(entries.get(0).index()).isTrue();
    assertThat(entries.get(1).index()).isFalse();
  }

  @Test
  @DisplayName("a memories folder nobody has written to is empty rather than an error")
  void absentRootIsEmpty() {
    assertThat(store.list(root.resolve("never-written"))).isEmpty();
  }

  @Test
  @DisplayName("something that is not text says so instead of being drawn as text")
  void reportsBinary() throws Exception {
    Files.write(root.resolve("blob.bin"), new byte[] {1, 2, 0, 3});

    final var file = store.read(root, "blob.bin").orElseThrow();

    assertThat(file.binary()).isTrue();
    assertThat(file.text()).isNull();
    assertThat(file.tooLarge()).isFalse();
  }

  @Test
  @DisplayName("something too big to show says which of the two reasons it is")
  void reportsTooLarge() throws Exception {
    Files.write(root.resolve("huge.md"), new byte[(int) MemoryStore.MAX_TEXT_BYTES + 1]);

    final var file = store.read(root, "huge.md").orElseThrow();

    // Both refuse the text and they are not the same news: one will never be text, the other is
    // text this declines to carry, and a reader told only "cannot show this" tries the wrong fix.
    assertThat(file.tooLarge()).isTrue();
    assertThat(file.binary()).isFalse();
  }

  @Test
  @DisplayName("writing brings the folders it needs, and writing again replaces the file")
  void writesAndReplaces() {
    store.write(root, "projects/one.md", "first");
    assertThat(root.resolve("projects/one.md")).hasContent("first");

    store.write(root, "projects/one.md", "second");
    assertThat(root.resolve("projects/one.md")).hasContent("second");
  }

  @Test
  @DisplayName("deleting answers whether there was anything there")
  void deleteReportsWhetherItDidAnything() throws Exception {
    write("note.md", "x");

    assertThat(store.delete(root, "note.md")).isTrue();
    assertThat(store.delete(root, "note.md")).isFalse();
  }

  @Test
  @DisplayName("a directory is not a row, so it is not something this can delete")
  void refusesToDeleteADirectory() throws Exception {
    Files.createDirectories(root.resolve("projects"));

    assertThat(store.delete(root, "projects")).isFalse();
    assertThat(root.resolve("projects")).exists();
  }

  @Test
  @DisplayName("a path climbing out of the root is refused rather than resolved")
  void refusesEscapes() {
    assertThatThrownBy(() -> store.read(root, "../outside.md"))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(() -> store.write(root, "../outside.md", "x"))
        .isInstanceOf(SecurityException.class);
  }

  private MemoryEntry only() {
    return store.list(root).get(0);
  }

  private void write(final String path, final String text) throws Exception {
    final var file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }
}
