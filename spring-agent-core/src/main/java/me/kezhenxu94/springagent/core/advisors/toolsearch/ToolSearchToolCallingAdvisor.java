/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.kezhenxu94.springagent.core.advisors.toolsearch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchTool;
import org.springframework.ai.tool.toolsearch.eviction.CompositeEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.LruEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.ToolIndexEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.TtlEvictionStrategy;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Forked from {@code
 * org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor}, changed in
 * exactly one place: {@link #initializeSession} no longer calls {@link
 * Prompt#augmentSystemMessage}, which mutates the <em>first</em> {@code SystemMessage} it finds in
 * the request and stops there. That was indistinguishable from correct while a run ever carried
 * exactly one system message; it stopped being correct the moment {@code
 * me.kezhenxu94.springagent.core.config.SystemPromptParts} let one carry several, one per
 * configured {@code system-prompt} part — the suffix landed on whichever piece an operator happened
 * to write first, which is arbitrary and usually not what a cache boundary or a persona block wants
 * sitting next to it. {@link #appendToLastSystemMessage} does the same append, on the last system
 * message instead, matching {@code MemoryToolsAdvisor} and {@code AutoSkillToolsAdvisor} in this
 * same package, which carry the identical fix and the identical reason for it.
 *
 * <p>A fork rather than a subclass because the method that needed changing, {@code
 * initializeSession}, is {@code private} upstream — nothing here could have overridden it. Forking
 * the whole file is therefore the honest cost of this fix, not a choice: {@code private} means
 * upstream never intended a caller to reach in here, and every method below this comment is
 * upstream's own, copied rather than rewritten, so that a future diff against the real class stays
 * legible. Read that class's own javadoc before touching anything past {@link #initializeSession}.
 *
 * <p>Everything else — the builder, the properties it takes, the eviction and fingerprinting logic
 * — is unchanged, which is what keeps this a small, auditable diff rather than a reimplementation:
 * {@code SpringAgentToolSearchAdvisorConfiguration} builds this exactly where {@code
 * ToolSearchAdvisorAutoConfiguration} would have built the original, from the same {@code
 * ToolSearchAdvisorProperties}.
 */
public class ToolSearchToolCallingAdvisor extends ToolCallingAdvisor {

  private static final JsonHelper jsonHelper = new JsonHelper();

  private static final String CACHED_TOOL_CALLBACKS_KEY =
      ToolSearchToolCallingAdvisor.class.getName() + ".cachedToolCallbacks";

  /**
   * Internal Tool implementing the tool search functionality. It is registered, automatically,
   * under the name "toolSearchTool".
   */
  private final ToolCallback toolSearchToolCallback;

  /** The ToolIndex used to find tools based on search queries. */
  private final ToolIndex toolIndex;

  /**
   * The Tool Search system message suffix augment to be added to the prompt during initialization.
   */
  private final String systemMessageSuffix;

  /**
   * If enabled, accumulates all tool search tool responses to find tool references.
   *
   * <p>If disabled, only the last tool search tool response is used to find tool references.
   */
  private final boolean referenceToolNameAccumulation;

  private final String sessionIdKeyName;

  /**
   * Tracks the fingerprint (sorted name+description hash) of the tool set last indexed for each
   * session. A fingerprint match means the session's index is still valid and re-indexing can be
   * skipped.
   */
  private final ConcurrentHashMap<String, String> indexedSessionFingerprints =
      new ConcurrentHashMap<>();

  private final ToolIndexEvictionStrategy evictionStrategy;

  protected ToolSearchToolCallingAdvisor(
      final ToolCallingManager toolCallingManager,
      final int advisorOrder,
      final ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
      final ToolIndex toolIndex,
      final String systemMessageSuffix,
      final boolean referenceToolNameAccumulation,
      @Nullable final Integer maxResults,
      final boolean conversationHistoryEnabled,
      final String sessionIdKeyName,
      final ToolIndexEvictionStrategy evictionStrategy) {

    super(
        toolCallingManager,
        toolExecutionEligibilityChecker,
        advisorOrder,
        conversationHistoryEnabled);
    this.toolIndex = toolIndex;
    this.systemMessageSuffix = systemMessageSuffix;
    this.referenceToolNameAccumulation = referenceToolNameAccumulation;
    this.sessionIdKeyName = sessionIdKeyName;
    this.evictionStrategy = evictionStrategy;
    this.toolSearchToolCallback =
        MethodToolCallbackProvider.builder()
            .toolObjects(new ToolSearchTool(toolIndex, maxResults))
            .build()
            .getToolCallbacks()[0];
  }

  @Override
  @SuppressWarnings("null")
  public String getName() {
    return "ToolSearchToolCallingAdvisor";
  }

  // -------------------------------------------------------------------------
  // Sync hooks
  // -------------------------------------------------------------------------

  @Override
  protected ChatClientRequest doInitializeLoop(
      final ChatClientRequest chatClientRequest, final CallAdvisorChain callAdvisorChain) {
    if (chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions) {
      return initializeSession(chatClientRequest);
    }
    return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
  }

  @Override
  protected ChatClientRequest doBeforeCall(
      final ChatClientRequest chatClientRequest, final CallAdvisorChain callAdvisorChain) {
    if (chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions) {
      return prepareIteration(chatClientRequest);
    }
    return super.doBeforeCall(chatClientRequest, callAdvisorChain);
  }

  // -------------------------------------------------------------------------
  // Stream hooks
  // -------------------------------------------------------------------------

  @Override
  protected ChatClientRequest doInitializeLoopStream(
      final ChatClientRequest chatClientRequest, final StreamAdvisorChain streamAdvisorChain) {
    if (chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions) {
      return initializeSession(chatClientRequest);
    }
    return super.doInitializeLoopStream(chatClientRequest, streamAdvisorChain);
  }

  @Override
  protected ChatClientRequest doBeforeStream(
      final ChatClientRequest chatClientRequest, final StreamAdvisorChain streamAdvisorChain) {
    if (chatClientRequest.prompt().getOptions() instanceof ToolCallingChatOptions) {
      return prepareIteration(chatClientRequest);
    }
    return super.doBeforeStream(chatClientRequest, streamAdvisorChain);
  }

  // -------------------------------------------------------------------------
  // Shared logic
  // -------------------------------------------------------------------------

  /**
   * Indexes tools for the session (skipping re-indexing when the tool set is unchanged), runs
   * eviction, and augments the system message.
   */
  @SuppressWarnings("null")
  private ChatClientRequest initializeSession(final ChatClientRequest chatClientRequest) {
    final ToolCallingChatOptions toolOptions =
        (ToolCallingChatOptions) chatClientRequest.prompt().getOptions();

    final String sessionId = this.getSessionId(chatClientRequest.context());

    // Evict stale sessions before touching the current one.
    this.evictionStrategy.onAccess(sessionId).forEach(this::doEvict);

    // validation of tool options happens in the tool calling advisor, so we can
    // assume that if tool options are present, they are valid and contain either tool
    // callbacks or tool names to search for.
    final List<ToolReference> toolReferences =
        this.toolCallingManager.resolveToolDefinitions(Objects.requireNonNull(toolOptions)).stream()
            .map(
                toolDef ->
                    ToolReference.builder()
                        .toolName(toolDef.name())
                        .summary(toolDef.description())
                        .build())
            .toList();

    // Re-index only when the tool set has changed for this session.
    // compute() serializes concurrent requests for the same session so that only one
    // thread performs clear+reindex when the fingerprint changes.
    final String fingerprint = computeFingerprint(toolReferences);
    this.indexedSessionFingerprints.compute(
        sessionId,
        (id, current) -> {
          if (!fingerprint.equals(current)) {
            this.toolIndex.clearIndex(id);
            this.toolIndex.indexTools(id, toolReferences);
          }
          return fingerprint;
        });

    final ConcurrentHashMap<String, ToolCallback> cachedResolvedToolCallbacks =
        new ConcurrentHashMap<>();
    if (!CollectionUtils.isEmpty(toolOptions.getToolCallbacks())) {
      toolOptions
          .getToolCallbacks()
          .forEach(
              tc -> cachedResolvedToolCallbacks.putIfAbsent(tc.getToolDefinition().name(), tc));
    }

    chatClientRequest.context().put(CACHED_TOOL_CALLBACKS_KEY, cachedResolvedToolCallbacks);
    chatClientRequest.context().put(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY, sessionId);

    // Was chatClientRequest.prompt().copy().augmentSystemMessage(...) upstream — see the class
    // comment for why that mutated whichever SystemMessage happened to be first instead of the one
    // that should carry this suffix: the last.
    final var augmented =
        appendToLastSystemMessage(chatClientRequest.prompt().copy(), this.systemMessageSuffix);
    return chatClientRequest.mutate().prompt(augmented).build();
  }

  /**
   * {@code suffix} appended to the last {@link SystemMessage} in {@code prompt}, or a new one at
   * the front where the prompt carries none. See the class comment for why this replaces {@link
   * Prompt#augmentSystemMessage}, and {@code MemoryToolsAdvisor}/{@code AutoSkillToolsAdvisor} for
   * the identical helper on the two advisors that had the same upstream problem.
   */
  private static Prompt appendToLastSystemMessage(final Prompt prompt, final String suffix) {
    final var messages = new ArrayList<>(prompt.getInstructions());
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof SystemMessage systemMessage) {
        messages.set(
            i, systemMessage.copy().mutate().text(systemMessage.getText() + suffix).build());
        return new Prompt(messages, prompt.getOptions());
      }
    }
    messages.add(0, SystemMessage.builder().text(suffix).build());
    return new Prompt(messages, prompt.getOptions());
  }

  // Selects tools discovered via previous toolSearchTool calls and injects them into
  // options.
  @SuppressWarnings({"null", "unchecked"})
  private ChatClientRequest prepareIteration(final ChatClientRequest chatClientRequest) {
    final ToolCallingChatOptions toolOptions =
        Objects.requireNonNull((ToolCallingChatOptions) chatClientRequest.prompt().getOptions());

    final Set<ToolCallback> selectedToolCallbacks =
        new HashSet<>(List.of(this.toolSearchToolCallback));

    final var cachedToolCallbacks =
        (Map<String, ToolCallback>) chatClientRequest.context().get(CACHED_TOOL_CALLBACKS_KEY);

    if (cachedToolCallbacks != null) {
      this.extractToolNameReferences(chatClientRequest.prompt().getInstructions())
          .forEach(
              toolName -> {
                if (cachedToolCallbacks.containsKey(toolName)) {
                  selectedToolCallbacks.add(cachedToolCallbacks.get(toolName));
                }
              });
    }

    final ToolCallingChatOptions toolOptionsCopy =
        ((ToolCallingChatOptions.Builder<?>) toolOptions.mutate())
            .toolCallbacks(new ArrayList<>(selectedToolCallbacks))
            .toolContext(
                ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY,
                Objects.requireNonNull(
                    chatClientRequest
                        .context()
                        .get(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY)))
            .build();

    return chatClientRequest
        .mutate()
        .prompt(chatClientRequest.prompt().mutate().chatOptions(toolOptionsCopy).build())
        .build();
  }

  /**
   * Explicitly evicts a session's tool index and removes it from the advisor's cache.
   *
   * <p>Call this when a conversation is known to be over (e.g., on logout or session expiry) to
   * free resources held by the underlying {@link ToolIndex}.
   *
   * @param sessionId the session to evict
   */
  public void evictSession(final String sessionId) {
    doEvict(sessionId);
  }

  private void doEvict(final String sessionId) {
    this.toolIndex.clearIndex(sessionId);
    this.indexedSessionFingerprints.remove(sessionId);
    this.evictionStrategy.onRemoved(sessionId);
  }

  private List<String> extractToolNameReferences(final List<Message> messages) {

    // Group toolSearchTool responses per tool-response message, i.e. per assistant
    // turn. A single turn may contain several parallel toolSearchTool calls, all of
    // which arrive within one ToolResponseMessage.
    final List<List<ToolResponse>> toolSearchResponsesPerTurn =
        messages.stream()
            .filter(m -> m.getMessageType() == MessageType.TOOL)
            .map(
                m ->
                    ((ToolResponseMessage) m)
                        .getResponses().stream()
                            .filter(
                                r ->
                                    r.name()
                                        .equalsIgnoreCase(
                                            this.toolSearchToolCallback.getToolDefinition().name()))
                            .toList())
            .filter(responses -> !responses.isEmpty())
            .toList();

    if (toolSearchResponsesPerTurn.isEmpty()) {
      return List.of();
    }

    // When accumulation is disabled only the most recent search turn is honored, but
    // that turn may contain multiple parallel toolSearchTool calls that must all be
    // kept.
    final List<List<ToolResponse>> selectedTurns =
        this.referenceToolNameAccumulation
            ? toolSearchResponsesPerTurn
            : List.of(toolSearchResponsesPerTurn.get(toolSearchResponsesPerTurn.size() - 1));

    return selectedTurns.stream()
        .flatMap(List::stream)
        .map(
            r ->
                jsonHelper.fromJson(
                    r.responseData(), new ParameterizedTypeReference<List<String>>() {}))
        .flatMap(List::stream)
        .toList();
  }

  private String getSessionId(final Map<String, @Nullable Object> context) {
    Assert.notNull(context, "context cannot be null");
    Assert.noNullElements(context.keySet().toArray(), "context cannot contain null keys");
    Assert.notNull(
        context.get(this.sessionIdKeyName),
        "context must contain a non-null value for '" + this.sessionIdKeyName + "'");

    return context.get(this.sessionIdKeyName).toString();
  }

  /**
   * Computes a stable SHA-256 fingerprint for the given tool set. Tools are sorted by name so that
   * registration order does not affect equality. Hashing avoids false cache hits that
   * string-concatenation with delimiters can produce when names or summaries contain those
   * delimiter characters.
   */
  private static String computeFingerprint(final List<ToolReference> toolReferences) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      toolReferences.stream()
          .sorted(Comparator.comparing(ToolReference::toolName))
          .forEachOrdered(
              tr -> {
                digest.update(tr.toolName().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0); // field separator
                digest.update(tr.summary().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1); // entry separator
              });
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  // -------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------

  /**
   * Creates a new Builder instance for constructing a ToolSearchToolCallingAdvisor.
   *
   * @return a new Builder instance
   */
  public static Builder<?> builder() {
    return new Builder<>();
  }

  /**
   * Builder for creating instances of ToolSearchToolCallingAdvisor.
   *
   * <p>This builder extends {@link ToolCallingAdvisor.Builder} and adds configuration options
   * specific to tool search functionality.
   *
   * @param <T> the builder type, used for self-referential generics to support method chaining in
   *     subclasses
   */
  public static class Builder<T extends Builder<T>> extends ToolCallingAdvisor.Builder<T> {

    @Nullable private ToolIndex toolIndex;

    @Nullable private String systemMessageSuffix;

    private boolean referenceToolNameAccumulation = true;

    @Nullable private Integer maxResults;

    private String sessionIdKeyName = ChatMemory.CONVERSATION_ID;

    private ToolIndexEvictionStrategy evictionStrategy = new LruEvictionStrategy(1000);

    protected Builder() {}

    public T referenceToolNameAccumulation(final boolean referenceToolNameAccumulation) {
      this.referenceToolNameAccumulation = referenceToolNameAccumulation;
      return self();
    }

    public T systemMessageSuffix(final String systemMessageSuffix) {
      Assert.hasText(systemMessageSuffix, "systemMessageSuffix cannot be null or empty");
      this.systemMessageSuffix = systemMessageSuffix;
      return self();
    }

    /**
     * Sets the ToolIndex to be used for finding tools.
     *
     * @param toolIndex the ToolIndex instance
     * @return this Builder instance for method chaining
     */
    public T toolIndex(final ToolIndex toolIndex) {
      Assert.notNull(toolIndex, "toolIndex cannot be null");
      this.toolIndex = toolIndex;
      return self();
    }

    /**
     * Sets the maximum number of tool references to return in tool search results. This is the
     * human/user defined default value used when invoking the tool search tool.
     *
     * @param maxResults maximum number of tool references
     * @return this Builder instance for method chaining
     */
    public T maxResults(final Integer maxResults) {
      this.maxResults = maxResults;
      return self();
    }

    /**
     * Sets the key name in the context where the conversation ID is stored. By default, it is
     * "conversationId", but it can be customized if the conversation ID is stored under a different
     * key in the context.
     *
     * @param sessionIdKeyName the context key
     * @return this Builder instance for method chaining
     */
    public T sessionIdKeyName(final String sessionIdKeyName) {
      Assert.hasText(sessionIdKeyName, "sessionIdKeyName cannot be null or empty");
      this.sessionIdKeyName = sessionIdKeyName;
      return self();
    }

    /**
     * Sets the eviction strategy that determines when session tool indexes are cleared from the
     * advisor's cache.
     *
     * <p>Defaults to {@code new LruEvictionStrategy(1000)}, which retains indexes for at most 1 000
     * concurrently active sessions and silently evicts the least-recently-used one when the cap is
     * exceeded. Adjust the cap to match the expected peak concurrency of your deployment — lower
     * values reduce memory pressure, higher values reduce unnecessary re-indexing for services with
     * many parallel conversations.
     *
     * <p>Combine with {@link TtlEvictionStrategy} via {@link CompositeEvictionStrategy} to also
     * release indexes for sessions that have been idle longer than a fixed duration.
     *
     * @param evictionStrategy the eviction strategy to use; must not be {@code null}
     * @return this Builder instance for method chaining
     * @see LruEvictionStrategy
     * @see TtlEvictionStrategy
     * @see CompositeEvictionStrategy
     */
    public T evictionStrategy(final ToolIndexEvictionStrategy evictionStrategy) {
      Assert.notNull(evictionStrategy, "evictionStrategy must not be null");
      this.evictionStrategy = evictionStrategy;
      return self();
    }

    /**
     * Builds and returns a new ToolSearchToolCallingAdvisor instance with the configured
     * properties.
     *
     * @return a new ToolSearchToolCallingAdvisor instance
     * @throws IllegalArgumentException if required parameters are null or invalid
     */
    @Override
    public ToolSearchToolCallingAdvisor build() {

      if (!StringUtils.hasText(this.systemMessageSuffix)) {
        try {
          this.systemMessageSuffix =
              new DefaultResourceLoader()
                  .getResource("classpath:/DEFAULT_SYSTEM_PROMPT_SUFFIX.md")
                  .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
          throw new IllegalArgumentException(
              "Failed to load default system message suffix from classpath resource", ex);
        }
      }

      Assert.notNull(this.toolIndex, "toolIndex is required");
      return new ToolSearchToolCallingAdvisor(
          getToolCallingManager(),
          getAdvisorOrder(),
          getToolExecutionEligibilityChecker(),
          this.toolIndex,
          Objects.requireNonNull(this.systemMessageSuffix),
          this.referenceToolNameAccumulation,
          this.maxResults,
          this.isConversationHistoryEnabled(),
          this.sessionIdKeyName,
          this.evictionStrategy);
    }

    @Override
    protected ToolCallingAdvisor.Builder<?> newCopy() {
      return new Builder<>();
    }

    @Override
    public ToolCallingAdvisor.Builder<?> copy() {
      final Builder<?> copy = (Builder<?>) super.copy();
      copy.toolIndex = this.toolIndex;
      copy.systemMessageSuffix = this.systemMessageSuffix;
      copy.referenceToolNameAccumulation = this.referenceToolNameAccumulation;
      copy.maxResults = this.maxResults;
      copy.sessionIdKeyName = this.sessionIdKeyName;
      copy.evictionStrategy = this.evictionStrategy;
      return copy;
    }
  }
}
