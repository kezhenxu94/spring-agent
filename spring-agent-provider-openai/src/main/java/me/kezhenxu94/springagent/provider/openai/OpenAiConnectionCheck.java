package me.kezhenxu94.springagent.provider.openai;

import com.google.common.base.Strings;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Refuses to start when nothing said where the model is, or which model it is.
 *
 * <p>Here rather than left to the first request, and rather than left to Spring AI, because neither
 * of those says anything a person can act on. The SDK handed no credential looks for {@code
 * OPENAI_API_KEY} in the environment and then throws {@code At least one credential source must be
 * specified} from inside a client builder; handed no base URL it silently assumes {@code
 * api.openai.com}. And a blank <em>model</em> fails nowhere at all until a run is made, arriving as
 * {@code 400 InvalidParameter: The length of model should be between 1 and 512} — which reads as a
 * broken endpoint rather than an unset variable. Every one of those is a startup question answered
 * at the worst possible moment.
 *
 * <p>The two rules differ, and the difference is the point:
 *
 * <ul>
 *   <li><b>a connection</b> fails only when base URL <em>and</em> credential are both blank. A
 *       blank base URL alone is real OpenAI; a blank credential alone is a local server wanting no
 *       auth, which Spring AI treats as no-auth deliberately rather than as unconfigured. Only both
 *       being absent cannot be anybody's intention.
 *   <li><b>a model name</b> fails on its own, because there is no such thing as a default model on
 *       the wire: the SDK sends the field as given and every endpoint rejects an empty one.
 * </ul>
 *
 * <p>The message names the two ways a deployment in this repository configures itself, because
 * neither is obvious from the property that is missing: the {@code spring.ai.openai.*} settings
 * directly, or a provider module that fills them in — {@code spring-agent-provider-dashscope} does,
 * from one credential. This class must not name that module in a condition, only in prose: it is a
 * consumer of this one, not the other way round.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenAiConnectionCheck {

  private final OpenAiCommonProperties commonProperties;
  private final OpenAiChatProperties chatProperties;

  /**
   * Through a provider because an embedding model is not this provider's to assume: {@code
   * spring.ai.model.embedding} may have selected another one, in which case these properties are
   * not bound at all and the check does not apply.
   */
  private final ObjectProvider<OpenAiEmbeddingProperties> embeddingProperties;

  @PostConstruct
  void check() {
    final var resolved =
        OpenAiAutoConfigurationUtil.resolveCommonProperties(commonProperties, chatProperties);
    if (Strings.isNullOrEmpty(resolved.getBaseUrl())
        && Strings.isNullOrEmpty(resolved.getApiKey())) {
      throw new IllegalStateException(
          "No model endpoint is configured: both spring.ai.openai.base-url and"
              + " spring.ai.openai.api-key are empty. Set OPENAI_BASE_URL, OPENAI_API_KEY and"
              + " OPENAI_MODEL, or set DASHSCOPE_API_KEY to have"
              + " spring-agent-provider-dashscope supply them.");
    }
    // Through toOptions() rather than off the properties, because that is what the model bean is
    // built with: it is where `spring.ai.openai.chat.model`, `.chat.options.model` and the common
    // `model` are reconciled, and reading any one of them here would disagree with the request.
    if (Strings.isNullOrEmpty(chatProperties.toOptions().getModel())
        && Strings.isNullOrEmpty(resolved.getModel())) {
      throw new IllegalStateException(
          "No chat model is named: spring.ai.openai.chat.model is empty. Set OPENAI_MODEL, or"
              + " DASHSCOPE_CHAT_MODEL if spring-agent-provider-dashscope is supplying the"
              + " connection. There is no default model on the wire — an empty name is refused by"
              + " every endpoint.");
    }
    final var embedding = embeddingProperties.getIfAvailable();
    if (embedding != null && Strings.isNullOrEmpty(embedding.toOptions().getModel())) {
      // Not optional even on a deployment that indexes nothing: the tool-search advisor builds its
      // index by embedding tool descriptions, so this fails on the way into the first run.
      throw new IllegalStateException(
          "No embedding model is named: spring.ai.openai.embedding.model is empty. Set"
              + " EMBEDDING_MODEL, or DASHSCOPE_EMBEDDING_MODEL if spring-agent-provider-dashscope"
              + " is supplying the connection. It is needed even if you index nothing, because tool"
              + " search is built by embedding tool descriptions.");
    }
    // The endpoint and not the key, obviously, and at info because "which gateway is this talking
    // to" is the first question asked of a deployment that answers oddly.
    log.info(
        "Model endpoint: {}",
        Strings.isNullOrEmpty(resolved.getBaseUrl())
            ? "the OpenAI SDK's own default"
            : resolved.getBaseUrl());
  }
}
