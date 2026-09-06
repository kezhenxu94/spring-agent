package me.kezhenxu94.springagent.integration.websocket.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/**
 * {@inheritDoc}
 *
 * <p>This surface has a second bundle of its own in {@code static/js/i18n.js} for what the page
 * says on its own behalf. This one is what the <em>agent</em> says through it, which is the half a
 * model reads and answers from.
 */
class WebMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return "web/messages";
  }
}
