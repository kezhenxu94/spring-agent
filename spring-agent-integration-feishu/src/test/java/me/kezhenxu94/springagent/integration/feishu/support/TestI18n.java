package me.kezhenxu94.springagent.integration.feishu.support;

import java.util.Locale;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;

/**
 * This module's own text, in a language the test names.
 *
 * <p>Always with a locale stated: {@link FeishuMessages} falls back to the workspace's locale and
 * thence to the host's, so a test that left it unset would read whatever the machine running it is
 * configured for.
 */
public final class TestI18n {

  private TestI18n() {}

  public static FeishuMessages messages(final Locale locale) {
    return new FeishuMessages(
        new FeishuProperties(null, null, null, null, null, null, null, locale, null, null, null));
  }

  public static FeishuMessages english() {
    return messages(Locale.ENGLISH);
  }

  public static FeishuMessages chinese() {
    return messages(Locale.SIMPLIFIED_CHINESE);
  }
}
