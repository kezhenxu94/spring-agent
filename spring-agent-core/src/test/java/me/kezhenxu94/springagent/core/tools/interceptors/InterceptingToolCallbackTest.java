package me.kezhenxu94.springagent.core.tools.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
            refs(),
            messages());

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
        new InterceptingToolCallback(delegate, List.of(watching(seen)), refs(), messages())
            .call("{\"descendantsJson\":\"@file:" + file + "\"}", CONTEXT);

    assertThat(delegate.received).isEqualTo("{\"descendantsJson\":\"[1,2,3]\"}");
    assertThat(seen)
        .allSatisfy(input -> assertThat(input).contains("@file:").doesNotContain("1,2,3"));
    assertThat(seen).hasSize(2);
    assertThat(result).isEqualTo("\"ok\"");
  }

  @Test
  @DisplayName("a reference that cannot be resolved answers the call instead of raising")
  void unresolvableReferenceAnswersTheCall() {
    // And the after-half still runs, because a surface that showed the call starting has to be
    // told it finished.
    final var seen = new ArrayList<String>();
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    final var result =
        new InterceptingToolCallback(delegate, List.of(watching(seen)), refs(), messages())
            .call("{\"descendantsJson\":\"@file:/nowhere/at/all.json\"}", CONTEXT);

    assertThat(delegate.received).isNull();
    assertThat(result).contains("No such file");
    assertThat(seen).hasSize(2);
  }

  @Test
  @DisplayName("the tool never sees the display description, and every interceptor does")
  void stripsTheDisplayDescription() {
    // Which is the whole reason it is asked for: a surface names a call by it, and the tool was
    // never told the parameter exists.
    final var seen = new ArrayList<String>();
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    new InterceptingToolCallback(delegate, List.of(watching(seen)), refs(), messages())
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
        new InterceptingToolCallback(
                delegate, List.of(refusing("Refused: not yours")), refs(), messages())
            .call("{\"path\":\"/tmp/x\"}", CONTEXT);

    assertThat(result).isEqualTo("\"Refused: not yours\"");
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
                refs(),
                messages())
            .call("{\"path\":\"/tmp/x\"}", CONTEXT);

    assertThat(result).isEqualTo("\"Refused: not yours\"");
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
            refs(),
            messages())
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
            refs(),
            messages());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> callback.call("{\"path\":\"/tmp/x\"}", CONTEXT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("broken interceptor");
  }

  @Test
  @DisplayName("arguments the model did not finish writing answer the call instead of reaching it")
  void truncatedArgumentsAnswerTheCall() {
    // A provider that runs out of output tokens mid-call still delivers what it had got to, and it
    // ends wherever the cut fell — here inside the description the runtime asked for, which is the
    // last field written and so where a cut usually lands. Handed on, that payload fails inside the
    // tool with Jackson naming a LinkedHashMap and a Java type, which tells the model nothing about
    // writing the call again.
    final var seen = new ArrayList<String>();
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    final var result =
        new InterceptingToolCallback(delegate, List.of(watching(seen)), refs(), messages())
            .call(
                "{\"path\":\"/tmp/x\",\"" + DisplayDescription.FIELD + "\":\"Write the fi",
                CONTEXT);

    assertThat(delegate.received).as("the tool was called with a broken payload").isNull();
    assertThat(result)
        .contains("could not be read as JSON")
        .contains("Write")
        .contains("closing quote")
        .doesNotContain("LinkedHashMap");
    // And the after-half still runs, so a surface that showed the call starting is told it ended.
    assertThat(seen).hasSize(2);
  }

  @Test
  @DisplayName("arguments that were never JSON are still the tool's business, not this one's")
  void argumentsThatAreNotJsonAreLeftAlone() {
    // Judging every payload would have this decide that a callback whose input is not JSON is being
    // called wrongly. Only one that set out to be a JSON object is judged.
    final var delegate = new RecordingCallback(ToolMetadata.builder().build());

    final var result =
        new InterceptingToolCallback(delegate, List.of(), refs(), messages())
            .call("not json at all", CONTEXT);

    assertThat(delegate.received).isEqualTo("not json at all");
    assertThat(result).isEqualTo("\"ok\"");
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
      // A JSON string literal, because that is what a real callback returns: Spring AI's
      // DefaultToolCallResultConverter runs toJson over every tool's return value, so a method
      // answering `ok` reaches this class as `"ok"`. Returning bare `ok` here made the stub the one
      // caller in the world that broke ToolCallback's contract, and hid that
      // InterceptingToolCallback
      // has to keep it.
      return "\"ok\"";
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
      return call(toolInput);
    }
  }

  private ToolInputFileRefs refs() {
    return new ToolInputFileRefs(
        new SpringAgentProperties(
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
    return new CoreMessages(source, new SpringAgentProperties(null, Locale.ENGLISH, null, null));
  }

  // --- what this callback hands back is JSON, because ToolCallback promises it is ---------

  @Test
  @DisplayName("a result an interceptor replaced with prose comes back as JSON")
  void aReplacedResultIsJson() {
    // The production failure this exists for: LargeResponseInterceptor swaps a big result for a
    // sentence telling the model where it was saved. That sentence is not JSON, and Gemini parses
    // what it is given — GoogleGenAiChatModel threw "Failed to parse JSON", which surfaced as a run
    // dying on "Stream processing failed", naming neither the tool nor the interceptor.
    final var callback =
        new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().build()),
            List.of(replacingWith("the result was too large, saved to /tmp/x.json")),
            refs(),
            messages());

    final var result = callback.call("{}", CONTEXT);

    assertThat(result).isEqualTo("\"the result was too large, saved to /tmp/x.json\"");
    assertThatCode(() -> new tools.jackson.databind.json.JsonMapper().readTree(result))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a refusal comes back as JSON too, being an answer the tool never gave")
  void aRefusalIsJson() {
    final var callback =
        new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().build()),
            List.of(refusingWith("not allowed here")),
            refs(),
            messages());

    assertThat(callback.call("{}", CONTEXT)).isEqualTo("\"not allowed here\"");
  }

  @Test
  @DisplayName("arguments the model did not finish writing come back as JSON")
  void malformedArgumentsAreJson() {
    final var callback =
        new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().build()), List.of(), refs(), messages());

    final var result = callback.call("{\"unterminated", CONTEXT);

    assertThatCode(() -> new tools.jackson.databind.json.JsonMapper().readTree(result))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a result that is already JSON is passed through, not quoted a second time")
  void jsonIsNotDoubleEncoded() {
    // The ordinary path: Spring AI's DefaultToolCallResultConverter has already run toJson over the
    // tool's return value, so re-encoding here would reach the model as a quoted blob of text where
    // an object was expected.
    final var callback =
        new InterceptingToolCallback(
            new RecordingCallback(ToolMetadata.builder().build()),
            List.of(replacingWith("{\"files\":[\"a.txt\"]}")),
            refs(),
            messages());

    assertThat(callback.call("{}", CONTEXT)).isEqualTo("{\"files\":[\"a.txt\"]}");
  }

  /** An interceptor that throws the result away and answers with something of its own. */
  private static ToolCallInterceptor replacingWith(final String replacement) {
    return new ToolCallInterceptor() {
      @Override
      public String afterCall(
          final String toolName,
          final String toolInput,
          final String toolResult,
          final ToolContext toolContext) {
        return replacement;
      }
    };
  }

  private static ToolCallInterceptor refusingWith(final String why) {
    return new ToolCallInterceptor() {
      @Override
      public String beforeCall(
          final String toolName, final String toolInput, final ToolContext toolContext) {
        throw new ToolCallInterceptor.CallRefused(why);
      }
    };
  }
}
