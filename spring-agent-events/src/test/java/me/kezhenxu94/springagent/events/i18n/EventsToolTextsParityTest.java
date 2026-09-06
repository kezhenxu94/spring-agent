package me.kezhenxu94.springagent.events.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractToolTextsParityTest;

/** {@inheritDoc} */
class EventsToolTextsParityTest extends AbstractToolTextsParityTest {

  @Override
  protected String basePackage() {
    return EventsI18n.PACKAGE;
  }

  @Override
  protected String bundleBase() {
    return EventsI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return EventsI18n.TOOLS_LOCATION;
  }

  @Override
  protected List<String> knownTools() {
    return List.of("ListOpenSituations", "ResolveSituation", "ListPlaybooks");
  }
}
