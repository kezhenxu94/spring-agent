package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
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
 */
abstract class AbstractPersistenceBackendTest extends AbstractIntegrationTest {

  @Autowired McpServerConfigRepo mcpServerConfigRepo;
  @Autowired ScheduledTaskRepo scheduledTaskRepo;
  @Autowired PendingQuestionRepo pendingQuestionRepo;

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
}
