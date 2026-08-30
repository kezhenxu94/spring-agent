package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lark.oapi.event.EventDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the long connection is handed for an event nothing here subscribes a handler to. */
class LenientEventDispatcherTest {

  private static byte[] event(final String eventType) {
    return ("{\"schema\":\"2.0\",\"header\":{\"event_type\":\"" + eventType + "\"},\"event\":{}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("an event with no handler is nothing, not a failure")
  void passesOverAnUnhandledEvent() throws Throwable {
    final var dispatcher = new LenientEventDispatcher(EventDispatcher.newBuilder("", ""));

    // Null is what makes the reply a 200: an event we chose not to handle was delivered fine, and a
    // 500 would ask Feishu to keep sending it.
    assertThat(dispatcher.doWithoutValidation(event("im.chat.member.bot.added_v1"))).isNull();
  }

  @Test
  @DisplayName("the stock dispatcher is what throws, so the subclass is what silences it")
  void showsWhatItIsFixing() {
    final var stock = EventDispatcher.newBuilder("", "").build();

    assertThatThrownBy(() -> stock.doWithoutValidation(event("im.chat.member.bot.added_v1")))
        .hasMessageContaining("im.chat.member.bot.added_v1");
  }

  @Test
  @DisplayName("an event that does have a handler still reaches it")
  void stillDispatchesWhatIsHandled() throws Throwable {
    final var seen = new AtomicReference<String>();
    final var dispatcher =
        new LenientEventDispatcher(
            EventDispatcher.newBuilder("", "")
                .onCustomizedEvent(
                    "im.chat.member.bot.added_v1",
                    new com.lark.oapi.event.CustomEventHandler() {
                      @Override
                      public void handle(final com.lark.oapi.core.request.EventReq event) {
                        seen.set(new String(event.getBody(), StandardCharsets.UTF_8));
                      }
                    }));

    dispatcher.doWithoutValidation(event("im.chat.member.bot.added_v1"));

    assertThat(seen.get()).contains("im.chat.member.bot.added_v1");
  }
}
