package me.kezhenxu94.springagent.core.tools;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

/**
 * Shrinks an image before it is sent to the vision model.
 *
 * <p>A full-resolution phone screenshot is ~5 MB of PNG inlined as base64, which a vision endpoint
 * may fail to take in time, while vision models downscale large images themselves anyway. An image
 * whose long side exceeds {@link #MAX_LONG_SIDE} or whose bytes exceed {@link #MAX_BYTES} is scaled
 * down to fit and re-encoded as JPEG; area averaging and a high quality keep small text legible.
 * Anything else, or anything that cannot be decoded, is passed through unchanged.
 */
@Slf4j
final class VisionImages {
  static final int MAX_LONG_SIDE = 1080;
  static final int MAX_BYTES = 1024 * 1024;
  private static final float JPEG_QUALITY = 0.85f;

  private VisionImages() {}

  static Media shrink(final Media media) {
    if (!(media.getData() instanceof byte[] bytes)) {
      return media;
    }
    try {
      final var image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null) {
        log.info("Sending image {} as is: not decodable here", media.getName());
        return media;
      }
      final var width = image.getWidth();
      final var height = image.getHeight();
      final var longSide = Math.max(width, height);
      if (longSide <= MAX_LONG_SIDE && bytes.length <= MAX_BYTES) {
        return media;
      }
      final var scale = Math.min(1.0, (double) MAX_LONG_SIDE / longSide);
      final var newWidth = Math.max(1, (int) Math.round(width * scale));
      final var newHeight = Math.max(1, (int) Math.round(height * scale));
      final var jpeg = toJpeg(image, newWidth, newHeight);
      log.info(
          "Shrunk image {} for the vision model from {}x{} ({} bytes, {}) to {}x{} ({} bytes,"
              + " image/jpeg)",
          media.getName(),
          width,
          height,
          bytes.length,
          media.getMimeType(),
          newWidth,
          newHeight,
          jpeg.length);
      return Media.builder()
          .name(media.getName())
          .mimeType(MimeTypeUtils.IMAGE_JPEG)
          .data(jpeg)
          .build();
    } catch (IOException | RuntimeException e) {
      log.warn("Sending image {} as is: failed to shrink it", media.getName(), e);
      return media;
    }
  }

  private static byte[] toJpeg(final BufferedImage image, final int width, final int height)
      throws IOException {
    final var scaled =
        width == image.getWidth() && height == image.getHeight()
            ? image
            : image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING);
    // JPEG has no alpha: flatten transparency onto white, as a viewer would show it.
    final var rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final var g = rgb.createGraphics();
    try {
      g.drawImage(scaled, 0, 0, Color.WHITE, null);
    } finally {
      g.dispose();
    }
    final var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    final var out = new ByteArrayOutputStream();
    try (final var ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      final var param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      writer.write(null, new IIOImage(rgb, null, null), param);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }
}
