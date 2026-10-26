package me.kezhenxu94.springagent.integration.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelegramNotifierTest {

  private final TelegramNotifier notifier = new TelegramNotifier(null);

  @Test
  @DisplayName(
      "a handle in somebody else's words no longer notifies anybody, and still reads the same")
  void shouldUnlinkHandles() {
    final var quoted = notifier.quoted("ping @everyone_here please");

    assertThat(quoted).doesNotContain("@everyone_here");
    assertThat(quoted.replace("\u2060", "")).isEqualTo("ping @everyone_here please");
  }

  @Test
  @DisplayName("names the platform, for a surface that draws its icon")
  void shouldNameItsSurface() {
    assertThat(notifier.surface()).isEqualTo("telegram");
  }
}
