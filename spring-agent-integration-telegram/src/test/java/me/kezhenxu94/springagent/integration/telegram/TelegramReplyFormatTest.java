package me.kezhenxu94.springagent.integration.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That the model is told what the markdown translation cannot fix. */
class TelegramReplyFormatTest {

  private static String replyFormat(final Locale locale, final String chatType) {
    return String.valueOf(
        new TelegramReplyFormat(locale)
            .variables(
                AgentRequest.builder()
                    .requestId("1")
                    .scenario(BuiltInScenarios.CHAT)
                    .chatType(chatType)
                    .userMessage(user -> user.text("hi"))
                    .build())
            .get("replyFormat"));
  }

  @Test
  @DisplayName("tables are ruled out, since nothing can translate one")
  void shouldRuleOutTables() {
    assertThat(replyFormat(Locale.ENGLISH, "p2p")).contains("NO tables");
  }

  @Test
  @DisplayName("who a handle notifies is said only in a group, where it can happen")
  void shouldOnlyWarnAboutHandlesInAGroup() {
    assertThat(replyFormat(Locale.ENGLISH, "group")).contains("@username");
    assertThat(replyFormat(Locale.ENGLISH, "p2p")).doesNotContain("@username");
  }

  @Test
  @DisplayName("and the whole of it in the chat's language")
  void shouldBeTranslated() {
    assertThat(replyFormat(Locale.SIMPLIFIED_CHINESE, "group")).contains("没有表格").contains("@用户名");
  }
}
