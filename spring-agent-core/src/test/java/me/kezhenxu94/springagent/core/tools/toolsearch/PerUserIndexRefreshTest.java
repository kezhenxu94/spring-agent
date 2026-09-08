package me.kezhenxu94.springagent.core.tools.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * The tool index is keyed per user, and nothing ever invalidates it by name. This is what makes
 * that safe: the advisor fingerprints the names and descriptions of the tool set it is handed and
 * re-indexes that key whenever the fingerprint moves, so the index is derived from the tool set
 * rather than kept in step with it by hand.
 *
 * <p>Worth a test of its own because the two halves belong to different owners. Upstream decides
 * when to re-index; {@link StatelessVectorToolIndex} decides what clearing an index removes. Only
 * together do they add up to "a user whose MCP server renamed a tool stops being offered the old
 * name" — and the failure is silent either way, since a stale index answers searches perfectly
 * well, just with tools that no longer exist.
 *
 * <p>What it does not claim: that the index refreshes the moment the server changes. It refreshes
 * on that user's next run, which is the only time anything here is asked a question.
 */
class PerUserIndexRefreshTest {

  private static final String USER = "ou_1";

  @Test
  @DisplayName("a renamed tool replaces the old one in that user's index, and only theirs")
  void aChangedToolSetReindexesTheUsersIndex() {
    final var store = new RecordingVectorStore();
    final var index = new StatelessVectorToolIndex(store);
    final var advisor =
        ToolSearchToolCallingAdvisor.builder()
            .toolIndex(index)
            .toolCallingManager(ToolCallingManager.builder().build())
            .sessionIdKeyName(SpringAgent.TOOL_INDEX_KEY)
            .systemMessageSuffix("Search for tools before answering.")
            .build();
    final var client = ChatClient.builder(new PlainAnswerChatModel()).build();

    // Another user, indexed once and never asked again: their index must survive the run below.
    run(client, advisor, "ou_2", stub("search_issues"));
    run(client, advisor, USER, stub("search_issues"));

    assertThat(store.toolNamesFor(USER)).containsExactly("search_issues");

    // The server renamed the tool. Nothing tells the index so; the next run for this user is what
    // notices, because the fingerprint it computes no longer matches the one it stored.
    run(client, advisor, USER, stub("find_issues"));

    assertThat(store.toolNamesFor(USER)).containsExactly("find_issues");
    assertThat(store.toolNamesFor("ou_2")).containsExactly("search_issues");
  }

  @Test
  @DisplayName("an unchanged tool set is not embedded a second time")
  void anUnchangedToolSetIsNotReindexed() {
    final var store = new RecordingVectorStore();
    final var index = new StatelessVectorToolIndex(store);
    final var advisor =
        ToolSearchToolCallingAdvisor.builder()
            .toolIndex(index)
            .toolCallingManager(ToolCallingManager.builder().build())
            .sessionIdKeyName(SpringAgent.TOOL_INDEX_KEY)
            .systemMessageSuffix("Search for tools before answering.")
            .build();
    final var client = ChatClient.builder(new PlainAnswerChatModel()).build();

    run(client, advisor, USER, stub("search_issues"));
    final var afterFirstRun = store.adds;
    run(client, advisor, USER, stub("search_issues"));

    // The point of keying the index per user rather than per conversation: a few hundred tool
    // descriptions are embedded once, not once a thread.
    assertThat(store.adds).isEqualTo(afterFirstRun);
  }

  private static void run(
      final ChatClient client,
      final ToolSearchToolCallingAdvisor advisor,
      final String indexKey,
      final ToolCallback... tools) {
    client
        .prompt()
        .system("You are an agent.")
        .user("List my issues.")
        .tools((Object[]) tools)
        .advisors(advisor)
        .advisors(a -> a.param(SpringAgent.TOOL_INDEX_KEY, indexKey))
        .call()
        .content();
  }

  private static ToolCallback stub(final String name) {
    final var definition =
        ToolDefinition.builder()
            .name(name)
            .description(name + " does something")
            .inputSchema("{}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return "";
      }

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        return "";
      }
    };
  }

  /** Answers once, with text, so the tool-calling loop finishes after one iteration. */
  private static final class PlainAnswerChatModel implements ChatModel {
    @Override
    public ToolCallingChatOptions getOptions() {
      return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(final Prompt prompt) {
      return new ChatResponse(
          List.of(
              new Generation(
                  new org.springframework.ai.chat.messages.AssistantMessage("done"),
                  ChatGenerationMetadata.NULL)));
    }
  }

  /**
   * Keeps what was written, keyed by document id, and honours the one filter the index uses —
   * equality on {@code sessionId}, which is what {@link StatelessVectorToolIndex} clears by.
   */
  private static final class RecordingVectorStore implements VectorStore {

    private final Map<String, Document> documents = new LinkedHashMap<>();
    private int adds;

    List<String> toolNamesFor(final String indexKey) {
      return documents.values().stream()
          .filter(document -> indexKey.equals(document.getMetadata().get("sessionId")))
          .map(document -> (String) document.getMetadata().get("toolName"))
          .toList();
    }

    @Override
    public void add(final List<Document> documents) {
      adds += documents.size();
      documents.forEach(document -> this.documents.put(document.getId(), document));
    }

    @Override
    public void delete(final List<String> idList) {
      idList.forEach(documents::remove);
    }

    @Override
    public void delete(final Filter.Expression filterExpression) {
      delete(matching(filterExpression));
    }

    private List<String> matching(final Filter.Expression expression) {
      assertThat(expression.type()).isEqualTo(Filter.ExpressionType.EQ);
      final var key = ((Filter.Key) expression.left()).key();
      final var value = ((Filter.Value) expression.right()).value();
      final var ids = new ArrayList<String>();
      documents.forEach(
          (id, document) -> {
            if (value.equals(document.getMetadata().get(key))) {
              ids.add(id);
            }
          });
      return ids;
    }

    @Override
    public List<Document> similaritySearch(final String query) {
      return List.copyOf(documents.values());
    }

    @Override
    public List<Document> similaritySearch(final SearchRequest request) {
      return similaritySearch(request.getQuery());
    }

    @Override
    public <T> Optional<T> getNativeClient() {
      return Optional.empty();
    }
  }
}
