package me.kezhenxu94.springagent.events.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractPromptFilesTranslatedTest;

/** {@inheritDoc} */
class EventsPromptFilesTranslatedTest extends AbstractPromptFilesTranslatedTest {

  @Override
  protected List<String> promptLocations() {
    return List.of("events/prompts/");
  }
}
