package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemperaturesTest {

  @Test
  @DisplayName("blank means the deployment's own, and a number in range is accepted")
  void accepts() {
    assertThat(Temperatures.valid((String) null)).isTrue();
    assertThat(Temperatures.valid(" ")).isTrue();
    assertThat(Temperatures.valid("0")).isTrue();
    assertThat(Temperatures.valid(" 1 ")).isTrue();
    assertThat(Temperatures.valid("2")).isTrue();
    assertThat(Temperatures.parse(" ")).isNull();
    assertThat(Temperatures.parse(" 0.7 ")).isEqualTo(0.7);
  }

  @Test
  @DisplayName("words, negatives, anything past 2 and NaN are refused")
  void refuses() {
    assertThat(Temperatures.valid("warm")).isFalse();
    assertThat(Temperatures.valid("-0.1")).isFalse();
    assertThat(Temperatures.valid("2.1")).isFalse();
    assertThat(Temperatures.valid("NaN")).isFalse();
    assertThat(Temperatures.valid(Double.POSITIVE_INFINITY)).isFalse();
  }
}
