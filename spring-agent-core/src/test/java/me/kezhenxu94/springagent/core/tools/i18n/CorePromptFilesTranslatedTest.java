package me.kezhenxu94.springagent.core.tools.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractPromptFilesTranslatedTest;

/**
 * {@inheritDoc}
 *
 * <p>Core's prompts are the largest model-facing text in the system: the system prompt alone runs
 * to five and a half thousand characters, and it is the first thing the model reads on every turn.
 */
class CorePromptFilesTranslatedTest extends AbstractPromptFilesTranslatedTest {

  @Override
  protected List<String> promptLocations() {
    return List.of("core/prompts/");
  }
}
