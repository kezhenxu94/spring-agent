package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The behaviour every persistence backend owes its callers, run against each of them so that
 * switching {@code app.persistence.type} cannot quietly change semantics.
 *
 * <p>What is worth pinning here is the part that is not a shared derived query: {@code
 * findAccessibleTo} and {@code findBySharedWithIn} are hand-written per backend — MongoDB's query
 * language, JPQL over a collection table, and a union of indexed reads on Redis — and {@code
 * updateStatus} is a partial update expressed differently on each.
 *
 * <p>{@code claim} is the same story taken further: it is not a query at all on any backend, but a
 * conditional insert, a refused insert and a {@code SET NX}, and a message answered twice is what a
 * disagreement between them looks like.
 */
abstract class AbstractPersistenceBackendTest extends AbstractIntegrationTest {

  @Autowired McpServerConfigRepo mcpServerConfigRepo;
  @Autowired ScheduledTaskRepo scheduledTaskRepo;
  @Autowired PendingQuestionRepo pendingQuestionRepo;
  @Autowired ProcessedMessageRepo processedMessageRepo;
  @Autowired SituationRepo situationRepo;
  @Autowired ObservedEventRepo observedEventRepo;

  /**
   * The owner is per-subclass so the two backends cannot collide on the ownerId+name constraint.
   */
  abstract String owner();

  @Test
  @DisplayName("an MCP server config round trips with its headers map and shared-with list")
  void mcpServerConfigRoundTrips() {
    final var saved =
        mcpServerConfigRepo.save(
            McpServerConfig.builder()
                .id(owner() + "-server-1")
                .ownerId(owner())
                .name("server-1")
                .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
                .url("https://mcp.example.invalid/sse")
                .headers(Map.of("Authorization", "Bearer token", "X-Trace", "on"))
                .sharedWith(List.of("ou_friend", "oc_group"))
                .build());
    assertThat(saved.id()).isEqualTo(owner() + "-server-1");

    final var found = mcpServerConfigRepo.findByOwnerIdAndName(owner(), "server-1");
    assertThat(found).isPresent();
    // The map goes through a JSON column under JPA and a subdocument under MongoDB.
    assertThat(found.get().headers())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("Authorization", "Bearer token", "X-Trace", "on"));
    assertThat(found.get().sharedWith()).containsExactlyInAnyOrder("ou_friend", "oc_group");
    assertThat(found.get().transport()).isEqualTo(McpServerConfig.Transport.STREAMABLE_HTTP);

