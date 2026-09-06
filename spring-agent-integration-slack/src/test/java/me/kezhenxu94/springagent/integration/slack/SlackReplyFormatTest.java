package me.kezhenxu94.springagent.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the model is told the things about Slack that fail silently.
 *
 * <p>Each of these is a rule whose absence produces an answer that looks wrong rather than an error
 * — visible asterisks, a table of pipes, a channel-wide ping nobody asked for — so the assertions
 * are on the presence of the rule rather than on its wording.
 */
class SlackReplyFormatTest {

  private final SlackReplyFormat format = new SlackReplyFormat(Locale.ENGLISH);

  private static AgentRequest request(final String chatType) {
    return AgentRequest.builder()
        .requestId("1")
        .scenario(me.kezhenxu94.springagent.core.agent.BuiltInScenarios.CHAT)
        .chatType(chatType)
        .userMessage(user -> user.text("hi"))
        .build();
  }

  private String replyFormat(final String chatType) {
    return String.valueOf(format.variables(request(chatType)).get("replyFormat"));
  }

  @Test
  @DisplayName("the model is told bold is one asterisk, which is the mistake it would make")
  void shouldCorrectTheCommonMarkHabit() {
    assertThat(replyFormat("p2p")).contains("*bold*").contains("NOT `**bold**`");
  }

  @Test
  @DisplayName("and that headings and tables do not exist here")
  void shouldRuleOutWhatSlackCannotRender() {
    assertThat(replyFormat("p2p")).contains("NO headings").contains("NO tables");
  }

  @Test
  @DisplayName("and that a link is not written the CommonMark way")
  void shouldCorrectLinkSyntax() {
    assertThat(replyFormat("p2p")).contains("<https://example.com|the text>");
  }

  @Test
  @DisplayName("channel-wide pings are explained only in a channel, where they can happen")
  void shouldOnlyWarnAboutBroadcastsInAChannel() {
    assertThat(replyFormat("group")).contains("<!here>").contains("<!channel>");
    // Nobody to notify in a direct message but the one person already reading it.
    assertThat(replyFormat("p2p")).doesNotContain("<!channel>");
  }

  @Test
  @DisplayName("mentioning somebody is told apart from naming them")
  void shouldDistinguishMentionFromName() {
    assertThat(replyFormat("p2p")).contains("<@U123ABC>").contains("without notifying them");
  }

  @Test
  @DisplayName("the whole guide is served in the workspace's language, not only the answer")
  void shouldBeTranslatedItself() {
    // Two thousand characters of it go into the system prompt of every run on this surface, so
    // English here is English the model reads on every turn — which is what has it reason in
    // English however Chinese the rest of the prompt is.
    final var chinese =
        String.valueOf(
            new SlackReplyFormat(Locale.SIMPLIFIED_CHINESE)
                .variables(request("group"))
                .get("replyFormat"));

    assertThat(chinese).contains("Slack mrkdwn").contains("粗体");
    // The syntax itself stays as Slack spells it: it is what the model has to type, not prose.
    assertThat(chinese).contains("<@U123ABC>").contains("<!channel>");
  }
}
