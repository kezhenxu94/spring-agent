package me.kezhenxu94.springagent.controllers;

import me.kezhenxu94.springagent.dao.models.PublishedResource;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class VisibilityConverter implements Converter<String, PublishedResource.Visibility> {

  @Override
  public PublishedResource.Visibility convert(String source) {
    final var visibility = PublishedResource.Visibility.from(source);
    if (visibility == null) {
      throw new IllegalArgumentException("Invalid visibility: " + source);
    }
    return visibility;
  }
}
