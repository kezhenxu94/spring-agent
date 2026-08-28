package me.kezhenxu94.springagent.core.config;

import static me.kezhenxu94.springagent.core.tools.AgentToolsProvider.KNOWLEDGE_RETRIEVAL_PROMPT;
import static me.kezhenxu94.springagent.core.tools.AgentToolsProvider.MEMORY_PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

class LocalizedPromptTest {

  @Test
  @DisplayName("the translation for the exact locale wins where core ships one")
  void exactLocale() {
    assertThat(LocalizedPrompt.resource(MEMORY_PROMPT, Locale.of("zh", "CN")).getFilename())
        .isEqualTo(MEMORY_PROMPT + "_zh_CN.md");
  }

  @Test
  @DisplayName("a locale with no translation falls back to the base file rather than failing")
  void untranslatedLocale() {
    assertThat(LocalizedPrompt.resource(MEMORY_PROMPT, Locale.FRANCE).getFilename())
        .isEqualTo(MEMORY_PROMPT + ".md");
  }

  @Test
  @DisplayName("no locale at all is the host's, as everything else in core reads it")
  void noLocale() {
    assertThat(LocalizedPrompt.resource(MEMORY_PROMPT, null).exists()).isTrue();
  }

  /**
   * The advisor renders the prompt as it is built, through a template whose delimiters are braces.
   * A stray brace in a translation — an example written as a placeholder, say — fails there, on
   * every request, rather than in whatever wrote it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"en", "zh_CN"})
  @DisplayName("every translation of the memory prompt renders as the advisor builds it")
  void memoryPromptRenders(final String tag) {
    assertThatCode(
            () ->
                AutoMemoryToolsAdvisor.builder()
                    .memoriesRootDirectory("/tmp/memories")
                    .memorySystemPrompt(
                        LocalizedPrompt.resource(
                            MEMORY_PROMPT, Locale.forLanguageTag(tag.replace('_', '-'))))
                    .build())
        .doesNotThrowAnyException();
  }

  /**
   * {@link ContextualQueryAugmenter} requires its template to carry {@code query} and {@code
   * context} placeholders; a translation missing either fails at advisor-build time rather than on
   * the first retrieval.
   */
  @ParameterizedTest
  @ValueSource(strings = {"en", "zh_CN"})
  @DisplayName("every translation of the knowledge-retrieval prompt builds a valid query augmenter")
  void knowledgeRetrievalPromptRenders(final String tag) {
    assertThatCode(
            () ->
                ContextualQueryAugmenter.builder()
                    .allowEmptyContext(true)
                    .promptTemplate(
                        new PromptTemplate(
                            LocalizedPrompt.resource(
                                KNOWLEDGE_RETRIEVAL_PROMPT,
                                Locale.forLanguageTag(tag.replace('_', '-')))))
                    .build())
        .doesNotThrowAnyException();
  }
}
