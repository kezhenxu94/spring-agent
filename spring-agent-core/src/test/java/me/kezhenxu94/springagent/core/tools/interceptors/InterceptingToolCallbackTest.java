package me.kezhenxu94.springagent.core.tools.interceptors;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.context.support.ResourceBundleMessageSource;

class InterceptingToolCallbackTest {

  @TempDir Path storage;

  private static final ToolContext CONTEXT =
      new ToolContext(Map.of(ToolContexts.KEY_USER_ID, "user1"));

  @Test
  @DisplayName("returnDirect is forwarded, so a tool that ends the turn still does")
  void forwardsMetadata() {
    final var callback =
        new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().returnDirect(true).build()),
            List.of(),
            refs());

    assertThat(callback.getToolMetadata().returnDirect()).isTrue();
  }

  @Test
  @DisplayName("the delegate is given the file, and every interceptor sees the reference")
  void expandsForTheDelegateOnly() throws Exception {
    // What a surface shows of a call is the arguments the model wrote. Handing the chain the
    // expanded form would put the payload the reference exists to avoid onto a Feishu card and
    // into the CLI, whatever order the interceptors happen to run in.
    final var toolResults = storage.resolve("user1").resolve("artifacts").resolve("tool-results");
    Files.createDirectories(toolResults);
    final var file = Files.writeString(toolResults.resolve("r.json"), "[1,2,3]");
    final var seen = new ArrayList<String>();
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    final var result =
        new InterceptingToolCallback(delegate, List.of(watching(seen)), refs())
            .call("{\"descendantsJson\":\"@file:" + file + "\"}", CONTEXT);

    assertThat(delegate.received).isEqualTo("{\"descendantsJson\":\"[1,2,3]\"}");
    assertThat(seen)
        .allSatisfy(input -> assertThat(input).contains("@file:").doesNotContain("1,2,3"));
    assertThat(seen).hasSize(2);
    assertThat(result).isEqualTo("ok");
  }

  @Test
  @DisplayName("a reference that cannot be resolved answers the call instead of raising")
  void unresolvableReferenceAnswersTheCall() {
    // And the after-half still runs, because a surface that showed the call starting has to be
    // told it finished.
    final var seen = new ArrayList<String>();
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    final var result =
        new InterceptingToolCallback(delegate, List.of(watching(seen)), refs())
            .call("{\"descendantsJson\":\"@file:/nowhere/at/all.json\"}", CONTEXT);

    assertThat(delegate.received).isNull();
    assertThat(result).contains("No such tool-result file");
    assertThat(seen).hasSize(2);
  }

  @Test
  @DisplayName("the tool never sees the display description, and every interceptor does")
  void stripsTheDisplayDescription() {
    // Which is the whole reason it is asked for: a surface names a call by it, and the tool was
    // never told the parameter exists.
    final var seen = new ArrayList<String>();
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    new InterceptingToolCallback(delegate, List.of(watching(seen)), refs())
        .call(
            "{\"path\":\"/tmp/x\",\"" + DisplayDescription.FIELD + "\":\"Write the file\"}",
            CONTEXT);

    assertThat(delegate.received).isEqualTo("{\"path\":\"/tmp/x\"}");
    assertThat(seen).hasSize(2).allSatisfy(input -> assertThat(input).contains("Write the file"));
  }

  @Test
  @DisplayName("a refusal from beforeCall answers the call instead of ending the turn")
  void aRefusalAnswersTheCall() {
    // The whole point of CallRefused. An interceptor that decided the call must not happen has
    // nowhere else to put that: an ordinary exception thrown here leaves ToolCallback.call by a
    // path Spring AI's exception processor does not cover, so the run stops rather than the model
    // being told why.
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    final var result =
        new InterceptingToolCallback(delegate, List.of(refusing("Refused: not yours")), refs())
            .call("{\"path\":\"/tmp/x\"}", CONTEXT);

    assertThat(result).isEqualTo("Refused: not yours");
    assertThat(delegate.received).as("the tool ran anyway").isNull();
  }

  @Test
  @DisplayName("and the after-half still runs, so a card that said the call started is cleared")
  void aRefusalStillRunsTheAfterHalf() {
    final var seen = new ArrayList<String>();
    final var results = new ArrayList<String>();

    final var result =
        new InterceptingToolCallback(
                new RecordingCallback(ToolMetadata.builder().build()),
                List.of(recording(seen, results), refusing("Refused: not yours")),
                refs())
            .call("{\"path\":\"/tmp/x\"}", CONTEXT);

    assertThat(result).isEqualTo("Refused: not yours");
    assertThat(seen).as("beforeCall of the interceptor ahead of the refusal").hasSize(1);
    assertThat(results)
        .as("its afterCall, with the refusal as the result")
        .containsExactly("Refused: not yours");
  }

  @Test
  @DisplayName("an interceptor after the refusing one is not asked, since there is no call left")
  void aRefusalStopsTheRestOfTheBeforeHalf() {
    final var seen = new ArrayList<String>();
    final var results = new ArrayList<String>();

    new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().build()),
            List.of(refusing("Refused"), recording(seen, results)),
            refs())
        .call("{\"path\":\"/tmp/x\"}", CONTEXT);

    assertThat(seen).isEmpty();
    // afterCall still reaches it: the chain is unwound in full so nothing is left half-shown.
    assertThat(results).containsExactly("Refused");
  }

  @Test
  @DisplayName("an ordinary exception from beforeCall is still an exception, not a quiet answer")
  void anOrdinaryExceptionIsNotSwallowed() {
    final var callback =
        new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().build()),
            List.of(
                new ToolCallInterceptor() {
                  @Override
                  public String beforeCall(String name, String input, ToolContext context) {
                    throw new IllegalStateException("broken interceptor");
                  }
                }),
            refs());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> callback.call("{\"path\":\"/tmp/x\"}", CONTEXT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("broken interceptor");
  }

  private static ToolCallInterceptor refusing(final String message) {
    return new ToolCallInterceptor() {
      @Override
      public String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
        throw new CallRefused(message);
      }
    };
  }

  private static ToolCallInterceptor recording(
      final List<String> inputs, final List<String> results) {
    return new ToolCallInterceptor() {
      @Override
      public String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
        inputs.add(toolInput);
        return toolInput;
      }

      @Override
      public String afterCall(
          String toolName, String toolInput, String toolResult, ToolContext toolContext) {
        results.add(toolResult);
        return toolResult;
      }
    };
  }

  private static ToolCallInterceptor watching(final List<String> seen) {
    return new ToolCallInterceptor() {
      @Override
      public String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
        seen.add(toolInput);
        return toolInput;
      }

      @Override
      public String afterCall(
          String toolName, String toolInput, String toolResult, ToolContext toolContext) {
        seen.add(toolInput);
        return toolResult;
      }
    };
  }

  private static final class RecordingCallback implements ToolCallback {
    private final ToolMetadata metadata;
    private String received;

    private RecordingCallback(final ToolMetadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder().name("Write").description("").inputSchema("{}").build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return metadata;
    }

    @Override
    public String call(String toolInput) {
      received = toolInput;
      return "ok";
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
      return call(toolInput);
    }
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
