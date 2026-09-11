package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.AudioTranscriptionTool;
import me.kezhenxu94.springagent.core.tools.ImageGenerationTools;
import me.kezhenxu94.springagent.core.tools.MediaSources;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.core.tools.VisionTools;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Offers the three tools that need a model other than the chat one, each only where the deployment
 * has that model.
 *
 * <p>Core ships none of them: an {@code ImageModel}, a {@code TranscriptionModel} and a vision
 * {@code ChatClient} all arrive with a {@code spring-agent-provider-*} module, and which of the
 * three a provider offers is the provider's business — DashScope's image API is not OpenAI's, and
 * plenty of endpoints serve no transcription at all. So a tool whose model is absent is not
 * registered, rather than registered and failing when the model reaches for it: a tool the model
 * can see is a tool it will try, and one that can only fail teaches it nothing except to try again.
 * That is the same reasoning as {@link KnowledgeToolsConfiguration}, and the same shape.
 *
 * <p>{@code afterName} rather than {@code after} for the reason that class documents at length:
 * {@code @ConditionalOnBean} is answered against what is registered by the time it runs, so a
 * provider contributing its models afterwards would lose in silence. Naming the class as a string
 * is what lets core order itself after a module it must not depend on. <b>Each {@code
 * spring-agent-provider-*} module's auto-configuration has to be listed here, and a module renaming
 * its own silently loses these tools.</b>
 *
 * <p>{@code @AgentTool} on the bean method rather than the class: the annotation is honoured on
 * factory methods, which is what lets a conditionally registered tool still be discovered by {@code
 * AgentToolsProvider.resolveScenarioTools}.
 */
@AutoConfiguration(
    afterName = {
      "me.kezhenxu94.springagent.provider.openai.OpenAiProviderAutoConfiguration",
      "me.kezhenxu94.springagent.provider.dashscope.DashScopeProviderAutoConfiguration"
    })
public class ModelToolsConfiguration {

  /**
   * Reads what a model named as an image — a local path, a {@code file://} URL, an {@code http(s)}
   * one — into bytes a provider can be handed. Shared by the two tools that take images in, rather
   * than a private method on each, because the workspace confinement it performs is the only thing
   * standing between a model naming {@code /etc/shadow} and a tool reading it, and a second copy of
   * that check is a second place for it to be got wrong.
   *
   * <p>Unconditional: it reaches no endpoint and holds no configuration, so a deployment with
   * neither an image nor a vision model simply never calls it.
   */
  @Bean
  @ConditionalOnMissingBean
  MediaSources mediaSources(final RestTemplate restTemplate) {
    return new MediaSources(restTemplate);
  }

  @Bean
  @AgentTool
  @ConditionalOnBean(ImageModel.class)
  @ConditionalOnMissingBean
  ImageGenerationTools imageGenerationTools(
      final RestTemplate restTemplate,
      final MediaSources mediaSources,
      final ImageModel imageModel,
      final UserWorkspaceFactory userWorkspaceFactory) {
    return new ImageGenerationTools(restTemplate, mediaSources, imageModel, userWorkspaceFactory);
  }

  /**
   * The vision tool, gated on the bean name rather than on a type: a vision model is a {@code
   * ChatClient} like the application's own, so a type condition would be answered by the wrong one
   * and offer {@code RecognizeImage} to every deployment.
   */
  @Bean
  @AgentTool
  @ConditionalOnBean(name = "visionChatClient")
  @ConditionalOnMissingBean
  VisionTools visionTools(
      final MediaSources mediaSources,
      final UserWorkspaceFactory userWorkspaceFactory,
      final CoreMessages messages,
      @Qualifier("visionChatClient") final ChatClient visionChatClient) {
    return new VisionTools(mediaSources, userWorkspaceFactory, messages, visionChatClient);
  }

  @Bean
  @AgentTool
  @ConditionalOnBean(TranscriptionModel.class)
  @ConditionalOnMissingBean
  AudioTranscriptionTool audioTranscriptionTool(final TranscriptionModel transcriptionModel) {
    return new AudioTranscriptionTool(transcriptionModel);
  }
}
