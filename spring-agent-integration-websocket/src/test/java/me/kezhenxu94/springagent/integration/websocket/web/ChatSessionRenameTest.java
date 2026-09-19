package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * What a conversation is called.
 *
 * <p>The default is the first thing that was said in it, derived on read so that it cannot go stale
 * against a conversation that was cleared or trimmed. A name somebody typed is the opposite case —
 * it is not a copy of anything in the conversation, so it is stored, and it wins. These assertions
 * are about the seam between the two, which is the only place the distinction can go wrong.
 */
class ChatSessionRenameTest {

  private static final String ID = "c1";

  private final ChatMemory chatMemory = mock(ChatMemory.class);
  private final ChatSessionRepo repo = mock(ChatSessionRepo.class);
  private final ChatSessions sessions = new ChatSessions(repo, chatMemory);

  private ChatSession session(final String title) {
    return ChatSession.builder().id(ID).userId("me").title(title).build();
  }

  private void said(final String text) {
    given(chatMemory.get(ID)).willReturn(List.of(new UserMessage(text)));
  }

  private ChatSession saved() {
    final var captor = org.mockito.ArgumentCaptor.forClass(ChatSession.class);
    verify(repo).save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("a conversation nobody named is called the first thing that was said in it")
  void derivesWhenUnnamed() {
    said("how do I fill a PDF form?");

    assertThat(sessions.titleOf(session(null))).isEqualTo("how do I fill a PDF form?");
    assertThat(sessions.titleOf(session(""))).isEqualTo("how do I fill a PDF form?");
    assertThat(sessions.titleOf(session("   "))).isEqualTo("how do I fill a PDF form?");
  }

  @Test
  @DisplayName("a name somebody typed wins over the first message")
  void storedNameWins() {
    said("how do I fill a PDF form?");

    assertThat(sessions.titleOf(session("Q3 forms"))).isEqualTo("Q3 forms");
  }

  @Test
  @DisplayName("renaming stores the name, trimmed")
  void renameStores() {
    given(repo.save(any())).willAnswer(it -> it.getArgument(0));

    sessions.rename(session(null), "  Q3 forms  ");

    assertThat(saved().title()).isEqualTo("Q3 forms");
  }

  @Test
  @DisplayName("renaming to nothing clears the name, so the conversation names itself again")
  void renameToNothingClears() {
    given(repo.save(any())).willAnswer(it -> it.getArgument(0));
    said("how do I fill a PDF form?");

    final var cleared = sessions.rename(session("Q3 forms"), "   ");

    assertThat(cleared.title()).isEmpty();
    assertThat(sessions.titleOf(cleared)).isEqualTo("how do I fill a PDF form?");
  }

  @Test
  @DisplayName("a name longer than the row can show is cut rather than refused")
  void renameCaps() {
    given(repo.save(any())).willAnswer(it -> it.getArgument(0));

    sessions.rename(session(null), "x".repeat(ChatSessions.MAX_TITLE + 50));

    assertThat(saved().title()).hasSize(ChatSessions.MAX_TITLE);
  }

  @Test
  @DisplayName("renaming does not move the conversation to the top of the list")
  void renameDoesNotTouch() {
    // The list is sorted by when something last happened in the conversation. Renaming is not
    // something happening in it, and a rename that reordered the sidebar would be the rename
    // pretending to be a turn.
    given(repo.save(any())).willAnswer(it -> it.getArgument(0));
    final var before = ChatSession.builder().id(ID).userId("me").updatedAt(null).build();

    sessions.rename(before, "Q3 forms");

    assertThat(saved().updatedAt()).isNull();
  }

  @Test
  @DisplayName("a conversation with nothing said in it yet has no name at all")
  void emptyConversationHasNoName() {
    given(chatMemory.get(ID)).willReturn(List.of());

    assertThat(sessions.titleOf(session(null))).isEmpty();
    verify(repo, never()).save(any());
  }
}
