package me.kezhenxu94.springagent.integration.telegram.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractPromptFilesTranslatedTest;

/** {@inheritDoc} */
class TelegramPromptFilesTranslatedTest extends AbstractPromptFilesTranslatedTest {

  @Override
  protected List<String> promptLocations() {
    return List.of("telegram/prompts/");
  }
}
