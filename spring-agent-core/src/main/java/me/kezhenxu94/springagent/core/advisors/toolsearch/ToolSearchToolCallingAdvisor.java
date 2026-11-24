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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import me.kezhenxu94.springagent.core.advisors.MemoryToolCallingAdvisor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchTool;
import org.springframework.ai.tool.toolsearch.eviction.CompositeEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.LruEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.ToolIndexEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.TtlEvictionStrategy;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Forked from {@code
 * org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor}, and by now
 * different from it in what a search does, not only in where its suffix lands.
 *
 * <p><b>The {@code tools} array is the same on every request of a conversation.</b> Upstream
 * attaches each tool a search names to the next request, so the array grows during a turn and
 * starts over at the next one. On a provider that caches by prefix — DeepSeek and the
 * OpenAI-compatible gateways in front of it — the tool definitions are rendered ahead of the system
 * prompt, so every attachment missed the cache for the whole conversation behind it. Here the array
 * is {@link #ALWAYS_LOADED} plus {@code toolSearchTool} and {@code CallTool}, sorted by name, and
 * nothing is ever attached: a search answers with the schemas of what it found, which then sit in
 * the message history, and the model calls them through {@code CallTool} — see {@code
 * DispatchingToolCallback}. Sorted because the composition's order is not stable from one turn to
 * the next — an MCP server's tools arrive when its handshake does.
 *
 * <p><b>The model is told what exists.</b> Every tool not in the array is listed by name, a group
 * to a line, at the end of the system prompt, after the suffix — see {@link #catalogue}. The list
 * holds a user's MCP servers and skills, so it is the last thing in the system prompt: everything
 * before it is the same for every user. It changes only when somebody adds or removes a tool, which
 * costs one cache miss rather than one per tool found.
 *
 * <p><b>The suffix goes on the last system message</b> rather than the first, which is what
 * upstream's {@link Prompt#augmentSystemMessage} does — with several {@code system-prompt} parts,
 * that put it on whichever piece an operator happened to write first.
 *
 * <p>A fork rather than a subclass because what had to change, {@code initializeSession} and {@code
 * prepareIteration}, is {@code private} upstream. The index, the fingerprinting, the eviction and
 * the builder's configuration are upstream's and read the same {@code ToolSearchAdvisorProperties},
 * except {@code reference-tool-name-accumulation}, which no longer means anything: nothing is
 * attached, so there are no references to accumulate.
 *
 * <p>It extends {@link MemoryToolCallingAdvisor} rather than {@link ToolCallingAdvisor}, so that a
 * run taking the tool search keeps its conversation's memory the way every other run does.
 */
public class ToolSearchToolCallingAdvisor extends MemoryToolCallingAdvisor {

  /**
   * The tools offered directly on every request rather than through the search: the ones nearly
   * every run needs, whose absence cost the model several searches before it could even read a file
   * or its own memory, and the ones core's own system prompt tells the model to call by name. A
   * name this run does not carry — {@code Bash} without a shell — is simply absent. An application
   * adds to these with {@code app.ai.tools.always-loaded}.
   */
  public static final Set<String> ALWAYS_LOADED =
      Set.of(
          "AskUserQuestionTool",
          "Bash",
          "CancelSubagent",
          "CurrentDateTime",
          "Edit",
          "Glob",
          "Grep",
          "MemoryCreate",
          "MemoryDelete",
          "MemoryInsert",
          "MemoryRename",
          "MemoryStrReplace",
          "MemoryView",
          "Read",
          "SearchKnowledge",
          "StartSubagent",
          "TodoWrite",
          "WaitForSubagent",
          "Write");

  /** The search tool's name, which is upstream's and which every run taking the search carries. */
  public static final String SEARCH_TOOL_NAME = "toolSearchTool";

  /** How much of a group's purpose its catalogue line keeps. */
  private static final int PURPOSE_CHARS = 120;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String OFFERED_TOOL_CALLBACKS_KEY =
      ToolSearchToolCallingAdvisor.class.getName() + ".offeredToolCallbacks";

  /** What {@code toolSearchTool} is told it does, now that it answers with schemas. */
  private static final String SEARCH_DESCRIPTION =
      """
      Search the tools listed under "Tools you call through CallTool" for the ones that can do \
      what the current task needs. The query is matched against each tool's name and description.
      Returns each match's name, description and input_schema; call it through CallTool with \
      arguments matching that schema. Nothing is attached to you, so there is nothing to wait for.\
      """;

  /** Upstream's search tool, whose index query and parameters are used as they are. */
  private final ToolCallback toolSearchToolCallback;

  /** The ToolIndex used to find tools based on search queries. */
  private final ToolIndex toolIndex;

  /** Appended to the last system message, ahead of the catalogue. */
  private final String systemMessageSuffix;

  private final String sessionIdKeyName;

  /** {@link #ALWAYS_LOADED} and whatever the application added to it. */
  private final Set<String> alwaysLoaded;

  /** Builds this run's {@code CallTool} over the tools it may call through it. */
  private final Function<Map<String, ToolCallback>, ToolCallback> dispatcher;

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
      @Nullable final Integer maxResults,
      final boolean conversationHistoryEnabled,
      final String sessionIdKeyName,
      final ToolIndexEvictionStrategy evictionStrategy,
      final Function<Map<String, ToolCallback>, ToolCallback> dispatcher,
      final Set<String> alwaysLoaded) {

    super(
        toolCallingManager,
        toolExecutionEligibilityChecker,
        advisorOrder,
        conversationHistoryEnabled);
    this.toolIndex = toolIndex;
    this.systemMessageSuffix = systemMessageSuffix;
    this.sessionIdKeyName = sessionIdKeyName;
    this.evictionStrategy = evictionStrategy;
    this.dispatcher = dispatcher;
    this.alwaysLoaded = alwaysLoaded;
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
   * Splits the run's tools into the ones offered directly and the ones called through {@code
   * CallTool}, indexes the latter (skipping it when that set is unchanged), and appends the suffix
   * and the catalogue to the system message.
   */
  @SuppressWarnings("null")
  private ChatClientRequest initializeSession(final ChatClientRequest chatClientRequest) {
    final ToolCallingChatOptions toolOptions =
        (ToolCallingChatOptions) chatClientRequest.prompt().getOptions();

    final String sessionId = this.getSessionId(chatClientRequest.context());

    // Evict stale sessions before touching the current one.
    this.evictionStrategy.onAccess(sessionId).forEach(this::doEvict);

    final var callbacks = new LinkedHashMap<String, ToolCallback>();
    if (!CollectionUtils.isEmpty(toolOptions.getToolCallbacks())) {
      toolOptions
          .getToolCallbacks()
          .forEach(tc -> callbacks.putIfAbsent(tc.getToolDefinition().name(), tc));
    }

    // Resolved through the manager rather than read off the callbacks, so the schemas a search
    // returns are the ones the model would have been sent: translated, and with the display
    // description the surfaces show.
    final var deferred =
        this.toolCallingManager.resolveToolDefinitions(Objects.requireNonNull(toolOptions)).stream()
            .filter(definition -> !this.alwaysLoaded.contains(definition.name()))
            .filter(definition -> callbacks.containsKey(definition.name()))
            .sorted(Comparator.comparing(ToolDefinition::name))
            .toList();

    final var offered = new ArrayList<ToolCallback>();
    callbacks.values().stream()
        .filter(callback -> this.alwaysLoaded.contains(callback.getToolDefinition().name()))
        .forEach(offered::add);

    var prompt = chatClientRequest.prompt().copy();
    if (!deferred.isEmpty()) {
      final List<ToolReference> toolReferences =
          deferred.stream()
              .map(
                  definition ->
                      ToolReference.builder()
                          .toolName(definition.name())
                          .summary(definition.description())
                          .build())
              .toList();
      // Re-index only when the tool set has changed for this session. compute() serializes
      // concurrent requests for the same session so that only one thread performs clear+reindex
      // when the fingerprint changes.
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

      final var targets = new LinkedHashMap<String, ToolCallback>();
      deferred.forEach(
          definition -> targets.put(definition.name(), callbacks.get(definition.name())));
      offered.add(searchCallback(deferred));
      offered.add(this.dispatcher.apply(targets));
      prompt =
          appendToLastSystemMessage(
              prompt,
              this.systemMessageSuffix
                  + System.lineSeparator()
                  + catalogue(deferred, groupsOf(chatClientRequest)));
    }
    offered.sort(Comparator.comparing(callback -> callback.getToolDefinition().name()));

    chatClientRequest.context().put(OFFERED_TOOL_CALLBACKS_KEY, List.copyOf(offered));
    chatClientRequest.context().put(ToolSearchTool.TOOL_SEARCH_TOOL_SESSION_ID_KEY, sessionId);
    return chatClientRequest.mutate().prompt(prompt).build();
  }

  /**
   * Names only, a group to a line: each MCP server's tools, and the skills, as the group's name,
   * the one line saying what it is for, and the tools' names after the prefix they share — {@code
   * github_{get_issue, search_issues}} — and every other tool on one line of its own. A description
   * per tool cost hundreds of characters a tool on every request, and every token in the context
   * slows every token the model writes; what a tool does exactly is a search away.
   *
   * <p>Sorted throughout, groups by name and names within them, so the same tools make the same
   * text on every request and the system prompt stays cacheable.
   */
  public static String catalogue(
      final List<ToolDefinition> definitions,
      final Map<String, ToolGroupsAdvisor.ToolGroup> groups) {
    final var grouped = new java.util.TreeMap<String, java.util.List<String>>();
    final var byKey = new java.util.HashMap<String, ToolGroupsAdvisor.ToolGroup>();
    final var loose = new java.util.TreeSet<String>();
    for (final var definition : definitions) {
      final var group = groups.get(definition.name());
      if (group == null) {
        loose.add(definition.name());
        continue;
      }
      final var key = group.label() + "\u0000" + group.prefix();
      byKey.put(key, group);
      grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(definition.name());
    }
    final var lines = new StringBuilder();
    for (final var entry : grouped.entrySet()) {
      final var group = byKey.get(entry.getKey());
      lines.append("- ").append(group.label());
      final var purpose = oneLine(group.purpose());
      if (!purpose.isEmpty()) {
        lines.append(" — ").append(purpose);
      }
      lines.append(": ");
      final var names = entry.getValue().stream().sorted().toList();
      if (group.prefix().isEmpty()) {
        lines.append(String.join(", ", names));
      } else {
        lines
            .append(group.prefix())
            .append('{')
            .append(
                String.join(
                    ", ",
                    names.stream().map(name -> name.substring(group.prefix().length())).toList()))
            .append('}');
      }
      lines.append(System.lineSeparator());
    }
    if (!loose.isEmpty()) {
      lines.append("- ").append(String.join(", ", loose)).append(System.lineSeparator());
    }
    return lines.toString();
  }

  /**
   * The groups {@link ToolGroupsAdvisor} left in the context, or none where no such advisor ran.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, ToolGroupsAdvisor.ToolGroup> groupsOf(
      final ChatClientRequest request) {
    final var groups = request.context().get(ToolGroupsAdvisor.CONTEXT_KEY);
    return groups instanceof Map<?, ?> map
        ? (Map<String, ToolGroupsAdvisor.ToolGroup>) map
        : Map.of();
  }

  /** The first line of {@code text}, cut short where it runs on. */
  private static String oneLine(final String text) {
    if (text == null) {
      return "";
    }
    final var first = text.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst();
    if (first.isEmpty()) {
      return "";
    }
    final var line = first.get();
    return line.length() <= PURPOSE_CHARS ? line : line.substring(0, PURPOSE_CHARS) + "…";
  }

  /**
   * {@code toolSearchTool} for this run: upstream's query against the index, answered with the
   * schemas of the matches rather than their names. A name the index holds but this run does not —
   * the index is per user, and a group chat composes other skills than a private one — is left out.
   */
  private ToolCallback searchCallback(final List<ToolDefinition> deferred) {
    final var byName = new LinkedHashMap<String, ToolDefinition>();
    deferred.forEach(definition -> byName.put(definition.name(), definition));
    final var upstream = this.toolSearchToolCallback;
    final var definition =
        ToolDefinition.builder()
            .name(upstream.getToolDefinition().name())
            .description(SEARCH_DESCRIPTION)
            .inputSchema(upstream.getToolDefinition().inputSchema())
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return call(toolInput, null);
      }

      @Override
      public String call(final String toolInput, final @Nullable ToolContext toolContext) {
        final var names = MAPPER.readTree(upstream.call(toolInput, toolContext));
        final ArrayNode found = MAPPER.createArrayNode();
        for (final var name : names) {
          final var match = byName.get(name.stringValue());
          if (match != null) {
            final var entry = found.addObject();
            entry.put("name", match.name());
            entry.put("description", match.description());
            entry.set("input_schema", MAPPER.readTree(match.inputSchema()));
          }
        }
        return MAPPER.writeValueAsString(found);
      }
    };
  }

  /**
   * {@code suffix} appended to the last {@link SystemMessage} in {@code prompt}, or a new one at
   * the front where the prompt carries none. See the class comment for why this replaces {@link
   * Prompt#augmentSystemMessage}.
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

  /** The same tools on every iteration: the ones {@link #initializeSession} chose. */
  @SuppressWarnings({"null", "unchecked"})
  private ChatClientRequest prepareIteration(final ChatClientRequest chatClientRequest) {
    final ToolCallingChatOptions toolOptions =
        Objects.requireNonNull((ToolCallingChatOptions) chatClientRequest.prompt().getOptions());

    final var offered =
        (List<ToolCallback>) chatClientRequest.context().get(OFFERED_TOOL_CALLBACKS_KEY);

    final ToolCallingChatOptions toolOptionsCopy =
        ((ToolCallingChatOptions.Builder<?>) toolOptions.mutate())
            .toolCallbacks(offered == null ? toolOptions.getToolCallbacks() : offered)
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
  public static class Builder<T extends Builder<T>> extends MemoryToolCallingAdvisor.Builder<T> {

    @Nullable private ToolIndex toolIndex;

    @Nullable private String systemMessageSuffix;

    @Nullable private Integer maxResults;

    private String sessionIdKeyName = ChatMemory.CONVERSATION_ID;

    private ToolIndexEvictionStrategy evictionStrategy = new LruEvictionStrategy(1000);

    @Nullable private Function<Map<String, ToolCallback>, ToolCallback> dispatcher;

    private Set<String> alwaysLoaded = ALWAYS_LOADED;

    protected Builder() {}

    /**
     * Tools to offer directly on every request in addition to {@link #ALWAYS_LOADED}. They are
     * chosen from the tools a run was composed with, so naming one never offers a tool the run was
     * not given.
     */
    public T alwaysLoaded(final Collection<String> extra) {
      Assert.notNull(extra, "extra must not be null");
      final var names = new HashSet<>(ALWAYS_LOADED);
      extra.stream().filter(StringUtils::hasText).map(String::strip).forEach(names::add);
      this.alwaysLoaded = Set.copyOf(names);
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
     * exceeded. Combine with {@link TtlEvictionStrategy} via {@link CompositeEvictionStrategy} to
     * also release indexes for sessions that have been idle longer than a fixed duration.
     *
     * @param evictionStrategy the eviction strategy to use; must not be {@code null}
     * @return this Builder instance for method chaining
     */
    public T evictionStrategy(final ToolIndexEvictionStrategy evictionStrategy) {
      Assert.notNull(evictionStrategy, "evictionStrategy must not be null");
      this.evictionStrategy = evictionStrategy;
      return self();
    }

    /**
     * How a run's {@code CallTool} is built over the tools it may call through it, by name — see
     * {@code DispatchingToolCallback}.
     */
    public T dispatcher(final Function<Map<String, ToolCallback>, ToolCallback> dispatcher) {
      Assert.notNull(dispatcher, "dispatcher must not be null");
      this.dispatcher = dispatcher;
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
      Assert.notNull(this.dispatcher, "dispatcher is required");
      return new ToolSearchToolCallingAdvisor(
          getToolCallingManager(),
          getAdvisorOrder(),
          getToolExecutionEligibilityChecker(),
          this.toolIndex,
          Objects.requireNonNull(this.systemMessageSuffix),
          this.maxResults,
          this.isConversationHistoryEnabled(),
          this.sessionIdKeyName,
          this.evictionStrategy,
          this.dispatcher,
          this.alwaysLoaded);
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
      copy.maxResults = this.maxResults;
      copy.sessionIdKeyName = this.sessionIdKeyName;
      copy.evictionStrategy = this.evictionStrategy;
      copy.dispatcher = this.dispatcher;
      copy.alwaysLoaded = this.alwaysLoaded;
      return copy;
    }
  }
}
