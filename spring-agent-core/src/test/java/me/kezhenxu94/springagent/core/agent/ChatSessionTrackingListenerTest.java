package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The index from a person to their conversations, as every surface writes it.
 *
 * <p>Over a map rather than a backend, because what is being asserted is the listener's own
 * decision about what to keep — the same decision on all three backends, since none of them sees
 * it.
 */
class ChatSessionTrackingListenerTest {

  private final Map<String, ChatSession> rows = new LinkedHashMap<>();

  private final ChatSessionRepo repo =
      new ChatSessionRepo() {
        @Override
        public ChatSession save(final ChatSession session) {
          rows.put(session.id(), session);
          return session;
        }

        @Override
        public Optional<ChatSession> findById(final String id) {
          return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ChatSession> findByUserId(final String userId) {
          return new ArrayList<>(rows.values())
              .stream().filter(it -> userId.equals(it.userId())).toList();
        }

        @Override
        public void deleteById(final String id) {
          rows.remove(id);
        }
      };

  private final ChatSessionTrackingListener listener = new ChatSessionTrackingListener(repo);

  private void run(final String userId, final String groupId, final String tenantId) {
    listener.onStart(
        new AgentRunRegistry(
            AgentRequest.builder()
                .requestId("r-" + rows.size())
                .scenario(BuiltInScenarios.CHAT)
                .userId(userId)
                .conversationId("conv-1")
                .groupId(groupId)
                .tenantId(tenantId)
                .userMessage(user -> user.text("hi"))
                .build()));
  }

  @Test
  @DisplayName("the first run indexes the conversation with the scope it named")
  void indexesTheConversation() {
    run("ou_1", "oc_group", "tenant-1");

    assertThat(rows.get("conv-1"))
        .returns("ou_1", ChatSession::userId)
        .returns("oc_group", ChatSession::groupId)
        .returns("tenant-1", ChatSession::tenantId);
  }

  @Test
  @DisplayName("a surface with no groups cannot un-group a conversation that has one")
  void keepsAGroupARunDidNotName() {
    // A Feishu group conversation, then the same conversation continued from the browser, which
    // has no group concept and names none. Overwriting here would take the knowledge scope and the
    // group's home directory away from every later run in the conversation.
    run("ou_1", "oc_group", "tenant-1");
    run("ou_1", null, "tenant-1");

    assertThat(rows.get("conv-1").groupId()).isEqualTo("oc_group");
  }

  @Test
  @DisplayName("a blank is not a request to clear a tenant either")
  void keepsATenantARunDidNotName() {
    run("ou_1", null, "tenant-1");
    run("ou_1", null, "   ");

    assertThat(rows.get("conv-1").tenantId()).isEqualTo("tenant-1");
  }

  @Test
  @DisplayName("a run that does name a scope replaces what was there")
  void aNamedScopeStillWins() {
    run("ou_1", "oc_group", "tenant-1");
    run("ou_1", "oc_other", "tenant-2");

    assertThat(rows.get("conv-1"))
        .returns("oc_other", ChatSession::groupId)
        .returns("tenant-2", ChatSession::tenantId);
  }

  @Test
  @DisplayName("an unattended run is nobody's conversation, so it is not indexed")
  void skipsBackgroundRuns() {
    listener.onStart(
        new AgentRunRegistry(
            AgentRequest.builder()
                .requestId("r-bg")
                .scenario(BuiltInScenarios.CHAT)
                .userId("ou_1")
                .conversationId("conv-1")
                .background(true)
                .userMessage(user -> user.text("hi"))
                .build()));

    assertThat(rows).isEmpty();
  }
}
