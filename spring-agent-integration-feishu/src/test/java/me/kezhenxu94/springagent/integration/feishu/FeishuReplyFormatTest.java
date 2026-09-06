package me.kezhenxu94.springagent.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeishuReplyFormatTest {

  private final FeishuReplyFormat format = new FeishuReplyFormat(Locale.ENGLISH);

  private static AgentRequest request(final String chatType) {
    return AgentRequest.builder()
        .requestId("om_1")
        .scenario(BuiltInScenarios.CHAT)
        .chatType(chatType)
        .userMessage(user -> user.text("hi"))
        .build();
  }

  private String replyFormat(final String chatType) {
    return (String) format.variables(request(chatType)).get("replyFormat");
  }

  @Test
  @DisplayName("the two tags that look alike are told apart, since one notifies and one does not")
  void tellsMentioningApartFromNaming() {
    final var guide = replyFormat("p2p");

    assertThat(guide).contains("<at id=ou_xxx></at>").contains("<person id=");
    assertThat(guide).contains("pings nobody");
  }

  @Test
  @DisplayName("notifying everybody is only mentioned where there is a group to notify")
  void atAllOnlyInGroups() {
    assertThat(replyFormat("group")).contains("<at id=all></at>").contains("at_all_permission");
    assertThat(replyFormat("p2p")).doesNotContain("<at id=all>");
    // The default chat type, which is a direct message.
    assertThat(replyFormat(null)).doesNotContain("<at id=all>");
  }

  @Test
  @DisplayName("the guide fills the slot core defaults to empty, under the name core knows")
  void fillsTheReplyFormatSlot() {
    assertThat(format.variables(request("group"))).containsOnlyKeys("replyFormat");
  }

  @Test
  @DisplayName("the whole guide is served in the workspace's language, not only the answer")
  void isItselfTranslated() {
    // Two thousand characters of it go into the system prompt of every run on this surface, so
    // English here is English the model reads on every turn — which is what has it reason in
    // English however Chinese the rest of the prompt is.
    final var chinese =
        (String)
            new FeishuReplyFormat(Locale.SIMPLIFIED_CHINESE)
                .variables(request("group"))
                .get("replyFormat");

    assertThat(chinese).contains("飞书卡片").contains("at_all_permission");
    // The syntax itself stays as Feishu spells it: it is what the model has to type, not prose.
    assertThat(chinese).contains("<at id=ou_xxx></at>").contains("<at id=all></at>");
  }

  @Test
  @DisplayName("says nothing about a run belonging to another surface")
  void staysOutOfAnotherSurfacesRun() {
    // A contributor is a @Bean, so it is asked about every run in the context. An application
    // carrying this module beside a browser would otherwise have every page answer written in
    // Feishu tags and rendered literally.
    assertThat(format.variables(request("web"))).isEmpty();
    assertThat(format.variables(request("channel"))).isEmpty();
  }
}