    assertThat(mcpServerConfigRepo.existsByOwnerIdAndName(owner(), "server-1")).isTrue();
  }

  @Test
  @DisplayName("a server is reachable by its owner and by anyone it is shared with")
  void accessResolvesThroughOwnershipAndSharing() {
    mcpServerConfigRepo.save(
        McpServerConfig.builder()
            .id(owner() + "-server-2")
            .ownerId(owner())
            .name("server-2")
            .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
            .url("https://mcp.example.invalid/2")
            .sharedWith(List.of(owner() + "-oc_shared"))
            .build());

    // Through the ownership half of the query, with an identifier that matches nothing.
    assertThat(mcpServerConfigRepo.findAccessibleTo(owner(), List.of("ou_nobody")))
        .extracting(McpServerConfig::name)
        .contains("server-2");

    // Through the sharing half, for a user who owns nothing.
    assertThat(
            mcpServerConfigRepo.findAccessibleTo(
                owner() + "-stranger", List.of(owner() + "-oc_shared")))
        .extracting(McpServerConfig::name)
        .contains("server-2");

    assertThat(mcpServerConfigRepo.findBySharedWithIn(List.of(owner() + "-oc_shared")))
        .extracting(McpServerConfig::name)
        .contains("server-2");

    // A server owned by nobody relevant and shared with nobody relevant stays invisible.
    assertThat(mcpServerConfigRepo.findAccessibleTo(owner() + "-stranger", List.of("ou_unrelated")))
        .extracting(McpServerConfig::name)
        .doesNotContain("server-2");
  }

  @Test
  @DisplayName("deleting by owner and name removes only that server")
  void deleteByOwnerAndName() {
    mcpServerConfigRepo.save(
        McpServerConfig.builder()
            .id(owner() + "-server-3")
            .ownerId(owner())
            .name("server-3")
            .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
            .url("https://mcp.example.invalid/3")
            .build());
    assertThat(mcpServerConfigRepo.findByOwnerIdAndName(owner(), "server-3")).isPresent();

    mcpServerConfigRepo.deleteByOwnerIdAndName(owner(), "server-3");

    assertThat(mcpServerConfigRepo.findByOwnerIdAndName(owner(), "server-3")).isEmpty();
    assertThat(mcpServerConfigRepo.findByOwnerId(owner()))
        .extracting(McpServerConfig::name)
        .doesNotContain("server-3");
  }

  @Test
  @DisplayName("updateStatus changes only the status, leaving the rest of the task alone")
  void updateStatusIsAPartialUpdate() {
    final var id = owner() + "-task-1";
    scheduledTaskRepo.save(
        ScheduledTask.builder()
            .id(id)
            .userId(owner())
            .taskText("summarise the thread")
            .cronExpression("0 0 9 * * MON")
            .background(true)
            .status(ScheduledTask.Status.ACTIVE)
            .build());

    assertThat(scheduledTaskRepo.findByUserIdAndStatus(owner(), ScheduledTask.Status.ACTIVE))
        .extracting(ScheduledTask::id)
        .contains(id);

    scheduledTaskRepo.updateStatus(id, ScheduledTask.Status.COMPLETED);

    final var reloaded = scheduledTaskRepo.findById(id);
    assertThat(reloaded).isPresent();
    assertThat(reloaded.get().status()).isEqualTo(ScheduledTask.Status.COMPLETED);
    // The fields the update did not name must survive it.
    assertThat(reloaded.get().taskText()).isEqualTo("summarise the thread");
    assertThat(reloaded.get().cronExpression()).isEqualTo("0 0 9 * * MON");
    assertThat(reloaded.get().background()).isTrue();
    assertThat(scheduledTaskRepo.findByUserIdAndStatus(owner(), ScheduledTask.Status.ACTIVE))
        .extracting(ScheduledTask::id)
        .doesNotContain(id);
  }

  @Test
  @DisplayName("a pending question round trips, and answering it takes it out of the conversation")
  void pendingQuestionRoundTripsAndLeavesPending() {
    final var conversation = owner() + "-om_root";
    final var id = owner() + "-question-1";
    final var expiresAt = Instant.now().plus(Duration.ofHours(24)).truncatedTo(ChronoUnit.MILLIS);

    pendingQuestionRepo.save(
        PendingQuestion.builder()
            .id(id)
            .userId(owner())
            .chatId("oc_chat")
            .chatType("p2p")
            .conversationId(conversation)
            .rootMessageId(conversation)
            .cardId("7355439197428236291")
            .questionsJson("[{\"question\":\"Which database?\"}]")
            .status(PendingQuestion.Status.PENDING)
            .createdAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .expiresAt(expiresAt)
            .build());

    final var found =
        pendingQuestionRepo.findByConversationIdAndStatus(
            conversation, PendingQuestion.Status.PENDING);
    assertThat(found).extracting(PendingQuestion::id).containsExactly(id);
    // Everything the answer handler needs hours later, when the run that asked is long gone.
    assertThat(found.getFirst().cardId()).isEqualTo("7355439197428236291");
    assertThat(found.getFirst().questionsJson()).contains("Which database?");
    assertThat(found.getFirst().expiresAt()).isEqualTo(expiresAt);

    pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.ANSWERED);

    // The index behind this query is what stops a second press, or a later message, from starting
    // another run for the same questions — so it has to follow the partial update.
    assertThat(
            pendingQuestionRepo.findByConversationIdAndStatus(
                conversation, PendingQuestion.Status.PENDING))
        .isEmpty();
    final var reloaded = pendingQuestionRepo.findById(id);
    assertThat(reloaded).isPresent();
    assertThat(reloaded.get().status()).isEqualTo(PendingQuestion.Status.ANSWERED);
    assertThat(reloaded.get().cardId()).isEqualTo("7355439197428236291");
    assertThat(reloaded.get().conversationId()).isEqualTo(conversation);
  }

  @Test
  @DisplayName("a message can only be claimed once, and claiming again after release succeeds")
  void aMessageIsClaimedOnce() {
    final var messageId = owner() + "-om_claimed_once";

    // The whole point of the operation: the second caller has to be told no. This is what stops a
    // redelivered Feishu message from being answered a second time, and each backend expresses it
    // differently — a conditional insert, a refused insert, and SET NX.
    assertThat(processedMessageRepo.claim(messageId)).isTrue();
    assertThat(processedMessageRepo.claim(messageId)).isFalse();

    // And a claim let go of is a message that can be taken up again, which is what keeps a failure
    // between claiming and answering from dropping the message for good.
    processedMessageRepo.release(messageId);
    assertThat(processedMessageRepo.claim(messageId)).isTrue();
  }

  @Test
  @DisplayName("a situation round trips with its enums and timestamps, and is found by its key")
  void situationRoundTrips() {
    final var key = owner() + "-grafana:abc";
    final var firstSeen = Instant.now().minus(Duration.ofMinutes(9)).truncatedTo(ChronoUnit.MILLIS);
    final var due = Instant.now().plus(Duration.ofSeconds(30)).truncatedTo(ChronoUnit.MILLIS);

    situationRepo.save(
        Situation.builder()
            .id(owner() + "-situation-1")
            .source("grafana")
            .correlationKey(key)
            .title("api latency")
            .status(Situation.Status.OPEN)
            .phase(Situation.Phase.AWAITING_EVALUATION)
            .firstSeenAt(firstSeen)
            .awaitingSince(firstSeen)
            .lastEventAt(firstSeen)
            .evaluateAfter(due)
            .generation(2)
            .eventCount(41)
            .decision(Situation.Decision.ACTED)
            .severity("high")
            .confidence(0.87)
            .assessment("Correlates with the deploy at 11:58.")
            .ownerUserId("ou_agent")
            .chatId("oc_alerts")
            .chatType("group")
            .tenantId("tenant-1")
            .build());

    // The lookup on the ingest path: equality on two indexed properties, which on Redis is an
    // intersection of two secondary index sets rather than a query.
    final var found = situationRepo.findByCorrelationKeyAndStatus(key, Situation.Status.OPEN);
    assertThat(found).extracting(Situation::id).containsExactly(owner() + "-situation-1");
    final var situation = found.getFirst();
    assertThat(situation.phase()).isEqualTo(Situation.Phase.AWAITING_EVALUATION);
    assertThat(situation.decision()).isEqualTo(Situation.Decision.ACTED);
    // The two numbers a triage run is shown, and the one it is scoped by.
    assertThat(situation.eventCount()).isEqualTo(41);
    assertThat(situation.generation()).isEqualTo(2);
    assertThat(situation.confidence()).isEqualTo(0.87);
    assertThat(situation.ownerUserId()).isEqualTo("ou_agent");
    assertThat(situation.assessment()).contains("11:58");
    // Timestamps matter more here than elsewhere: the debounce, its cap and the cooldown are all
    // arithmetic on these, so a backend that dropped precision would change when runs happen.
    assertThat(situation.evaluateAfter()).isEqualTo(due);
    assertThat(situation.awaitingSince()).isEqualTo(firstSeen);

    // The sweeper's own query, which is how a due situation is found at all.
    assertThat(situationRepo.findByPhase(Situation.Phase.AWAITING_EVALUATION))
        .extracting(Situation::id)
        .contains(owner() + "-situation-1");
  }

  @Test
  @DisplayName("closing a situation moves it between indexes rather than only rewriting a field")
  void situationStatusChangeMaintainsTheIndexes() {
    final var key = owner() + "-grafana:def";
    situationRepo.save(
        Situation.builder()
            .id(owner() + "-situation-2")
            .source("grafana")
            .correlationKey(key)
            .status(Situation.Status.OPEN)
            .phase(Situation.Phase.AWAITING_EVALUATION)
            .build());
    assertThat(situationRepo.findByCorrelationKeyAndStatus(key, Situation.Status.OPEN)).hasSize(1);

    // A whole-object save, which is the only way this contract changes anything — there is no
    // partial update, deliberately.
    situationRepo.save(
        situationRepo.findById(owner() + "-situation-2").orElseThrow().toBuilder()
            .status(Situation.Status.RESOLVED)
            .phase(Situation.Phase.MONITORING)
            .resolvedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build());

    // Both halves have to hold, and the first is the one a backend can get wrong: a stale index
    // entry would keep handing arriving observations to a situation that has been closed, and would
    // keep a closed one in front of the sweeper for ever.
    assertThat(situationRepo.findByCorrelationKeyAndStatus(key, Situation.Status.OPEN)).isEmpty();
    assertThat(situationRepo.findByCorrelationKeyAndStatus(key, Situation.Status.RESOLVED))
        .extracting(Situation::id)
        .containsExactly(owner() + "-situation-2");
    assertThat(situationRepo.findByPhase(Situation.Phase.AWAITING_EVALUATION))
        .extracting(Situation::id)
        .doesNotContain(owner() + "-situation-2");
  }

  @Test
  @DisplayName("the observations behind a situation come back, and only that situation's")
  void observedEventsAreFoundBySituation() {
    final var mine = owner() + "-situation-3";
    final var theirs = owner() + "-situation-4";
    observedEventRepo.save(
        ObservedEvent.builder()
            .id(owner() + "-delivery-1")
            .situationId(mine)
            .source("github")
            .kind("issues.opened")
            .summary("nobody has answered this")
            .payloadJson("{\"action\":\"opened\"}")
            .observedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS))
            .build());
    observedEventRepo.save(
        ObservedEvent.builder()
            .id(owner() + "-delivery-2")
            .situationId(theirs)
            .source("github")
            .kind("issues.opened")
            .summary("a different thing entirely")
            .build());

    final var found = observedEventRepo.findBySituationId(mine);
    assertThat(found).extracting(ObservedEvent::id).containsExactly(owner() + "-delivery-1");
    assertThat(found.getFirst().payloadJson()).contains("opened");
    assertThat(found.getFirst().summary()).isEqualTo("nobody has answered this");
    assertThat(observedEventRepo.findBySituationId(theirs))
        .extracting(ObservedEvent::id)
        .containsExactly(owner() + "-delivery-2");
  }

  @Test
  @DisplayName("recording a delivery twice leaves one observation, whatever the backend")
  void observedEventsAreKeyedByDeliveryId() {
    // The id is the transport's delivery key rather than a generated one, which is the whole of the
    // deduplication story for this table: a redelivery rewrites the row it already wrote. A backend
    // where save appended instead would inflate the evidence behind every situation.
    final var id = owner() + "-delivery-3";
    final var situationId = owner() + "-situation-5";
    observedEventRepo.save(
        ObservedEvent.builder().id(id).situationId(situationId).summary("first").build());
    observedEventRepo.save(
        ObservedEvent.builder().id(id).situationId(situationId).summary("second").build());

    final var found = observedEventRepo.findBySituationId(situationId);
    assertThat(found).hasSize(1);
    assertThat(found.getFirst().summary()).isEqualTo("second");
  }
}
