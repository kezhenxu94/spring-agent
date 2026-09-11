package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import me.kezhenxu94.springagent.core.tools.ImageGenerationMetadata;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.image.GoogleGenAiImageOptions;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

/**
 * The two translations {@link GoogleGenAiAgentImageModel} exists for, pinned against a real HTTP
 * server for the half that reaches one.
 *
 * <p>The reference-image assertions are the point of the whole change: bytes must reach the
 * endpoint untouched and <b>without anything being fetched</b>, because the common case is a file
 * this machine already has and the round trip through a public URL is what the change removes.
 */
class GoogleGenAiAgentImageModelTest {

  private MockWebServer server;
  private final AtomicReference<ImagePrompt> seen = new AtomicReference<>();

  /**
   * Stands in for Spring AI's image model. A recording stub rather than the real one because what
   * is being asserted is the request this class hands <em>on</em>, which is where both translations
   * happen; what Spring AI then puts on the wire is Spring AI's own tested business.
   */
  private final ImageModel delegate =
      prompt -> {
        seen.set(prompt);
        return new ImageResponse(List.of());
      };

  private GoogleGenAiAgentImageModel model;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    model = new GoogleGenAiAgentImageModel(new RestTemplate(), delegate);
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  private static ImagePrompt prompt(final List<Media> media, final Map<String, Object> metadata) {
    return new ImagePrompt(
        List.of(new ImageMessage("a cat", null, media, metadata)),
        GoogleGenAiImageOptions.builder().n(1).build());
  }

  private static Media bytes(final String name) {
    return Media.builder()
        .name(name)
        .mimeType(MimeTypeUtils.IMAGE_PNG)
        .data("not-really-a-png".getBytes(StandardCharsets.UTF_8))
        .build();
  }

  // --- reference images -------------------------------------------------------------------

  @Test
  @DisplayName("a local reference is already bytes and nothing is fetched")
  void bytesArePassedThroughWithoutFetching() {
    model.call(prompt(List.of(bytes("generated-1.png")), Map.of()));

    final var media = seen.get().getInstructions().get(0).getMedia();
    assertThat(media).hasSize(1);
    assertThat(media.get(0).getData()).isInstanceOf(byte[].class);
    assertThat(media.get(0).getName()).isEqualTo("generated-1.png");
    // The whole point: core resolved a file:// URL or a path into bytes, so there is no publishing
    // and no download. A request here would mean the round trip had crept back in.
    assertThat(server.getRequestCount())
        .as("a local reference must not cause any HTTP call at all")
        .isZero();
  }

  @Test
  @DisplayName("an http reference is fetched here, because Gemini will not fetch it")
  void remoteReferencesAreDownloaded() throws Exception {
    server.enqueue(new MockResponse().setBody("the-image-bytes"));
    final var url = server.url("/cat.png").toString();

    model.call(
        prompt(
            List.of(
                Media.builder()
                    .name(url)
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(URI.create(url))
                    .build()),
            Map.of()));

    final var media = seen.get().getInstructions().get(0).getMedia();
    assertThat(media).hasSize(1);
    // A URI here would become Part.fromUri, i.e. fileData.fileUri, which the Gemini Developer API
    // accepts only for a Files API or GCS URI — so a public link is refused.
    assertThat(media.get(0).getData())
        .isInstanceOf(byte[].class)
        .isEqualTo("the-image-bytes".getBytes(StandardCharsets.UTF_8));
    assertThat(server.takeRequest().getPath()).isEqualTo("/cat.png");
  }

  @Test
  @DisplayName("a reference that cannot be fetched is dropped, not fatal")
  void anUnfetchableReferenceIsDropped() {
    server.enqueue(new MockResponse().setResponseCode(404));
    final var url = server.url("/gone.png").toString();

    model.call(
        prompt(
            List.of(
                bytes("kept.png"),
                Media.builder()
                    .name(url)
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(URI.create(url))
                    .build()),
            Map.of()));

    // The prompt still says what was wanted, so generating from the references that did resolve
    // beats generating nothing.
    final var media = seen.get().getInstructions().get(0).getMedia();
    assertThat(media).hasSize(1);
    assertThat(media.get(0).getName()).isEqualTo("kept.png");
  }

