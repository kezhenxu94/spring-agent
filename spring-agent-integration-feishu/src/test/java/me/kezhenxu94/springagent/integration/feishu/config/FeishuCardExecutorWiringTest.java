package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * That a card's calls are made where they are meant to be made.
 *
 * <p>Worth a test of its own because both ways this can go wrong are quiet. A {@code
 * ScheduledExecutorService} is an {@code ExecutorService}, so by type alone the clock is a
 * candidate for the parameter the writes belong on, and what keeps them apart is a
 * {@code @Qualifier} on a field that Lombok has to copy onto the constructor it generates —
 * configured in {@code lombok.config}, and nothing in this module would notice it being dropped.
 * Injected with the clock, every card write would be made on two threads shared by every card in
 * the application.
 */
class FeishuCardExecutorWiringTest {

  @Test
  @DisplayName("the listener asks for the writes executor by name, the clock being one too")
  void theWritesExecutorIsAskedForByName() {
    final var constructor = FeishuCardListener.class.getDeclaredConstructors()[0];
    final var writes =
        Arrays.stream(constructor.getParameters())
            .filter(parameter -> parameter.getType() == ExecutorService.class)
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no ExecutorService parameter to make the calls on"));

    final var qualifier = writes.getAnnotation(Qualifier.class);
    assertThat(qualifier)
        .as("without this the clock's own pool is as good a candidate, and Spring picks neither")
        .isNotNull();
    assertThat(qualifier.value()).isEqualTo("feishuCardWrites");
  }

  @Test
  @DisplayName("a card's calls are made on a virtual thread, so being blocked costs no thread")
  void theWritesExecutorIsVirtual() throws Exception {
    final var writes = new FeishuAutoConfiguration().feishuCardWrites();
    try {
      final var thread = writes.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);

      assertThat(thread.isVirtual()).isTrue();
      assertThat(thread.getName()).startsWith("feishu-card-write-");
    } finally {
      writes.shutdownNow();
    }
  }
}
