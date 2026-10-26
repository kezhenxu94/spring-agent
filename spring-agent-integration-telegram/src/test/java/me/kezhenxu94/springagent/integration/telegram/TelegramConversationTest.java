package me.kezhenxu94.springagent.integration.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Chat;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a conversation key says where to answer, since a scheduled task fires back with nothing
 * else.
 */
class TelegramConversationTest {

  static Message message(final Chat chat, final long from, final Integer topic) {
    return new Message(
        9,
        topic,
        topic != null,
        new User(from, false, "A", null, null),
        chat,
        0,
        null,
        "hi",
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("a private chat is one conversation, whoever is in it")
  void shouldKeyAPrivateChatByTheChat() {
    final var key =
        TelegramConversation.of(message(new Chat(5, "private", null, null, null), 5, null));

    assertThat(key.key()).isEqualTo("telegram:5");
  }

  @Test
  @DisplayName("a group is one conversation per person, and a topic one per person within it")
  void shouldKeyAGroupByThePerson() {
    final var group = new Chat(-100123, "supergroup", "G", null, true);

    assertThat(TelegramConversation.of(message(group, 5, null)).key())
        .isEqualTo("telegram:-100123:5");
    assertThat(TelegramConversation.of(message(group, 6, null)).key())
        .isEqualTo("telegram:-100123:6");
    assertThat(TelegramConversation.of(message(group, 5, 77)).key())
        .isEqualTo("telegram:-100123/77:5");
  }

  @Test
  @DisplayName("a key reads back as the chat and topic to send to")
  void shouldRoundTrip() {
    final var conversation = new TelegramConversation("-100123", 77, "5");

    assertThat(TelegramConversation.parse(conversation.key())).contains(conversation);
    assertThat(TelegramConversation.parse("telegram:5"))
        .contains(new TelegramConversation("5", null, null));
  }

  @Test
  @DisplayName("anything that is not one of these keys is not read as one")
  void shouldRefuseOtherKeys() {
    assertThat(TelegramConversation.parse("1700000000.000100")).isEmpty();
    assertThat(TelegramConversation.parse(null)).isEmpty();
    assertThat(TelegramConversation.parse("telegram:-1/x:5")).isEmpty();
  }
}
