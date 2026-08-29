package me.kezhenxu94.springagent.core.tools.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.support.ResourceBundleMessageSource;

class ToolInputFileRefsTest {

  @TempDir Path storage;

  private static final ToolContext CONTEXT =
      new ToolContext(Map.of(ToolContexts.KEY_USER_ID, "user1"));

  private Path toolResults;

  @BeforeEach
  void setUp() throws Exception {
    toolResults = storage.resolve("user1").resolve("artifacts").resolve("tool-results");
    Files.createDirectories(toolResults);
  }

  @Test
  @DisplayName("arguments with nothing to expand are handed back as they came")
  void nothingToDo() {
    final var input = "{\"descendantsJson\":\"[]\"}";

    assertThat(refs().expand("Write", input, CONTEXT)).isEqualTo(input);
  }

  @Test
  @DisplayName("a reference is replaced by the file, and only on a parameter that takes one")
  void expandsWholeFile() throws Exception {
    final var file = saved("result.json", "[{\"block_id\":\"a\"}]");

    final var expanded =
        refs().expand("Write", "{\"descendantsJson\":\"@file:" + file + "\"}", CONTEXT);

    assertThat(expanded).isEqualTo("{\"descendantsJson\":\"[{\\\"block_id\\\":\\\"a\\\"}]\"}");
  }

  @Test
  @DisplayName("a pointer picks one part out, and a selected string arrives as itself")
  void expandsPointer() throws Exception {
    final var file = saved("r.json", "{\"data\":{\"blocks\":[1,2],\"title\":\"hi\"}}");

    assertThat(refs().expand("Write", arg("@file:" + file + "#/data/blocks"), CONTEXT))
        .isEqualTo("{\"descendantsJson\":\"[1,2]\"}");
    // Not quoted twice: a parameter asking for text wants the text.
    assertThat(refs().expand("Write", arg("@file:" + file + "#/data/title"), CONTEXT))
        .isEqualTo("{\"descendantsJson\":\"hi\"}");
  }

  @Test
  @DisplayName("a reference on a parameter that does not take one is refused, not written as text")
  void refusesUnlistedParameter() throws Exception {
    final var file = saved("r.json", "x");

    assertThatThrownBy(
            () -> refs().expand("Write", "{\"content\":\"@file:" + file + "\"}", CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class)
        .hasMessageContaining("does not take a file reference")
        .hasMessageContaining("descendantsJson");
  }

  @Test
  @DisplayName("a tool with no such parameters at all refuses too")
  void refusesUnlistedTool() throws Exception {
    final var file = saved("r.json", "x");

    assertThatThrownBy(() -> refs().expand("FeishuSendMessage", arg("@file:" + file), CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
  }

  @Test
  @DisplayName("doubling the marker writes it as the text it is")
  void escape() {
    assertThat(refs().expand("Write", arg("@@file:/etc/passwd"), CONTEXT))
        .isEqualTo("{\"descendantsJson\":\"@file:/etc/passwd\"}");
  }

  @Test
  @DisplayName("the marker in the middle of a value is text, not a reference")
  void onlyWholeValues() {
    final var input = "{\"descendantsJson\":\"see @file:/tmp/x for details\"}";

    assertThat(refs().expand("Write", input, CONTEXT)).isEqualTo(input);
  }

  @Test
  @DisplayName("a file outside the tool-results directory is refused wherever it sits")
  void refusesOutsideToolResults() throws Exception {
    final var outside = Files.writeString(storage.resolve("elsewhere.json"), "[]");
    final var nested = Files.createDirectories(toolResults.resolve("deeper"));
    final var deeper = Files.writeString(nested.resolve("x.json"), "[]");

    for (final var path : List.of(outside, deeper, Path.of("/etc/passwd"))) {
      assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + path), CONTEXT))
          .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    }
  }

  @Test
  @DisplayName("a symlink sitting in the directory does not smuggle in what it points at")
  void refusesSymlinkedFile() throws Exception {
    final var target = Files.writeString(storage.resolve("secret.json"), "[]");
    final var link = toolResults.resolve("innocent.json");
    Files.createSymbolicLink(link, target);

    assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + link), CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
  }

  @Test
  @DisplayName("a path that is not there says so rather than being written as text")
  void refusesMissingFile() {
    assertThatThrownBy(
            () -> refs().expand("Write", arg("@file:" + toolResults.resolve("gone.json")), CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class)
        .hasMessageContaining("No such tool-result file");
  }

  @Test
  @DisplayName("a pointer that selects nothing lists what the file does hold")
  void refusesMissingPointer() throws Exception {
    final var file = saved("r.json", "{\"data\":1,\"msg\":\"ok\"}");

    assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + file + "#/nope"), CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class)
        .hasMessageContaining("data, msg");
  }

  @Test
  @DisplayName("a pointer into something that is not JSON says which of the two is wrong")
  void refusesPointerIntoText() throws Exception {
    final var file = saved("r.txt", "not json at all");

    assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + file + "#/a"), CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class)
        .hasMessageContaining("not JSON");
  }

  @Test
  @DisplayName("more than the cap is an error, because half a JSON argument is not half a document")
  void refusesOverTheCap() throws Exception {
    final var file = saved("r.json", "\"" + "x".repeat(100) + "\"");

    assertThatThrownBy(() -> refs(20).expand("Write", arg("@file:" + file), CONTEXT))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class)
        .hasMessageContaining("over the");
  }

  @Test
  @DisplayName("without a context there is no identity to resolve a reference against")
  void refusesWithoutContext() throws Exception {
    final var file = saved("r.json", "[]");

    assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + file), null))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
  }

  private static String arg(final String value) {
    return "{\"descendantsJson\":\"" + value + "\"}";
  }

  private Path saved(final String name, final String content) throws Exception {
    return Files.writeString(toolResults.resolve(name), content);
  }

  private ToolInputFileRefs refs() {
    return refs(300_000);
  }

  private ToolInputFileRefs refs(final int maxInlinedInputChars) {
    return new ToolInputFileRefs(
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(
                null,
                null,
                null,
                null,
                new SpringAgentProperties.Ai.Tools(null, null, null, maxInlinedInputChars),
                null,
                null,
                null),
            Locale.ENGLISH),
        new UserWorkspaceFactory(
            FileSystemStorageProperties.builder().location(storage.toString()).build()),
        messages(),
        List.of(() -> Map.of("Write", Set.of("descendantsJson"))));
  }

  private static CoreMessages messages() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return new CoreMessages(source, new SpringAgentProperties(null, null, Locale.ENGLISH));
  }
}
