package me.kezhenxu94.springagent.provider.openai;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ImageGenerationMetadata;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;

/**
 * Spring AI's {@link OpenAiImageModel} with the two things this project's {@code GenerateImage}
 * tool asks for that it does not read: reference images, and a size named as an aspect ratio.
 *
 * <p><b>Reference images fail here rather than being dropped.</b> {@code /v1/images/generations}
 * has no image input at all — editing an image is {@code /v1/images/edits}, which Spring AI does
 * not call — and {@code OpenAiImageModel} ignores {@code ImageMessage.getMedia()} in silence. So a
 * run that asks for "this photo, as a watercolour" would get an unrelated watercolour reported as a
 * success, and neither the agent nor the person reading its answer would have any way to tell. An
 * exception carrying the reason is a tool error the agent can report and work around; a wrong
 * picture is not.
 *
 * <p><b>Sizes are a vocabulary, not a number.</b> The tool takes whatever the model typed and hands
 * it to the provider, because providers do not agree on the spelling: DashScope takes {@code 2K}
 * and {@code 16:9}, OpenAI takes {@code 1024x1024}. The three ratios below are translated so the
 * same tool call means the same thing on either; anything else is left to the endpoint's own
 * default rather than guessed at, and said once in the log.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenAiAgentImageModel implements ImageModel {

  /**
   * What an aspect ratio means here. The landscape and portrait pairs are the sizes {@code
   * gpt-image-1} and {@code dall-e-3} both accept; a gateway that accepts neither answers with its
   * own rejection, which {@link OpenAiErrorBodyLoggingInterceptor} makes readable.
   */
  private static final Map<String, String> RATIOS =
      Map.of(
          "1:1", "1024x1024",
          "16:9", "1536x1024",
          "9:16", "1024x1536");

  private final OpenAiImageModel delegate;

  @Override
  public ImageResponse call(final ImagePrompt request) {
    final var media =
        request.getInstructions().stream().flatMap(message -> message.getMedia().stream()).toList();
    if (!media.isEmpty()) {
      throw new UnsupportedOperationException(
          "This deployment generates images with OpenAI, whose image API takes a prompt only and"
              + " cannot generate from a reference image. Describe what the reference shows in the"
              + " prompt instead, or ask an administrator to configure a provider that supports"
              + " reference images.");
    }
    return delegate.call(new ImagePrompt(request.getInstructions(), sized(request)));
  }

  /**
   * The prompt's options with a size written over them where the metadata named one this endpoint
   * understands, and untouched otherwise.
   */
  private ImageOptions sized(final ImagePrompt request) {
    final var requested =
        request.getInstructions().stream()
            .map(message -> message.getMetadata().get(ImageGenerationMetadata.SIZE))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .findFirst()
            .orElse(null);
    if (requested == null || requested.isBlank()) {
      return request.getOptions();
    }
    final var size =
        RATIOS.getOrDefault(requested, requested.matches("\\d+x\\d+") ? requested : null);
    if (size == null) {
      log.info(
          "Ignoring image size '{}': OpenAI's image API names sizes as WIDTHxHEIGHT, so the"
              + " endpoint's own default is used instead",
          requested);
      return request.getOptions();
    }
    // merge rather than field-by-field: it is Spring AI's own copy of a portable ImageOptions into
    // the OpenAI one, so a field added to that interface arrives here without this class knowing.
    final var builder = OpenAiImageOptions.builder();
    if (request.getOptions() != null) {
      builder.merge(request.getOptions());
    }
    return builder.size(size).build();
  }
}
