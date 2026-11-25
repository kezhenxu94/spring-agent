package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

class VisionImagesTest {

  @Test
  @DisplayName(
      "a full-resolution phone screenshot is scaled to a 1080px long side and sent as JPEG")
  void aLargeScreenshot() throws Exception {
    final var png = png(screenshot(1170, 2532));
    assertThat(png.length).isGreaterThan(VisionImages.MAX_BYTES);

    final var shrunk = VisionImages.shrink(media(png, MimeTypeUtils.IMAGE_PNG));

    assertThat(shrunk.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    assertThat(shrunk.getName()).isEqualTo("screenshot.png");
    final var bytes = (byte[]) shrunk.getData();
    assertThat(bytes.length).isLessThan(VisionImages.MAX_BYTES);
    final var image = ImageIO.read(new ByteArrayInputStream(bytes));
    assertThat(image.getHeight()).isEqualTo(1080);
    assertThat(image.getWidth()).isEqualTo(Math.round(1170 * 1080 / 2532f));
  }

  @Test
  @DisplayName("a wide image is scaled by its width")
  void aWideImage() throws Exception {
    final var shrunk =
        VisionImages.shrink(media(png(screenshot(2000, 500)), MimeTypeUtils.IMAGE_PNG));

    final var image = ImageIO.read(new ByteArrayInputStream((byte[]) shrunk.getData()));
    assertThat(image.getWidth()).isEqualTo(1080);
    assertThat(image.getHeight()).isEqualTo(270);
  }

  @Test
  @DisplayName("an image within both limits is sent unchanged")
  void aSmallImage() throws Exception {
    final var png = png(screenshot(400, 300));
    assertThat(png.length).isLessThan(VisionImages.MAX_BYTES);
    final var original = media(png, MimeTypeUtils.IMAGE_PNG);

    assertThat(VisionImages.shrink(original)).isSameAs(original);
  }

  @Test
  @DisplayName("an image within the size limit but too heavy is re-encoded at its own size")
  void aHeavySmallImage() throws Exception {
    final var png = png(noise(1000, 1000));
    assertThat(png.length).isGreaterThan(VisionImages.MAX_BYTES);

    final var shrunk = VisionImages.shrink(media(png, MimeTypeUtils.IMAGE_PNG));

    assertThat(shrunk.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    final var image = ImageIO.read(new ByteArrayInputStream((byte[]) shrunk.getData()));
    assertThat(image.getWidth()).isEqualTo(1000);
    assertThat(image.getHeight()).isEqualTo(1000);
  }

  @Test
  @DisplayName("transparency is flattened onto white")
  void aTransparentImage() throws Exception {
    final var transparent = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_ARGB);

    final var shrunk = VisionImages.shrink(media(png(transparent), MimeTypeUtils.IMAGE_PNG));

    final var image = ImageIO.read(new ByteArrayInputStream((byte[]) shrunk.getData()));
    final var pixel = new Color(image.getRGB(540, 540));
    assertThat(pixel.getRed()).isGreaterThan(250);
    assertThat(pixel.getGreen()).isGreaterThan(250);
    assertThat(pixel.getBlue()).isGreaterThan(250);
  }

  @Test
  @DisplayName("bytes that are not a decodable image are sent unchanged")
  void notAnImage() {
    final var original =
        media("pretend-png-bytes".getBytes(StandardCharsets.UTF_8), MimeTypeUtils.IMAGE_PNG);

    assertThat(VisionImages.shrink(original)).isSameAs(original);
  }

  private static Media media(final byte[] bytes, final org.springframework.util.MimeType type) {
    return Media.builder().name("screenshot.png").mimeType(type).data(bytes).build();
  }

  /** Lines of text over a noisy background, so the PNG is as heavy as a real screenshot. */
  private static BufferedImage screenshot(final int width, final int height) {
    final var image = noise(width, height);
    final var g = image.createGraphics();
    g.setColor(Color.BLACK);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
    for (var y = 60; y < height; y += 80) {
      g.drawString("Line " + y + ": the quick brown fox", 40, y);
    }
    g.dispose();
    return image;
  }

  private static BufferedImage noise(final int width, final int height) {
    final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final var random = new Random(42);
    for (var x = 0; x < width; x++) {
      for (var y = 0; y < height; y++) {
        image.setRGB(x, y, 0xC0C0C0 | random.nextInt(0x3F3F3F));
      }
    }
    return image;
  }

  private static byte[] png(final BufferedImage image) throws Exception {
    final var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }
}
