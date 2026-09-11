package me.kezhenxu94.springagent.provider.googlegenai;

import com.google.common.base.Strings;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ImageGenerationMetadata;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.image.GoogleGenAiImageOptions;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

/**
 * The two places Gemini's image API is shaped differently from what core's {@code GenerateImage}
 * hands it. A decorator rather than an image model of its own, because unlike DashScope's this
 * <em>is</em> the API Spring AI already speaks — only the request needs translating.
 *
 * <h2>Reference images have to be bytes</h2>
 *
 * <p>Spring AI's {@code GoogleGenAiImageModel} maps a {@code Media} carrying a {@code URI} to
 * {@code Part.fromUri}, which on the Gemini Developer API means {@code fileData.fileUri} — a Files
 * API or GCS URI, and never an arbitrary public link. So a URL that any other provider would simply
 * fetch is refused here, with a message about the file rather than about the link.
 *
 * <p>Core resolves a local path or {@code file://} URL to bytes before this is ever reached (see
 * {@code MediaSources}), which is the common case and needs nothing doing. What is left is a
 * genuine {@code http(s)} URL — a picture the model found on the web — and that is downloaded here,
 * because someone has to and Gemini will not. Doing it in the decorator rather than in core keeps
 * the download where the reason for it lives: it is a fact about this endpoint, not about the tool.
 *
 * <h2>Size is two fields, not one</h2>
 *
 * <p>{@code ImageGenerationMetadata.SIZE} arrives as the model typed it and untranslated, which is
 * that contract's whole point. {@link GoogleGenAiImageOptions} splits what other providers call one
 * thing into {@code aspectRatio} ({@code 16:9}) and {@code imageSize} ({@code 2K}), so the two
 * vocabularies have to be told apart here. An explicit {@code 1024x1024} is reduced to the ratio it
 * describes, since there is no field for pixels. Anything unrecognised means the endpoint's own
 * default and a log line — never a guess, for the reason {@code OpenAiAgentImageModel} gives.
 *
 * <p>{@code ImageGenerationMetadata.THINKING_MODE} is read by no field here and is ignored.
 * Gemini's image models do reason, but the budget for it is not exposed through {@code
 * GoogleGenAiImageOptions}; there is nothing to set, rather than something being forgotten.
 */
@Slf4j
@RequiredArgsConstructor
public class GoogleGenAiAgentImageModel implements ImageModel {

  /** {@code WIDTHxHEIGHT}, which this API has no field for and which is reduced to a ratio. */
  private static final Pattern PIXELS = Pattern.compile("(\\d{2,5})\\s*[xX*]\\s*(\\d{2,5})");

  private static final Pattern RATIO = Pattern.compile("\\d{1,2}\\s*:\\s*\\d{1,2}");

  /** What {@code imageSize} accepts, as the endpoint spells it. */
  private static final List<String> IMAGE_SIZES = List.of("1K", "2K", "4K");

  /**
   * The ratios worth reducing a pixel size to, as width/height. Kept short on purpose: these are
   * the ones Gemini documents, and mapping a stray size onto a ratio the endpoint does not accept
   * would turn a harmless unrecognised value into a refused request.
   */
  private static final Map<String, Double> RATIOS =
      Map.of(
          "1:1", 1.0,
          "16:9", 16.0 / 9,
          "9:16", 9.0 / 16,
          "4:3", 4.0 / 3,
          "3:4", 3.0 / 4);

  private final RestTemplate restTemplate;
  private final ImageModel delegate;

  @Override
  public ImageResponse call(final ImagePrompt request) {
    return delegate.call(new ImagePrompt(fetched(request), sized(request)));
  }

  /**
   * Every instruction again, with any {@code http(s)} reference downloaded into bytes.
   *
   * <p>A reference that cannot be fetched is dropped with a warning rather than failing the call,
   * which is the choice core's tool already makes about a reference it cannot read: the prompt
   * still says what was wanted, so generating from fewer references beats generating nothing.
   */
  private List<ImageMessage> fetched(final ImagePrompt request) {
    final var messages = new ArrayList<ImageMessage>();
    for (final var message : request.getInstructions()) {
      if (message.getMedia().stream().noneMatch(GoogleGenAiAgentImageModel::isRemote)) {
        messages.add(message);
        continue;
      }
      final var media = new ArrayList<Media>();
      for (final var one : message.getMedia()) {
        if (!isRemote(one)) {
          media.add(one);
          continue;
        }
        final var downloaded = download(one);
        if (downloaded != null) {
          media.add(downloaded);
        }
      }
      messages.add(
          new ImageMessage(message.getText(), message.getWeight(), media, message.getMetadata()));
    }
    return messages;
  }

