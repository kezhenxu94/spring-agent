package me.kezhenxu94.springagent.core.tools.i18n;

import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/**
 * {@inheritDoc}
 *
 * <p>Core's bundle is the one every application resolves, {@code MessagesDefaults} seeing to that,
 * so a key left untranslated here is an English sentence in the middle of every Chinese turn in
 * every deployment.
 */
class CoreMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return CoreMessages.BASENAME;
  }
}
