package me.kezhenxu94.springagent.core.tools.interceptors;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

class LargeResponseInterceptorTest {

  @TempDir Path storage;

  private static final ToolContext CONTEXT =
      new ToolContext(Map.of(ToolContexts.KEY_USER_ID, "user1"));

  @Test
  @DisplayName("a result that fits is handed on untouched")
  void underThreshold() {
    final var result = interceptor(100).afterCall("Tool", "{}", "small", CONTEXT);

    assertThat(result).isEqualTo("small");
    assertThat(spilled()).isEmpty();
  }

  @Test
  @DisplayName("a large result is saved and the model is told where and what is in it")
  void overThreshold() throws Exception {
    final var json =
        "{\"code\":0,\"data\":{\"blocks\":[1,2,3],\"title\":\"" + "x".repeat(200) + "\"}}";

    final var result = interceptor(20).afterCall("FeishuListDocBlocks", "{}", json, CONTEXT);

    final var files = spilled();
    assertThat(files).singleElement();
    assertThat(result).contains(files.get(0).toAbsolutePath().toString());
    // The shape is the point: a run that only wanted the count never has to open the file.
    assertThat(result).contains("code: number").contains("blocks: [3 items");
    assertThat(result).contains("@file:");
  }

  @Test
  @DisplayName("JSON is saved indented and named .json, because Read truncates a long line")
  void jsonIsPrettyPrinted() throws Exception {
    final var json = "{\"a\":\"" + "x".repeat(3000) + "\",\"b\":1}";

    interceptor(20).afterCall("Tool", "{}", json, CONTEXT);

    final var file = spilled().get(0);
    assertThat(file.getFileName().toString()).endsWith(".json");
    assertThat(Files.readString(file).lines().count()).isGreaterThan(1);
  }

  @Test
  @DisplayName("a result that is not JSON keeps its own shape and gets a .txt name")
  void plainTextKeepsItsShape() throws Exception {
    final var text = "line\n".repeat(100);

    final var result = interceptor(20).afterCall("Bash", "{}", text, CONTEXT);

    assertThat(spilled().get(0).getFileName().toString()).endsWith(".txt");
    assertThat(Files.readString(spilled().get(0))).isEqualTo(text);
    assertThat(result).contains("100 line(s)");
  }

  @Test
  @DisplayName("two results saved in the same millisecond get files of their own")
  void namesDoNotCollide() {
    // A model emits tool calls in parallel and Spring AI runs them so. A name made of the tool and
    // the millisecond used to have one call overwrite the other, and both guides point at one
    // file — which is a wrong document once that path can be passed back in as an argument.
    final var interceptor = interceptor(20);
    final var first = interceptor.afterCall("Tool", "{}", "a".repeat(100), CONTEXT);
    final var second = interceptor.afterCall("Tool", "{}", "b".repeat(100), CONTEXT);

    assertThat(spilled()).hasSize(2);
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("without a user there is nowhere to save it, so the result is handed on whole")
  void noUserId() {
    final var big = "a".repeat(100);

    final var result = interceptor(20).afterCall("Tool", "{}", big, new ToolContext(Map.of()));

    assertThat(result).isEqualTo(big);
  }

  private List<Path> spilled() {
    final var dir = storage.resolve("user1").resolve("artifacts").resolve("tool-results");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (var files = Files.list(dir)) {
      return files.toList();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private LargeResponseInterceptor interceptor(final int maxResultChars) {
    return new LargeResponseInterceptor(
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(
                null,
                null,
                null,
                null,
                new SpringAgentProperties.Ai.Tools(null, null, null, maxResultChars, null),
                null,
                null,
                null),
            Locale.ENGLISH,
            null,
            null),
        new UserWorkspaceFactory(
            FileSystemStorageProperties.builder().location(storage.toString()).build()),
        messages());
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
