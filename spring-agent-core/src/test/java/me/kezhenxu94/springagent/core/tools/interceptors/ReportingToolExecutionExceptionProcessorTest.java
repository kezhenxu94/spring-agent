package me.kezhenxu94.springagent.core.tools.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.support.ResourceBundleMessageSource;

class ReportingToolExecutionExceptionProcessorTest {

  private static final ToolDefinition TOOL =
      ToolDefinition.builder().name("FeishuSendFile").description("d").inputSchema("{}").build();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  @DisplayName("A read timeout is answered to the model rather than ending the run")
  void answersATimeout() {
    final var answer =
        processor(false)
            .process(
                new ToolExecutionException(TOOL, new SocketTimeoutException("Read timed out")));

    assertThat(answer).contains("FeishuSendFile").contains("Read timed out");
  }

  @Test
  @DisplayName("A runtime failure is still the delegate's to answer")
  void runtimeFailureGoesToTheDelegate() {
    final var answer =
        processor(false).process(new ToolExecutionException(TOOL, new IllegalStateException("x")));

    assertThat(answer).isEqualTo("x");
  }

  @Test
  @DisplayName("An Error still ends the run")
  void errorIsRethrown() {
    final var exception = new ToolExecutionException(TOOL, new OutOfMemoryError());

    assertThatThrownBy(() -> processor(false).process(exception)).isSameAs(exception);
  }

  @Test
  @DisplayName("A cancelled run is not carried on past the stop button")
  void interruptionIsRethrown() {
    final var exception = new ToolExecutionException(TOOL, new IOException("Socket closed"));
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> processor(false).process(exception)).isSameAs(exception);
  }

  @Test
  @DisplayName("throw-exception-on-error still ends the run on a checked failure")
  void alwaysThrowIsHonoured() {
    final var exception =
        new ToolExecutionException(TOOL, new SocketTimeoutException("Read timed out"));

    assertThatThrownBy(() -> processor(true).process(exception)).isSameAs(exception);
  }

  private static ReportingToolExecutionExceptionProcessor processor(final boolean alwaysThrow) {
    return new ReportingToolExecutionExceptionProcessor(
        DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(alwaysThrow).build(),
        alwaysThrow,
        messages());
  }

  private static CoreMessages messages() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return new CoreMessages(source, new SpringAgentProperties(null, Locale.ENGLISH, null, null));
  }
}
