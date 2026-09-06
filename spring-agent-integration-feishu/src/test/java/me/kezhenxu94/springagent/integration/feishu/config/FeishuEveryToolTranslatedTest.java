package me.kezhenxu94.springagent.integration.feishu.config;

import me.kezhenxu94.springagent.core.i18n.AbstractEveryToolTranslatedTest;

/**
 * {@inheritDoc}
 *
 * <p>Seventy-odd tools, and a bitable or document task calls a dozen of them in a turn. One left in
 * English is a paragraph of English in the middle of every such turn.
 */
class FeishuEveryToolTranslatedTest extends AbstractEveryToolTranslatedTest {

  @Override
  protected String basePackage() {
    return "me.kezhenxu94.springagent.integration.feishu";
  }

  @Override
  protected String bundleBase() {
    return "feishu/tools";
  }

  @Override
  protected String promptDirectory() {
    return FeishuGuides.TOOLS_LOCATION;
  }
}