  @Test
  @DisplayName("a prompt with no references is handed on as it came")
  void noReferencesIsUntouched() {
    final var original = prompt(List.of(), Map.of());
    model.call(original);
    assertThat(seen.get().getInstructions().get(0).getMedia()).isEmpty();
    assertThat(server.getRequestCount()).isZero();
  }

  // --- size -------------------------------------------------------------------------------

  private GoogleGenAiImageOptions sizedWith(final String size) {
    return model.sized(prompt(List.of(), Map.of(ImageGenerationMetadata.SIZE, size)));
  }

  @Test
  @DisplayName("an aspect ratio reaches aspectRatio")
  void aspectRatios() {
    assertThat(sizedWith("16:9").getAspectRatio()).isEqualTo("16:9");
    assertThat(sizedWith("1:1").getAspectRatio()).isEqualTo("1:1");
    // Whitespace a model typed is not a different ratio.
    assertThat(sizedWith(" 9:16 ").getAspectRatio()).isEqualTo("9:16");
    assertThat(sizedWith("16:9").getImageSize()).isNull();
  }

  @Test
  @DisplayName("an image size reaches imageSize, which is a different field")
  void imageSizes() {
    assertThat(sizedWith("2K").getImageSize()).isEqualTo("2K");
    // Case is the model's choice, not the endpoint's vocabulary.
    assertThat(sizedWith("4k").getImageSize()).isEqualTo("4K");
    assertThat(sizedWith("2K").getAspectRatio()).isNull();
  }

  @Test
  @DisplayName("pixels become the nearest ratio, since there is no field for them")
  void pixelSizesBecomeRatios() {
    assertThat(sizedWith("1024x1024").getAspectRatio()).isEqualTo("1:1");
    // 1.5 is nearer 4:3 (1.333) than 16:9 (1.778), and 3:2 is not a ratio Gemini documents.
    assertThat(sizedWith("1536x1024").getAspectRatio()).isEqualTo("4:3");
    assertThat(sizedWith("1920x1080").getAspectRatio()).isEqualTo("16:9");
    assertThat(sizedWith("1080x1920").getAspectRatio()).isEqualTo("9:16");
  }

  @Test
  @DisplayName("an unrecognised size means the endpoint's default, never a guess")
  void unrecognisedSizes() {
    // A size the provider does not recognise means its own default and not a failure — the contract
    // ImageGenerationMetadata.SIZE states, and what the tool description promises the model.
    assertThat(sizedWith("gigantic").getAspectRatio()).isNull();
    assertThat(sizedWith("gigantic").getImageSize()).isNull();
    // A well-formed ratio Gemini does not document is left alone rather than mapped onto a nearby
    // one, which would turn a harmless unknown into a refused request.
    assertThat(sizedWith("21:9").getAspectRatio()).isNull();
    assertThat(sizedWith("8K").getImageSize()).isNull();
  }

  @Test
  @DisplayName("no size at all leaves the options as they came")
  void noSize() {
    final var options = model.sized(prompt(List.of(), Map.of()));
    assertThat(options.getAspectRatio()).isNull();
    assertThat(options.getImageSize()).isNull();
    assertThat(options.getN()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "thinkingMode is understood to be unsupported, and ignored rather than mistranslated")
  void thinkingModeIsIgnored() {
    final var options =
        model.sized(
            prompt(
                List.of(),
                Map.of(
                    ImageGenerationMetadata.THINKING_MODE,
                    true,
                    ImageGenerationMetadata.SIZE,
                    "2K")));
    // GoogleGenAiImageOptions exposes no thinking budget, so there is nothing to set. The size
    // beside it still has to come through, which is what says the key was ignored and not fatal.
    assertThat(options.getImageSize()).isEqualTo("2K");
  }
}
