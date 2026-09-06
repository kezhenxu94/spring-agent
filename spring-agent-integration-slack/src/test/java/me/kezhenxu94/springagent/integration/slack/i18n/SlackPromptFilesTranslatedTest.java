package me.kezhenxu94.springagent.integration.slack.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractPromptFilesTranslatedTest;

/** {@inheritDoc} */
class SlackPromptFilesTranslatedTest extends AbstractPromptFilesTranslatedTest {

  @Override
  protected List<String> promptLocations() {
    return List.of("slack/prompts/");
  }
}
