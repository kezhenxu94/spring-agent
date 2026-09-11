package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import me.kezhenxu94.springagent.core.config.UserModelsProperties;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What this module contributes to bring-your-own-model, and when.
 *
 * <p>{@link ProviderChatClients} is published wherever user models are switched on, <b>whether or
 * not this module built the application's chat model</b> — that is what lets somebody register an
 * OpenAI endpoint on a deployment serving chat from Gemini. Core's dispatcher picks between
 * providers per row, so this one has to exist wherever it might be named.
 *
 * <p>{@link ReasoningEffortInForce} is the opposite: it answers for the model the deployment itself
 * runs, so it belongs to whichever module built that. Getting these two backwards is how a card
 * ends up labelling a Gemini run with an OpenAI setting.
 */
class OpenAiUserModelsBackOffTest {

  /** A key, since user models are switched on by there being one that can seal a token. */
  private static final String ENCRYPTION_KEY =
      "app.ai.user-models.encryption-key=" + "A".repeat(43) + "=";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiProviderAutoConfiguration.class))
          // The beans core contributes that these methods take. Supplied by hand rather than by
          // standing up core's whole auto-configuration, which would drag in persistence and a
          // vector store to assert one condition.
          .withUserConfiguration(CoreStandIns.class);

  /** What core publishes and this module consumes, and nothing else. */
  @Configuration(proxyBeanMethods = false)
  static class CoreStandIns {

    @Bean("chatClient")
    ChatClient chatClient() {
      return ChatClient.builder(mock(ChatModel.class)).build();
    }

    @Bean
    UserModelRegistry userModelRegistry() {
      return new UserModelRegistry(
          mock(UserModelConfigRepo.class),
          new AesGcmSealer(Base64.getEncoder().encodeToString(new byte[32]), "t"),
          3);
    }

    @Bean
    UserModelsProperties userModelsProperties() {
      return new UserModelsProperties(null, 0, 0, null);
    }
  }

  @Test
  @DisplayName("with an OpenAI chat model, this module serves bring-your-own-model")
  void withAChatModel() {
    runner
        .withPropertyValues(
            ENCRYPTION_KEY,
            "spring.ai.openai.base-url=https://gateway.example.com/v1",
            "spring.ai.openai.api-key=sk-test",
            "spring.ai.openai.chat.options.model=gpt-5.6")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(OpenAiChatModel.class);
              assertThat(context).hasSingleBean(ProviderChatClients.class);
              assertThat(context).hasSingleBean(BuiltinModels.class);
              assertThat(context).hasSingleBean(ReasoningEffortInForce.class);
            });
  }

  @Test
  @DisplayName("without one, it still offers its protocol but claims nothing about the deployment")
  void withoutAChatModel() {
    // spring.ai.model.chat naming somebody else is exactly what a google-genai deployment sets.
    runner
        .withPropertyValues(ENCRYPTION_KEY, "spring.ai.model.chat=google-genai")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(OpenAiChatModel.class);
              // Still published: a user may register an OpenAI endpoint here even though the
              // deployment's own chat model is somebody else's. This is the bean that makes the
              // provider select in /config more than decoration.
              assertThat(context).hasSingleBean(ProviderChatClients.class);
              assertThat(context.getBean(ProviderChatClients.class).provider())
                  .isEqualTo(OpenAiUserChatClients.PROVIDER);
              // These two speak for the deployment's own model, which this module did not build.
              assertThat(context).doesNotHaveBean(BuiltinModels.class);
              assertThat(context).doesNotHaveBean(ReasoningEffortInForce.class);
            });
  }

  @Test
  @DisplayName("and with no encryption key there is no bring-your-own-model either way")
  void withoutAKey() {
    runner
        .withPropertyValues(
            "spring.ai.openai.base-url=https://gateway.example.com/v1",
            "spring.ai.openai.api-key=sk-test",
            "spring.ai.openai.chat.options.model=gpt-5.6")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ProviderChatClients.class);
              assertThat(context).doesNotHaveBean(BuiltinModels.class);
              // But the effort in force is published regardless: a deployment where nobody may
              // choose still has one, and a surface should not have to know the difference.
              assertThat(context).hasSingleBean(ReasoningEffortInForce.class);
            });
  }
}
