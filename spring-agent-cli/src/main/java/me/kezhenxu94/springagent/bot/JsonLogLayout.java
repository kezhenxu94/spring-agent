package me.kezhenxu94.springagent.bot;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.contrib.json.classic.JsonLayout;
import java.util.Map;

public class JsonLogLayout extends JsonLayout {
  @Override
  protected void addCustomDataToJsonMap(Map<String, Object> map, ILoggingEvent event) {
    event.getMDCPropertyMap().forEach((k, v) -> add(k, true, v, map));
  }
}
