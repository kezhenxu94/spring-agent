package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.advisors.TurnContextAdvisor;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Rag;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskExecutor;

/**
 * The retrieval advisor a run is composed with is built fresh every turn, and what it runs its
 * queries on must not be. Spring AI's default is a thread pool per advisor that nobody shuts down,
 * which leaked four platform threads per turn until a week-old process could not start one.
 */
class AgentToolsProviderKnowledgeRetrievalTest {

  @TempDir Path location;

  @Test
  @DisplayName("every turn's retrieval advisor shares one executor, and none starts a pool")
  void advisorsShareOneExecutor() throws Exception {
    final var provider = provider();
    final var before = advisorPoolThreads();

    final var first = executorOf(provider);
    final var second = executorOf(provider);

    assertThat(first).isSameAs(second);
    // The name Spring AI gives its own default pool's threads, and nothing else uses it. Counting
    // them is the check that would have caught the leak on the pod, so it is the one kept here.
    assertThat(advisorPoolThreads()).isEqualTo(before);
  }

  @Test
  @DisplayName("retrieval runs once per message, after the turn context and before the tool loop")
  void retrievalPrecedesTheToolLoop() throws Exception {
    // Inside the loop it searched once per tool round, and the message the loop saved lacked what
    // it had added, so the next turn's request no longer matched this one's for the prompt cache.
    // After the turn context because the passages go into its block.
    assertThat(retrievalAdvisorOf(provider()).getOrder())
        .isGreaterThan(TurnContextAdvisor.ORDER)
        .isLessThan(ToolCallingAdvisor.DEFAULT_ORDER);
  }

  private TaskExecutor executorOf(final AgentToolsProvider provider) throws Exception {
    final var advisor = retrievalAdvisorOf(provider);
    final var field = RetrievalAugmentationAdvisor.class.getDeclaredField("taskExecutor");
    field.setAccessible(true);
    return (TaskExecutor) field.get(advisor);
  }

  private static RetrievalAugmentationAdvisor retrievalAdvisorOf(final AgentToolsProvider provider)
      throws Exception {
    return (RetrievalAugmentationAdvisor)
        provider
            .compose(
                AgentRequest.builder()
                    .scenario(BuiltInScenarios.CHAT)
                    .userId("ou_1")
                    .chatId("oc_1")
                    .userMessage(user -> user.text("what did we decide?"))
                    .build(),
                Map.of(),
                todos -> {},
                null,
                false,
                references -> {})
            .advisors()
            .stream()
            .filter(RetrievalAugmentationAdvisor.class::isInstance)
            .findFirst()
            .orElseThrow();
  }

  private static long advisorPoolThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith("ai-advisor-"))
        .count();
  }

  @Test
  @DisplayName(
      "a playbook's chunks reach the model in document order, the same bytes on every run, though"
          + " they carry no scores")
  void playbookChunksKeepTheirOrder() throws Exception {
    // Shaped like a filtered retrieval: no score, and a fresh random id per run, which is what the
    // default joiner hashed on — so it shuffled the chunks, differently each time.
    final var provider =
        provider(
            query ->
                java.util.stream.IntStream.range(0, 16)
                    .mapToObj(
                        i ->
                            org.springframework.ai.document.Document.builder()
                                .text("step " + i + " of the playbook")
                                .metadata(Map.of("docId", "pb", "chunk", i))
                                .build())
                    .toList());

    final var first = augmentedTextOf(retrievalAdvisorOf(provider));
    final var second = augmentedTextOf(retrievalAdvisorOf(provider));

    var previous = -1;
    for (var i = 0; i < 16; i++) {
      final var at = first.indexOf("step " + i + " of the playbook");
      assertThat(at).as("step %d", i).isGreaterThan(previous);
      previous = at;
    }
    assertThat(second).isEqualTo(first);
  }

  /** The user message the advisor hands on, with what it retrieved added. */
  private static String augmentedTextOf(final RetrievalAugmentationAdvisor advisor) {
    final var request =
        org.springframework.ai.chat.client.ChatClientRequest.builder()
            .prompt(
                new org.springframework.ai.chat.prompt.Prompt(
                    new org.springframework.ai.chat.messages.UserMessage("what do we do?")))
            .context(Map.of())
            .build();
    return advisor
        .before(
            request,
            org.mockito.Mockito.mock(
                org.springframework.ai.chat.client.advisor.api.AdvisorChain.class))
        .prompt()
        .getUserMessage()
        .getText();
  }

  private AgentToolsProvider provider() {
    return provider(query -> List.of());
  }

  @SuppressWarnings("unchecked")
  private AgentToolsProvider provider(
      final org.springframework.ai.rag.retrieval.search.DocumentRetriever retriever) {
    final var base = mock(KnowledgeBase.class);
    when(base.retrieverFor(any(), any())).thenReturn(retriever);
    final ObjectProvider<KnowledgeBase> bases = mock(ObjectProvider.class);
    when(bases.getIfAvailable()).thenReturn(base);
    final var context = new AnnotationConfigApplicationContext();
    context.refresh();
    return new AgentToolsProvider(
        new UserWorkspaceFactory(
            FileSystemStorageProperties.builder().location(location.toString()).build()),
        mock(McpServerConfigRepo.class),
        mock(McpClientFactory.class),
        context,
        properties(),
        new Admins(properties()),
        new TenantWrites(properties(), new Admins(properties())),
        TestI18n.english(),
        bases);
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(
            Set.of(),
            null,
            Map.of(),
            null,
            new Rag(true, 4, 0.5d, 0, 0),
            null,
            "You are an agent.",
            null,
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }
}
