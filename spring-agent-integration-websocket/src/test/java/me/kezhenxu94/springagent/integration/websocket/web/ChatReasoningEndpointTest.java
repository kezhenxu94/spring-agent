package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may read what a round thought.
 *
 * <p>Two checks stand between a caller and the text, and the second is the one worth a test: the
 * conversation has to be the caller's, <em>and</em> the row has to belong to that conversation.
 * Without the second, one's own conversation id plus somebody else's run id reads their thinking.
 */
class ChatReasoningEndpointTest {

  private static final String ME = "ou_me";
  private static final String MINE = "conv-mine";

  private final ChatSessionRepo sessionRepo = mock(ChatSessionRepo.class);
  private final ChatReasoningRepo reasonings = mock(ChatReasoningRepo.class);

  @SuppressWarnings("unchecked")
  private final ChatController controller =
      new ChatController(
          null,
          mock(ObjectProvider.class),
          null,
          null,
          mock(ObjectProvider.class),
          new ChatSessions(sessionRepo, reasonings, null),
          null,
          null,
          reasonings,
          null,
          null,
          null,
          null,
          null);

  private void mine(final String conversationId) {
    when(sessionRepo.findById(conversationId))
        .thenReturn(Optional.of(ChatSession.builder().id(conversationId).userId(ME).build()));
  }

  private void row(final String requestId, final String conversationId) {
    when(reasonings.findById(requestId))
        .thenReturn(
            Optional.of(
                ChatReasoning.builder()
                    .id(requestId)
                    .conversationId(conversationId)
                    .userId(ME)
                    .text("I checked the disk first.")
                    .build()));
  }

  @Test
  @DisplayName("a round of one's own conversation reads back what it thought")
  void readsOwnReasoning() {
    mine(MINE);
    row("r-1", MINE);

    assertThat(controller.reasoning(principal(), MINE, "r-1"))
        .containsEntry("requestId", "r-1")
        .containsEntry("text", "I checked the disk first.");
  }

  @Test
  @DisplayName("a run id from another conversation is not found, even inside one's own")
  void refusesARowFromAnotherConversation() {
    mine(MINE);
    // Somebody else's round, guessed or leaked. The path names a conversation the caller owns, so
    // only the row's own conversation says no.
    row("r-2", "conv-theirs");

    assertThatThrownBy(() -> controller.reasoning(principal(), MINE, "r-2"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(it -> ((ResponseStatusException) it).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("somebody else's conversation is not found, whatever it holds")
  void refusesSomebodyElsesConversation() {
    when(sessionRepo.findById("conv-theirs"))
        .thenReturn(
            Optional.of(ChatSession.builder().id("conv-theirs").userId("ou_someone").build()));
    row("r-3", "conv-theirs");

    assertThatThrownBy(() -> controller.reasoning(principal(), "conv-theirs", "r-3"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("a round that was never recorded is not found either")
  void refusesAMissingRow() {
    mine(MINE);
    when(reasonings.findById(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.reasoning(principal(), MINE, "r-none"))
        .isInstanceOf(ResponseStatusException.class);
  }

  private static OAuth2User principal() {
    return new DefaultOAuth2User(
        List.of(), Map.of("open_id", ME, "name", "Me", "tenant_key", "t"), "open_id");
  }
}
