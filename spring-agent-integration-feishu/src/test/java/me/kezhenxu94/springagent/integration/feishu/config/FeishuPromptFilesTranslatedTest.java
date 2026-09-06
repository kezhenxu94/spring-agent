package me.kezhenxu94.springagent.integration.feishu.config;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractPromptFilesTranslatedTest;

/**
 * {@inheritDoc}
 *
 * <p>Four of these are reference pages a tool returns as its whole result, eighteen thousand
 * characters between them, and {@code reply-format.md} goes into the system prompt of every run on
 * this surface. All of them were English until this test existed.
 */
class FeishuPromptFilesTranslatedTest extends AbstractPromptFilesTranslatedTest {

  @Override
  protected List<String> promptLocations() {
    return List.of(FeishuGuides.LOCATION);
  }
}
