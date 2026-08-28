package me.kezhenxu94.springagent.core.config;

import static me.kezhenxu94.springagent.core.tools.AgentToolsProvider.KNOWLEDGE_RETRIEVAL_PROMPT;
import static me.kezhenxu94.springagent.core.tools.AgentToolsProvider.MEMORY_PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
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

  @Test
  @DisplayName("find answers empty rather than throwing, for a caller that has a fallback")
  void findIsNullable() {
    assertThat(LocalizedPrompt.find(LocalizedPrompt.LOCATION, "no-such-prompt", Locale.ENGLISH))
        .isEmpty();
    assertThat(LocalizedPrompt.find(LocalizedPrompt.LOCATION, MEMORY_PROMPT, Locale.ENGLISH))
        .isPresent();
  }

  /**
   * A tool's description is the caller that needs the nullable form: the English is the
   * annotation's own and there is no base file to fall back to, so "nothing translated" has to be
   * an answer rather than a failure.
   */
  @Test
  @DisplayName("a location with no base file at all is empty, not an error")
  void findUnderAnotherLocation() {
    assertThat(LocalizedPrompt.find(LocalizedPrompt.TOOLS_LOCATION, "NoSuchTool", Locale.ENGLISH))
        .isEmpty();
  }

  @Test
  @DisplayName("resource still fails loudly where a caller has nothing to fall back to")
  void resourceStillThrows() {
    assertThatThrownBy(() -> LocalizedPrompt.resource("no/such/place/", "nothing", Locale.ENGLISH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no/such/place/nothing.md");
  }

  /**
   * The skill tool's description is the one the library composes rather than declares: it formats
   * the list of installed skills into this template. A translation that lost the {@code %s} would
   * leave the model told to use only skills it is then never shown, and nothing else would notice.
   */
  @ParameterizedTest
  @ValueSource(strings = {"zh_CN"})
  @DisplayName("every translation of the skill tool's template keeps the slot for the skills list")
  void skillToolTemplateKeepsItsSlot(final String tag) {
    final var template =
        LocalizedPrompt.findText(
            AgentToolsProvider.SKILL_TOOL_PROMPT, Locale.forLanguageTag(tag.replace('_', '-')));

    assertThat(template).isPresent();
    assertThat(template.get().split("%s", -1).length - 1)
        .as("exactly one %%s, which is what the skills list is formatted into")
        .isEqualTo(1);
  }

  /** And no file under the per-tool location may name it, which would discard that list. */
  @Test
  @DisplayName("the skill tool has no per-tool description file, which would drop the skills list")
  void skillToolHasNoPerToolFile() {
    assertThat(LocalizedPrompt.find(LocalizedPrompt.TOOLS_LOCATION, "Skill", Locale.of("zh", "CN")))
        .isEmpty();
  }
}
