package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Locale;
import me.kezhenxu94.springagent.core.tools.AudioTranscriptionTool;
import me.kezhenxu94.springagent.core.tools.ImageGenerationTools;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.core.tools.VisionTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.web.client.RestTemplate;

/**
 * Whether the three tools that need a model of their own are offered to a run, which is decided by
 * whether the deployment's provider published that model.
 *
 * <p>Worth its own test because both failure directions are silent. A tool whose bean method is
 * never reached is simply absent — nothing logs it, and the symptom is a model that never generates
 * an image and cannot say why. A tool registered without its model would instead be offered and
 * fail on every call, which reads to the model as an endpoint to retry rather than a feature that
 * is off.
 *
 * <p>It also covers being listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}: an
 * auto-configuration missing from that file contributes nothing at all, with no error anywhere.
 */
class ModelToolsConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ModelToolsConfiguration.class))
          .withUserConfiguration(Collaborators.class);

  @Test
  @DisplayName("no models means none of the three tools, rather than three that fail")
  void noProviderNoTools() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(ImageGenerationTools.class);
          assertThat(context).doesNotHaveBean(VisionTools.class);
          assertThat(context).doesNotHaveBean(AudioTranscriptionTool.class);
        });
  }

  @Test
  @DisplayName("an ImageModel is what GenerateImage needs to exist")
  void anImageModelRegistersTheImageTool() {
    runner
        .withBean(ImageModel.class, () -> mock(ImageModel.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(ImageGenerationTools.class);
              assertThat(context).doesNotHaveBean(VisionTools.class);
            });
  }

  @Test
  @DisplayName("a TranscriptionModel is what TranscribeAudio needs to exist")
  void aTranscriptionModelRegistersTheAudioTool() {
    runner
        .withBean(TranscriptionModel.class, () -> mock(TranscriptionModel.class))
        .run(context -> assertThat(context).hasSingleBean(AudioTranscriptionTool.class));
  }

  @Test
  @DisplayName("the vision tool follows the bean's name, not its type")
  void theVisionToolFollowsTheBeanName() {
    // The distinction is the point: a vision model is a ChatClient like the application's own, so a
    // type condition would be answered by whichever ChatClient happened to be there and offer
    // RecognizeImage to every deployment. Only a bean actually called visionChatClient counts.
    runner
        .withBean("chatClient", ChatClient.class, () -> mock(ChatClient.class))
        .run(context -> assertThat(context).doesNotHaveBean(VisionTools.class));

    runner
        .withBean("visionChatClient", ChatClient.class, () -> mock(ChatClient.class))
        .run(context -> assertThat(context).hasSingleBean(VisionTools.class));
  }

  /** What the tools take besides a model, none of which decides whether they exist. */
  @Configuration(proxyBeanMethods = false)
  static class Collaborators {

    @Bean
    RestTemplate restTemplate() {
      return new RestTemplate();
    }

    @Bean
    UserWorkspaceFactory userWorkspaceFactory() {
      return mock(UserWorkspaceFactory.class);
    }

    @Bean
    CoreMessages coreMessages() {
      return new CoreMessages(
          new StaticMessageSource(), new SpringAgentProperties(null, Locale.ENGLISH, null, null));
    }
  }
}
