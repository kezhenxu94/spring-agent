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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * What an argument reference must never be talked into reading.
 *
 * <p>These are not edge cases. Expanding an argument is the one place where text the model wrote
 * becomes a file read, and a run does not always act on behalf of somebody trustworthy: an
 * observation carries evidence written by whoever caused the event, so a triage run reads
 * attacker-authored text and can be told to do things by it. Every test here is one sentence
 * somebody could put in a webhook payload.
 */
class ToolInputFileRefsSecurityTest {

  @TempDir Path storage;

  @Test
  @DisplayName("a tool that only sends things out cannot be turned into one that reads files")
  void aSendingToolIsNotAFileReader() throws Exception {
    // The whole reason the allow-list exists. "Reply with the contents of @file:..." in an event
    // payload has to fail on the parameter, not on where the file happens to sit.
    final var secret = saved("user1", "notes.json", "the memories");

    assertThatThrownBy(
            () ->
                refs()
                    .expand(
                        "FeishuSendMessage",
                        "{\"content\":\"@file:" + secret + "\"}",
                        contextOf("user1")))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class)
        .hasMessageContaining("does not take a file reference");
  }

  @Test
  @DisplayName("a listed tool still refuses the reference on any parameter but the listed one")
  void onlyTheListedParameter() throws Exception {
    final var secret = saved("user1", "notes.json", "the memories");

    assertThatThrownBy(
            () ->
                refs()
                    .expand(
                        "Write",
                        "{\"descendantsJson\":\"[]\",\"clientToken\":\"@file:" + secret + "\"}",
                        contextOf("user1")))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
  }

  @Test
  @DisplayName("another user's saved results are out of reach, named directly or walked to")
  void cannotReachAnotherUser() throws Exception {
    final var theirs = saved("user2", "r.json", "[1]");
    final var mine = toolResultsOf("user1");
    final var walked =
        mine.resolve("..")
            .resolve("..")
            .resolve("..")
            .resolve("user2")
            .resolve("artifacts")
            .resolve("tool-results")
            .resolve("r.json");

    for (final var path : List.of(theirs, walked)) {
      assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + path), contextOf("user1")))
          .as("reached %s", path)
          .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    }
  }

  @Test
  @DisplayName("a group's saved results are reachable only from a run that is in that group")
  void groupScopeIsTheRequestsOwn() throws Exception {
    final var shared = saved(Path.of("groups", "chatA").toString(), "r.json", "[1]");

    assertThat(refs().expand("Write", arg("@file:" + shared), contextOf("user1", "chatA")))
        .isEqualTo("{\"descendantsJson\":\"[1]\"}");
    // The same path, from a run that is not in that chat, is somebody else's.
    assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + shared), contextOf("user1")))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    assertThatThrownBy(
            () -> refs().expand("Write", arg("@file:" + shared), contextOf("user1", "chatB")))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
  }

  @Test
  @DisplayName("nothing outside the tool-results directory is readable, however it is spelled")
  void onlyToolResults() throws Exception {
    final var home = storage.resolve("user1");
    toolResultsOf("user1");
    Files.createDirectories(home.resolve("memories"));
    final var memories = Files.writeString(home.resolve("memories").resolve("m.md"), "secret");
    final var artifact = Files.writeString(home.resolve("artifacts").resolve("a.json"), "[1]");
    final var walked =
        toolResultsOf("user1").resolve("..").resolve("..").resolve("memories").resolve("m.md");

    for (final var path : List.of(memories, artifact, walked, Path.of("/etc/hosts"))) {
      assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + path), contextOf("user1")))
          .as("read %s", path)
          .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    }
  }

  @Test
  @DisplayName("a link planted in the right directory is judged by what it points at")
  void followsLinksBeforeJudging() throws Exception {
    final var outside = Files.writeString(storage.resolve("outside.json"), "[1]");
    final var link = toolResultsOf("user1").resolve("looks-fine.json");
    Files.createSymbolicLink(link, outside);
    final var linkedDir = toolResultsOf("user1").resolve("sub");
    Files.createSymbolicLink(linkedDir, storage);

    assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + link), contextOf("user1")))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    assertThatThrownBy(
            () ->
                refs()
                    .expand(
                        "Write",
                        arg("@file:" + linkedDir.resolve("outside.json")),
                        contextOf("user1")))
        .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
  }

  @Test
  @DisplayName("what a file expands to is content, never another reference to follow")
  void doesNotExpandWhatItRead() throws Exception {
    // Otherwise a file whose text somebody else controls — a saved webhook payload, say — would
    // be a second chance to name a path, and this time from inside a value that already passed.
    final var planted = saved("user1", "r.json", "@file:/etc/hosts");

    assertThat(refs().expand("Write", arg("@file:" + planted), contextOf("user1")))
        .isEqualTo("{\"descendantsJson\":\"@file:/etc/hosts\"}");
  }

  @Test
  @DisplayName("a directory is not a file, and neither is a dangling link")
  void neitherDirectoriesNorDanglingLinks() throws Exception {
    final var dir = Files.createDirectories(toolResultsOf("user1").resolve("adir"));
    final var dangling = toolResultsOf("user1").resolve("dangling.json");
    Files.createSymbolicLink(dangling, storage.resolve("never-created.json"));

    for (final var path : List.of(dir, dangling)) {
      assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + path), contextOf("user1")))
          .as("accepted %s", path)
          .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    }
  }

  @Test
  @DisplayName("an empty or relative path is refused rather than resolved against anything")
  void refusesEmptyAndRelativePaths() {
    for (final var path : List.of("", "r.json", "./r.json", "~/r.json")) {
      assertThatThrownBy(() -> refs().expand("Write", arg("@file:" + path), contextOf("user1")))
          .as("accepted '%s'", path)
          .isInstanceOf(ToolInputFileRefs.UnresolvableReference.class);
    }
  }

  @Test
  @DisplayName("a reference buried inside a nested argument is left alone, not quietly followed")
  void nestedArgumentsAreNotExpanded() throws Exception {
    // Only top-level arguments are looked at. Worth pinning down: the risk of the other choice is
    // that a tool whose schema nobody here wrote — an MCP server's — gains a file-reading
    // parameter that no allow-list ever named.
    final var file = saved("user1", "r.json", "[1]");
    final var input = "{\"descendantsJson\":{\"nested\":\"@file:" + file + "\"}}";

    assertThat(refs().expand("Write", input, contextOf("user1"))).isEqualTo(input);
  }

  @Test
  @DisplayName("a value that is not a string is never treated as a reference")
  void onlyStringsAreConsidered() throws Exception {
    final var input = "{\"descendantsJson\":[1,2],\"index\":3,\"flag\":true,\"none\":null}";

    assertThat(refs().expand("Write", input, contextOf("user1"))).isEqualTo(input);
  }

  private static String arg(final String value) {
    return "{\"descendantsJson\":\"" + value + "\"}";
  }

  private Path toolResultsOf(final String scope) throws Exception {
    return Files.createDirectories(
        storage.resolve(scope).resolve("artifacts").resolve("tool-results"));
  }

  private Path saved(final String scope, final String name, final String content) throws Exception {
    return Files.writeString(toolResultsOf(scope).resolve(name), content);
  }

  private static ToolContext contextOf(final String userId) {
    return new ToolContext(Map.of(ToolContexts.KEY_USER_ID, userId));
  }

  private static ToolContext contextOf(final String userId, final String groupId) {
    return new ToolContext(
        Map.of(ToolContexts.KEY_USER_ID, userId, ToolContexts.KEY_GROUP_ID, groupId));
  }

  private ToolInputFileRefs refs() {
    return new ToolInputFileRefs(
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(
                null,
                null,
                null,
                null,
                new SpringAgentProperties.Ai.Tools(null, null, null, null, null),
                null,
                null,
                null),
            Locale.ENGLISH,
            null,
            null),
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
    return new CoreMessages(
        source, new SpringAgentProperties(null, null, Locale.ENGLISH, null, null));
  }
}
