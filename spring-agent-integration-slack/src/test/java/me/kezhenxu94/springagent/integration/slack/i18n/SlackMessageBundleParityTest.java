package me.kezhenxu94.springagent.integration.slack.i18n;

import java.util.Set;
import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/** {@inheritDoc} */
class SlackMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return SlackI18n.MESSAGES;
  }

  @Override
  protected Set<String> sameInEveryLanguage() {
    // A sample URL and a version number. Neither is prose, and translating either would make the
    // placeholder wrong rather than clearer.
    return Set.of("config-baseurl-placeholder", "update-version");
  }
}
