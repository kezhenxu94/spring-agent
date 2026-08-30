package me.kezhenxu94.springagent.integration.feishu.greeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/** How the notes on disk become the notes the cards are built from. */
class FeishuUpdatesTest {

  private static FeishuMessages messagesIn(final Locale locale) {
    return new FeishuMessages(
        new FeishuProperties(null, null, null, null, null, null, null, locale, null, null, null));
  }

  private static FeishuUpdates updatesIn(final Path directory, final Locale locale) {
    final var location = "file:" + directory.toAbsolutePath() + "/";
    return new FeishuUpdates(
        new DefaultResourceLoader(), messagesIn(locale), location + "welcome.md", location);
  }

  private static void write(final Path file, final String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("the notes this repository ships load, and the welcome offers something to tap")
  void loadsWhatIsShipped() {
    final var updates =
        new FeishuUpdates(
            new DefaultResourceLoader(),
            messagesIn(Locale.ENGLISH),
            "classpath:/feishu/welcome.md",
            "classpath:/feishu/updates/");

    assertThat(updates.current()).isPositive();
    assertThat(updates.welcome().title()).isNotBlank();
    assertThat(updates.welcome().suggestions()).isNotEmpty();
    assertThat(updates.offers(updates.welcome().suggestions().getFirst())).isTrue();
    assertThat(updates.offers("rm -rf /")).isFalse();
  }

  @Test
  @DisplayName("counting up stops at the first number with no note, rather than skipping it")
  void stopsAtAGap(@TempDir final Path directory) throws IOException {
    write(directory.resolve("welcome.md"), "---\ntitle: hi\n---\nbody");
    write(directory.resolve("1.md"), "---\ntitle: one\n---\nfirst");
    write(directory.resolve("2.md"), "---\ntitle: two\n---\nsecond");
    // 3 is missing, so 4 is out of reach even though it is there.
    write(directory.resolve("4.md"), "---\ntitle: four\n---\nfourth");

    final var updates = updatesIn(directory, Locale.ENGLISH);

    assertThat(updates.current()).isEqualTo(2);
    assertThat(updates.since(0).stream().map(FeishuUpdates.Note::title))
        .containsExactly("one", "two");
  }

  @Test
  @DisplayName("only the notes above the version a person has read come back")
  void reportsWhatIsUnread(@TempDir final Path directory) throws IOException {
    write(directory.resolve("welcome.md"), "---\ntitle: hi\n---\nbody");
    write(directory.resolve("1.md"), "---\ntitle: one\n---\nfirst");
    write(directory.resolve("2.md"), "---\ntitle: two\n---\nsecond");
    write(directory.resolve("3.md"), "---\ntitle: three\n---\nthird");

    final var updates = updatesIn(directory, Locale.ENGLISH);

    assertThat(updates.since(1).stream().map(FeishuUpdates.Note::version)).containsExactly(2, 3);
    assertThat(updates.since(3)).isEmpty();
  }

  @Test
  @DisplayName("a note in the configured language wins, and one without it falls back")
  void prefersTheConfiguredLanguage(@TempDir final Path directory) throws IOException {
    write(directory.resolve("welcome.md"), "---\ntitle: hi\n---\nbody");
    write(directory.resolve("welcome_zh_CN.md"), "---\ntitle: 你好\n---\n正文");
    write(directory.resolve("1.md"), "---\ntitle: one\n---\nfirst");
    write(directory.resolve("1_zh_CN.md"), "---\ntitle: 一\n---\n第一");
    // Only the base file, so a zh_CN deployment still sees it.
    write(directory.resolve("2.md"), "---\ntitle: two\n---\nsecond");

    final var updates = updatesIn(directory, Locale.SIMPLIFIED_CHINESE);

    assertThat(updates.welcome().title()).isEqualTo("你好");
    assertThat(updates.since(0).stream().map(FeishuUpdates.Note::title))
        .containsExactly("一", "two");
  }

  @Test
  @DisplayName("frontmatter is read as fields and the rest as the note's prose")
  void separatesFrontmatterFromProse(@TempDir final Path directory) throws IOException {
    write(
        directory.resolve("welcome.md"),
        """
        ---
        title: Hello
        suggestions:
          - ask me one thing
          - ask me another
        ---
        The first line.

        The second.
        """);

    final var welcome = updatesIn(directory, Locale.ENGLISH).welcome();

    assertThat(welcome.title()).isEqualTo("Hello");
    assertThat(welcome.suggestions()).containsExactly("ask me one thing", "ask me another");
    assertThat(welcome.body()).isEqualTo("The first line.\n\nThe second.");
  }

  @Test
  @DisplayName("a file with no frontmatter is all prose")
  void readsAFileWithNoFrontmatter(@TempDir final Path directory) throws IOException {
    write(directory.resolve("welcome.md"), "just the prose");

    final var welcome = updatesIn(directory, Locale.ENGLISH).welcome();

    assertThat(welcome.title()).isEmpty();
    assertThat(welcome.suggestions()).isEmpty();
    assertThat(welcome.body()).isEqualTo("just the prose");
  }

  @Test
  @DisplayName(
      "a frontmatter block that is never closed stops startup rather than being guessed at")
  void refusesAnUnclosedFrontmatterBlock(@TempDir final Path directory) throws IOException {
    write(directory.resolve("welcome.md"), "---\ntitle: Hello\nthe prose, with no fence above it");

    assertThatThrownBy(() -> updatesIn(directory, Locale.ENGLISH))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a deployment shipping no notes has nothing to say and says nothing")
  void copesWithNoNotesAtAll(@TempDir final Path directory) {
    final var updates = updatesIn(directory, Locale.ENGLISH);

    assertThat(updates.current()).isZero();
    assertThat(updates.since(0)).isEqualTo(List.of());
    assertThat(updates.welcome().suggestions()).isEmpty();
  }
}
