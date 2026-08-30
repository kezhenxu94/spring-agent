package me.kezhenxu94.springagent.integration.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reducing a mail body to text that says the same thing read as it does rendered. */
class MessageTextTest {

  private static MimeMessage message(final String raw) throws Exception {
    return new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("a plain message is its own text")
  void shouldReadPlainText() throws Exception {
    final var mail =
        message(
            """
            From: a@b.example
            Content-Type: text/plain; charset=UTF-8

            The build is failing on main.
            """);

    assertThat(MessageText.bodyOf(mail, 8000)).isEqualTo("The build is failing on main.");
  }

  @Test
  @DisplayName("where a message is sent twice over, the plain half is the one read")
  void shouldPreferPlainOverHtml() throws Exception {
    // The two halves are written by the sender and need not agree. The plain one is both the one
    // that needs no parsing and the one nothing can be hidden in.
    final var mail =
        message(
            """
            From: a@b.example
            Content-Type: multipart/alternative; boundary="b"

            --b
            Content-Type: text/plain; charset=UTF-8

            what the person wrote
            --b
            Content-Type: text/html; charset=UTF-8

            <html><body>something else entirely</body></html>
            --b--
            """);

    assertThat(MessageText.bodyOf(mail, 8000)).isEqualTo("what the person wrote");
  }

  @Test
  @DisplayName("an HTML-only message is parsed, not pattern-matched")
  void shouldReadHtml() throws Exception {
    final var mail =
        message(
            """
            From: a@b.example
            Content-Type: text/html; charset=UTF-8

            <html><head><title>ignored</title><style>.x{color:red}</style></head>
            <body><p>Hello &amp; welcome</p><!-- hidden --><script>alert('x')</script>
            <div title=">not a tag">and more</div></body></html>
            """);

    final var body = MessageText.bodyOf(mail, 8000);

    assertThat(body).contains("Hello & welcome").contains("and more");
    // Each of these would need a rule of its own with a regular expression, and the last would
    // defeat one: an attribute containing a > ends the tag as far as a pattern is concerned.
    assertThat(body).doesNotContain("alert").doesNotContain("color:red");
    assertThat(body).doesNotContain("hidden").doesNotContain("ignored");
    assertThat(body).doesNotContain("not a tag");
  }

  @Test
  @DisplayName("characters that render as nothing are removed, entity-encoded ones included")
  void shouldStripInvisibleCharacters() throws Exception {
    // The parser decodes entities and this runs after it, so an entity is not a way around the
    // stripping.
    final var mail =
        message(
            """
            From: a@b.example
            Content-Type: text/html; charset=UTF-8

            <html><body>visible&#x202E;&#xE0041;&#x200B;text</body></html>
            """);

    assertThat(MessageText.bodyOf(mail, 8000)).isEqualTo("visibletext");
  }

  @Test
  @DisplayName("the joiners real scripts are written with are left alone")
  void shouldKeepScriptJoiners() {
    // Dropping every format character would close a channel that carries no payload on its own, at
    // the cost of corrupting ordinary Arabic and Indic text.
    assertThat(MessageText.clean("a‌b‍c")).isEqualTo("a‌b‍c");
  }

  @Test
  @DisplayName("an over-long body is cut, and says that it was")
  void shouldTruncateAudibly() {
    final var cut = MessageText.truncate("x".repeat(100), 10);

    assertThat(cut).startsWith("x".repeat(10)).contains("truncated");
  }

  @Test
  @DisplayName("an attachment is not read, whatever it contains")
  void shouldNotReadAttachments() throws Exception {
    final var mail =
        message(
            """
            From: a@b.example
            Content-Type: multipart/mixed; boundary="b"

            --b
            Content-Type: text/plain; charset=UTF-8

            see attached
            --b
            Content-Type: text/plain; charset=UTF-8
            Content-Disposition: attachment; filename="notes.txt"

            instructions in an attachment
            --b--
            """);

    assertThat(MessageText.bodyOf(mail, 8000))
        .isEqualTo("see attached")
        .doesNotContain("instructions");
  }

  @Test
  @DisplayName("a body that cannot be read is empty rather than an exception")
  void shouldSurviveAnUnreadableBody() throws Exception {
    // A message whose text is unreadable is still a message that arrived, and letting this escape
    // would put one unparseable mail in the way of everything behind it.
    final var mail =
        message(
            """
            From: a@b.example
            Content-Type: multipart/mixed; boundary="b"

            truncated before any part
            """);

    assertThat(MessageText.bodyOf(mail, 8000)).isNotNull();
  }
}