  private static boolean isRemote(final Media media) {
    final var data = media.getData();
    if (!(data instanceof URI || data instanceof String)) {
      return false;
    }
    final var text = data.toString();
    return text.startsWith("http://") || text.startsWith("https://");
  }

  private Media download(final Media media) {
    final var url = media.getData().toString();
    final var startedAt = System.nanoTime();
    try {
      final var bytes = restTemplate.getForObject(URI.create(url), byte[].class);
      if (bytes == null || bytes.length == 0) {
        log.warn("Nothing to read at reference image URL {}; generating without it", url);
        return null;
      }
      log.info(
          "Downloaded reference image {} for Gemini, size={} bytes, took {} ms — Gemini will not"
              + " fetch a link itself",
          url,
          bytes.length,
          (System.nanoTime() - startedAt) / 1_000_000);
      return Media.builder()
          .name(media.getName())
          .mimeType(media.getMimeType() == null ? MimeTypeUtils.IMAGE_PNG : media.getMimeType())
          .data(bytes)
          .build();
    } catch (Exception e) {
      log.warn("Could not fetch reference image {}; generating without it", url, e);
      return null;
    }
  }

  /**
   * The options with whatever the model asked for as a size put into whichever of the two fields
   * can carry it.
   */
  GoogleGenAiImageOptions sized(final ImagePrompt request) {
    final var builder = GoogleGenAiImageOptions.builder().merge(request.getOptions());
    final var asked = requestedSize(request);
    if (Strings.isNullOrEmpty(asked)) {
      return builder.build();
    }

    final var trimmed = asked.trim();
    final var upper = trimmed.toUpperCase(Locale.ROOT);
    if (IMAGE_SIZES.contains(upper)) {
      log.info("Generating at imageSize={} (asked for '{}')", upper, asked);
      return builder.imageSize(upper).build();
    }
    if (RATIO.matcher(trimmed).matches()) {
      final var ratio = trimmed.replaceAll("\\s", "");
      if (!RATIOS.containsKey(ratio)) {
        log.info(
            "Aspect ratio '{}' is not one Gemini documents ({}); using the endpoint's default",
            asked,
            RATIOS.keySet());
        return builder.build();
      }
      log.info("Generating at aspectRatio={} (asked for '{}')", ratio, asked);
      return builder.aspectRatio(ratio).build();
    }

    final var pixels = PIXELS.matcher(trimmed);
    if (pixels.matches()) {
      final var nearest =
          nearestRatio(Double.parseDouble(pixels.group(1)) / Double.parseDouble(pixels.group(2)));
      log.info(
          "Gemini takes no pixel size, so '{}' becomes the nearest aspect ratio it documents, {}",
          asked,
          nearest);
      return builder.aspectRatio(nearest).build();
    }

    log.info(
        "Unrecognised image size '{}'; using the endpoint's default. Gemini takes an aspect ratio"
            + " ({}) or an image size ({})",
        asked,
        RATIOS.keySet(),
        IMAGE_SIZES);
    return builder.build();
  }

  private static String nearestRatio(final double wanted) {
    return RATIOS.entrySet().stream()
        .min(
            (a, b) ->
                Double.compare(Math.abs(a.getValue() - wanted), Math.abs(b.getValue() - wanted)))
        .orElseThrow()
        .getKey();
  }

  private static String requestedSize(final ImagePrompt request) {
    return request.getInstructions().stream()
        .map(ImageMessage::getMetadata)
        .filter(metadata -> metadata != null)
        .map(metadata -> metadata.get(ImageGenerationMetadata.SIZE))
        .filter(size -> size instanceof String)
        .map(String.class::cast)
        .filter(size -> !size.isBlank())
        .findFirst()
        .orElse(null);
  }
}
