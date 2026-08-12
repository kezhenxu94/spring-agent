package me.kezhenxu94.springagent.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.kezhenxu94.springagent.dao.models.PublishedResource;
import org.junit.jupiter.api.Test;

class VisibilityConverterTest {

  private final VisibilityConverter converter = new VisibilityConverter();

  @Test
  void convertsKnownValuesCaseInsensitively() {
    assertThat(converter.convert("public")).isEqualTo(PublishedResource.Visibility.PUBLIC);
    assertThat(converter.convert("PUBLIC")).isEqualTo(PublishedResource.Visibility.PUBLIC);
    assertThat(converter.convert("Internal")).isEqualTo(PublishedResource.Visibility.INTERNAL);
  }

  @Test
  void rejectsUnknownValues() {
    assertThatThrownBy(() -> converter.convert("foo")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> converter.convert("")).isInstanceOf(IllegalArgumentException.class);
  }
}
