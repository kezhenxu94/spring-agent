package me.kezhenxu94.springagent.integration.websocket.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What this record decides for a deployment that left something out. */
class WebPropertiesTest {

  @Test
  @DisplayName("a tab icon set apart from the logo is kept apart from it")
  void anExplicitFaviconWins() {
    final var properties = web("/brand/wordmark.svg", "/brand/glyph.svg");
    assertThat(properties.logo()).isEqualTo("/brand/wordmark.svg");
    assertThat(properties.favicon()).isEqualTo("/brand/glyph.svg");
  }

  @Test
  @DisplayName("blank is nobody having replaced the mark, not an empty image")
  void blankIsNothing() {
    // Blank rather than absent is what a `${WEB_LOGO:}` with no value in the environment binds to,
    // so it is the case that actually happens, and an empty string reaching the page would be a
    // missing image where the shipped mark belongs.
    final var properties = web("  ", "");
    assertThat(properties.logo()).isNull();
    assertThat(properties.favicon()).isNull();
  }

  @Test
  @DisplayName("one mark said once is the tab's icon too")
  void theTabFollowsTheLogo() {
    assertThat(web("/brand/acme.svg", null).favicon()).isEqualTo("/brand/acme.svg");
  }

  private static WebProperties web(final String logo, final String favicon) {
    return new WebProperties(null, null, null, null, null, logo, favicon, null, false, null);
  }
}
