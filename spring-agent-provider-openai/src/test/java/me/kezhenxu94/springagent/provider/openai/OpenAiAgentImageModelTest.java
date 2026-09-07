package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.tools.ImageGenerationMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.content.Media;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.util.MimeTypeUtils;

/** The two things this decorator exists for; see {@link OpenAiAgentImageModel}. */
class OpenAiAgentImageModelTest {

  private final OpenAiImageModel delegate = mock(OpenAiImageModel.class);
  private final OpenAiAgentImageModel model = new OpenAiAgentImageModel(delegate);

  private ImagePrompt prompt(final List<Media> media, final Map<String, Object> metadata) {
    return new ImagePrompt(
        List.of(new ImageMessage("a cat", null, media, metadata)),
        ImageOptionsBuilder.builder().n(1).model("gpt-image-1").build());
  }

  private ImagePrompt sent() {
    when(delegate.call(any()))
        .thenReturn(new ImageResponse(List.of(new ImageGeneration(new Image("u", null)))));
    final var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(delegate).call(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("a reference image fails the call rather than being silently dropped")
  void referenceImagesAreRefused() {
    // The failure worth preventing: /v1/images/generations has no image input and Spring AI ignores
    // getMedia(), so the alternative is an unrelated picture reported as a success — which neither
    // the agent nor the person reading its answer could tell apart from the real thing.
    assertThatThrownBy(
            () ->
                model.call(
                    prompt(
                        List.of(
                            Media.builder()
                                .name("ref")
                                .mimeType(MimeTypeUtils.IMAGE_PNG)
                                .data(URI.create("https://example.test/ref.png"))
                                .build()),
                        Map.of())))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("cannot generate from a reference image");

    verify(delegate, never()).call(any());
  }

  @Test
  @DisplayName("an aspect ratio becomes the size OpenAI's API names")
  void aspectRatiosAreTranslated() {
    when(delegate.call(any()))
        .thenReturn(new ImageResponse(List.of(new ImageGeneration(new Image("u", null)))));

    model.call(prompt(List.of(), Map.of(ImageGenerationMetadata.SIZE, "16:9")));

    final var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(delegate).call(captor.capture());
    assertThat(((OpenAiImageOptions) captor.getValue().getOptions()).getSize())
        .isEqualTo("1536x1024");
    // merge(), not a field-by-field copy: what the caller asked for has to survive.
    assertThat(captor.getValue().getOptions().getModel()).isEqualTo("gpt-image-1");
    assertThat(captor.getValue().getOptions().getN()).isEqualTo(1);
  }

  @Test
  @DisplayName("an explicit WIDTHxHEIGHT is passed through")
  void explicitSizesArePassedThrough() {
    when(delegate.call(any()))
        .thenReturn(new ImageResponse(List.of(new ImageGeneration(new Image("u", null)))));

    model.call(prompt(List.of(), Map.of(ImageGenerationMetadata.SIZE, "1024x1024")));

    final var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(delegate).call(captor.capture());
    assertThat(((OpenAiImageOptions) captor.getValue().getOptions()).getSize())
        .isEqualTo("1024x1024");
  }

  @Test
  @DisplayName("a size this endpoint has no spelling for leaves its own default in place")
  void unknownSizesAreLeftToTheEndpoint() {
    when(delegate.call(any()))
        .thenReturn(new ImageResponse(List.of(new ImageGeneration(new Image("u", null)))));

    // '2K' is DashScope's vocabulary. Ignoring it is right: the alternative is guessing at pixels
    // the caller did not ask for, or failing a call that would otherwise have produced an image.
    model.call(prompt(List.of(), Map.of(ImageGenerationMetadata.SIZE, "2K")));

    final var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(delegate).call(captor.capture());
    assertThat(captor.getValue().getOptions()).isNotInstanceOf(OpenAiImageOptions.class);
  }

  @Test
  @DisplayName("no size asked for means the options are handed on untouched")
  void noSizeChangesNothing() {
    when(delegate.call(any()))
        .thenReturn(new ImageResponse(List.of(new ImageGeneration(new Image("u", null)))));
    final var request = prompt(List.of(), Map.of());

    model.call(request);

    final var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(delegate).call(captor.capture());
    assertThat(captor.getValue().getOptions()).isSameAs(request.getOptions());
  }
}
