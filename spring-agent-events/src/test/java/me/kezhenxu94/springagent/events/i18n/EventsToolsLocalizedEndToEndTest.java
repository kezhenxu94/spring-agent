package me.kezhenxu94.springagent.events.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractLocalizedToolsEndToEndTest;
import me.kezhenxu94.springagent.events.tools.PlaybookTools;
import me.kezhenxu94.springagent.events.tools.SituationTools;

/** {@inheritDoc} */
class EventsToolsLocalizedEndToEndTest extends AbstractLocalizedToolsEndToEndTest {

  @Override
  protected String bundleBase() {
    return EventsI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return EventsI18n.TOOLS_LOCATION;
  }

  @Override
  protected List<Object> tools() {
    return List.of(
        new SituationTools(null, null, null, null), new PlaybookTools(null, null, null, null));
  }
}
