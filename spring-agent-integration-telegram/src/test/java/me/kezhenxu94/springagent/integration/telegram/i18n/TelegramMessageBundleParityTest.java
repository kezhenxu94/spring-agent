package me.kezhenxu94.springagent.integration.telegram.i18n;

import java.util.Set;
import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/** {@inheritDoc} */
class TelegramMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return TelegramI18n.MESSAGES;
  }

  @Override
  protected Set<String> sameInEveryLanguage() {
    // A version number, which is not prose.
    return Set.of("update-version");
  }
}
