package me.kezhenxu94.springagent.integration.feishu.greeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

/** Who is greeted, with which of the two cards, and who is left alone. */
@ExtendWith(MockitoExtension.class)
class FeishuGreetingsTest {

  private static final String USER = "ou_reader";
  private static final String CHAT = "oc_chat";

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private FeishuUpdates updates;
  @Mock private FeishuGreetingCards cards;

  private final Map<String, SeenUpdate> rows = new HashMap<>();
  private final Set<String> claims = new HashSet<>();

  private FeishuGreetings greetings;

  @BeforeEach
  void setUp() {
    greetings =
        new FeishuGreetings(
            feishu, updates, cards, seenUpdates(), processedMessages(), new SyncTaskExecutor());
  }

  private SeenUpdateRepo seenUpdates() {
    return new SeenUpdateRepo() {
      @Override
      public SeenUpdate save(final SeenUpdate seenUpdate) {
        rows.put(seenUpdate.id(), seenUpdate);
        return seenUpdate;
      }

      @Override
      public Optional<SeenUpdate> findById(final String id) {
        return Optional.ofNullable(rows.get(id));
      }
    };
  }

  private ProcessedMessageRepo processedMessages() {
    return new ProcessedMessageRepo() {
      @Override
      public boolean claim(final String id) {
        return claims.add(id);
      }

      @Override
      public void release(final String id) {
        claims.remove(id);
      }
    };
  }

  private void sendsSuccessfully() throws Exception {
    final var response = new CreateMessageResp();
    response.setCode(0);
    when(feishu.im().v1().message().create(any(CreateMessageReq.class))).thenReturn(response);
  }

  @Test
  @DisplayName("somebody with no record is new, so they get the welcome card")
  void greetsSomebodyNew() throws Exception {
    when(updates.current()).thenReturn(2);
    when(cards.welcome()).thenReturn("{\"welcome\":true}");
    sendsSuccessfully();

    greetings.greet(CHAT, USER);

    verify(cards).welcome();
    verify(cards, never()).update(any());
    // And they start level, not with two notes waiting behind the card they were just shown.
    assertThat(rows.get(USER).version()).isEqualTo(2);
  }

  @Test
  @DisplayName("somebody behind gets the notes above their version, and only those")
  void tellsSomebodyWhatTheyMissed() throws Exception {
    rows.put(USER, SeenUpdate.builder().id(USER).version(1).updatedAt(Instant.now()).build());
    final var unread = List.of(new FeishuUpdates.Note(2, "two", "the second"));
    when(updates.current()).thenReturn(2);
    when(updates.since(1)).thenReturn(unread);
    when(cards.update(unread)).thenReturn("{\"update\":true}");
    sendsSuccessfully();

    greetings.greet(CHAT, USER);

    verify(cards).update(unread);
    verify(cards, never()).welcome();
    assertThat(rows.get(USER).version()).isEqualTo(2);
  }

  @Test
  @DisplayName("somebody up to date is not talked at")
  void saysNothingToSomebodyUpToDate() throws Exception {
    rows.put(USER, SeenUpdate.builder().id(USER).version(2).updatedAt(Instant.now()).build());
    when(updates.current()).thenReturn(2);

    greetings.greet(CHAT, USER);

    verify(feishu.im().v1().message(), never()).create(any(CreateMessageReq.class));
  }

  @Test
  @DisplayName("opening the chat twice at once greets once")
  void greetsOnlyOnce() throws Exception {
    when(updates.current()).thenReturn(1);
    when(cards.welcome()).thenReturn("{\"welcome\":true}");
    sendsSuccessfully();

    greetings.greet(CHAT, USER);
    // The row is written by the first, but a replica that read before that write still has to be
    // stopped — which is the claim's job, not the row's.
    rows.clear();
    greetings.greet(CHAT, USER);

    verify(feishu.im().v1().message()).create(any(CreateMessageReq.class));
  }

  @Test
  @DisplayName("a greeting that could not be sent is left to be tried again")
  void releasesTheClaimWhenNothingWasSaid() throws Exception {
    when(updates.current()).thenReturn(1);
    when(cards.welcome()).thenReturn("{\"welcome\":true}");
    final var refused = new CreateMessageResp();
    refused.setCode(230001);
    when(feishu.im().v1().message().create(any(CreateMessageReq.class))).thenReturn(refused);

    greetings.greet(CHAT, USER);

    assertThat(rows).isEmpty();
    assertThat(claims).isEmpty();
  }

  @Test
  @DisplayName("the card goes to the chat, as a card")
  void sendsAnInteractiveMessage() throws Exception {
    when(updates.current()).thenReturn(1);
    when(cards.welcome()).thenReturn("{\"welcome\":true}");
    sendsSuccessfully();

    greetings.greet(CHAT, USER);

    final var request = ArgumentCaptor.forClass(CreateMessageReq.class);
    verify(feishu.im().v1().message()).create(request.capture());
    assertThat(request.getValue().getCreateMessageReqBody().getReceiveId()).isEqualTo(CHAT);
    assertThat(request.getValue().getCreateMessageReqBody().getMsgType()).isEqualTo("interactive");
    assertThat(request.getValue().getCreateMessageReqBody().getContent())
        .isEqualTo("{\"welcome\":true}");
  }

  @Test
  @DisplayName("an event naming no user is dropped rather than greeting nobody")
  void ignoresAnEventWithNoUser() {
    greetings.greet(CHAT, null);

    assertThat(rows).isEmpty();
  }
}
