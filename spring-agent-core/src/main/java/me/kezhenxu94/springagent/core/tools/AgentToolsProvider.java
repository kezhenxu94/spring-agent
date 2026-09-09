package me.kezhenxu94.springagent.core.tools;

import io.modelcontextprotocol.client.McpSyncClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.advisors.AutoSkillToolsAdvisor;
import me.kezhenxu94.springagent.core.advisors.MemoryToolsAdvisor;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeReference;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeRetrieval;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.memory.MemoryScopes;
import me.kezhenxu94.springagent.core.tools.TodoWriteTool.TodoEventHandler;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import me.kezhenxu94.springagent.core.tools.mcp.ServerNameToolPrefixGenerator;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolsProvider {

  /** The memory prompt's file, without its locale suffix or extension. */
  public static final String MEMORY_PROMPT = "auto-memory";

  /** The skill-offer prompt's file, without its locale suffix or extension. */
  public static final String SKILL_PROMPT = "auto-skill";

  /** The knowledge-retrieval augmentation prompt's file, without its locale suffix or extension. */
  public static final String KNOWLEDGE_RETRIEVAL_PROMPT = "knowledge-retrieval";

  /**
   * The skill tool's description template, without its locale suffix or extension.
   *
   * <p>Not under {@code core/prompts/tools/}, where every other tool's description lives, and that
   * is deliberate: a file there would be applied to the finished definition and would discard the
   * list of skills the library formats into it.
   */
  public static final String SKILL_TOOL_PROMPT = "skill-tool";

  private final UserWorkspaceFactory userWorkspaceFactory;
  private final McpServerConfigRepo mcpServerConfigRepo;
  private final McpClientFactory mcpClientFactory;
  private final ApplicationContext applicationContext;
  private final SpringAgentProperties appConfiguration;

  /**
   * Asked about the tools declared {@link AgentTool#admin()}, and about which memories a run may
   * write to — an admin may write the tenant's from a one-to-one chat, where nobody else may. See
   * {@code MemoryScopes.writable}.
   */
  private final Admins admins;

  /** What the memory block the model reads is written in. */
  private final CoreMessages messages;

  /**
   * The knowledge base, where a {@code spring-agent-rag-*} module supplies one. An {@link
   * ObjectProvider} because core ships no implementation: having none is an ordinary configuration,
   * not a missing dependency, and injecting the type directly would refuse to start every
   * deployment that does not want a knowledge base.
   */
  private final ObjectProvider<KnowledgeBase> knowledgeBase;

  public record AgentTools(
      FileSystemTools fileSystemTools, Optional<ToolCallback> skillsTool, McpTools mcpTools) {}

  /**
   * Live MCP clients built for one request and the tool callbacks derived from them. Must be {@link
   * #close() closed} once the request completes to release connections.
   */
  public record McpTools(List<McpSyncClient> clients, ToolCallback[] callbacks)
      implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpTools.class);

    @Override
    public void close() {
      for (final var client : clients) {
        try {
          client.close();
        } catch (Exception e) {
          log.warn("Failed to close MCP client", e);
        }
      }
    }
  }

  /**
   * Fully assembled agent inputs for one request: the tools a run is offered, the live MCP clients
   * behind some of them, and the resolved memories directory. Callers must close {@code mcpTools()}
   * once the request completes.
   *
   * <p>One array holds every kind of tool because that is what the far end makes of them anyway:
   * {@code ChatClient.tools(Object...)} takes a {@link ToolCallback} as it stands and derives one
   * from anything else, into the same list either way. So a tool that has to arrive as a callback —
   * the ask that ends the turn, whose metadata cannot be set any other way — travels here beside
   * the plain tool objects rather than in a second array that means nothing downstream.
   *
   * <p>An advisor appears here for a related reason, though a narrower one than it used to be:
   * {@link MemoryToolsAdvisor} is the memory tools' documentation. It contributes no callback — the
   * tools are an {@code @AgentTool} bean like any other, for the reasons {@code MemoryTools} gives
   * — but the paragraph naming which memories this run reaches, and which of them it may write to,
   * has to reach the system message, and a tool cannot put anything there. It travels with the
   * tools because it is false without them: a run offered no tools must not be told it has a
   * memory, and a run must not be told about a scope it cannot reach. Only an advisor that exists
   * for the tools belongs in a composition; the ones a run wires up for its own reasons, chat
   * memory and logging, stay with the run.
   */
  public record AgentComposition(Object[] tools, List<Advisor> advisors, McpTools mcpTools) {}

  public AgentComposition compose(
      final AgentRequest request,
      final Map<String, Object> toolContext,
      final TodoEventHandler todoEventHandler,
      final QuestionHandler questionHandler,
      final boolean answersArriveLater,
      final Consumer<List<KnowledgeReference>> knowledgeHandler)
      throws IOException {
    if (!request.scenario().tools()) {
      // No tools means no MCP client is built, which is the whole point of asking before build()
      // rather than filtering after it — the fan-out dials out to every server the user can reach,
      // and a run that will be offered none of them should not pay for the handshakes. The memory
      // advisor goes with them, being tools of its own; retrieval does not, since it contributes
      // no tool and answers to a switch of its own.
      final var retrieval = knowledgeRetrieval(request, knowledgeHandler);
      return new AgentComposition(
          new Object[0],
          retrieval.map(List::of).orElseGet(List::of),
          new McpTools(List.of(), new ToolCallback[0]));
    }
    final var agentTools = build(request.userId(), request.chatId(), toolContext);
    // From here on the MCP clients are live, and the caller only learns of them by being handed the
    // composition. Anything that throws in between would leave them open with nobody holding a
    // reference to close them — the run's own cleanup included, since it has not been given them
    // yet.
    try {
      return composeWith(
          agentTools,
          request,
          todoEventHandler,
          questionHandler,
          answersArriveLater,
          knowledgeHandler);
    } catch (Throwable t) {
      agentTools.mcpTools().close();
      throw t;
    }
  }

  private AgentComposition composeWith(
      final AgentTools agentTools,
      final AgentRequest request,
      final TodoEventHandler todoEventHandler,
      final QuestionHandler questionHandler,
      final boolean answersArriveLater,
      final Consumer<List<KnowledgeReference>> knowledgeHandler)
      throws IOException {
    final var tools = new ArrayList<Object>();
    tools.addAll(resolveScenarioTools(request.scenario(), request.userId()));
    tools.add(agentTools.fileSystemTools());
    tools.add(TodoWriteTool.builder().todoEventHandler(todoEventHandler).build());
    // Two independent gates, and both have to open. No handler means the run has no way to reach
    // the user, so offering the tool would only invite the agent to ask into the void; the property
    // is how a deployment turns the whole interaction off whatever the channel can do.
    if (questionHandler != null && appConfiguration.ai().tools().askUserQuestion().enabled()) {
      // Both of these follow from the same fact. Where the answer only arrives later there is
      // nothing to validate — the ask comes back empty by design — and nothing to hand the model,
      // so the turn ends here. Where a channel answers within the call, validation is a real check
      // that every question came back with something, and the turn has to carry on to use it.
      final var askTool =
          AskUserQuestionTool.builder()
              .answersValidation(!answersArriveLater)
              .questionHandler(questionHandler)
              .build();
      // Ending the turn is a property of the callback, not of the tool, so that path hands over a
      // wrapped one while the other hands over the tool itself and lets the far end derive it.
      tools.add(answersArriveLater ? endsTurnCallback(askTool) : askTool);
    }

    agentTools.skillsTool().ifPresent(tools::add);
    final var callbacks = new ArrayList<ToolCallback>();
    final var mcpCallbacks = agentTools.mcpTools().callbacks();
    if (mcpCallbacks != null) {
      Collections.addAll(callbacks, mcpCallbacks);
    }
    callbacks.addAll(globalToolCallbacks());
    rejectDuplicateToolNames(callbacks);
    tools.addAll(callbacks);

    final var advisors = new ArrayList<Advisor>();
    knowledgeRetrieval(request, knowledgeHandler).ifPresent(advisors::add);
    memoryScopes(request)
        .ifPresent(
            block ->
                advisors.add(
                    MemoryToolsAdvisor.builder()
                        .memoryScopes(block)
                        // Core's own prompt, in the workspace's language, rather than the
                        // library's: the advisor appends whatever this is to the end of the system
                        // message on every request, and the default is two thousand words of
                        // English. A workspace whose prompt is not English gets that English tail
                        // last and closest to the model, which is enough to make it reason in
                        // English about a conversation it answers in another language. The text
                        // also has to be true of this deployment — the default says MEMORY.md is
                        // always loaded into context, and here nothing loads it, nor does it know
                        // that a memory has a scope.
                        .memorySystemPrompt(
                            LocalizedPrompt.resource(MEMORY_PROMPT, appConfiguration.locale()))
                        .build()));

    skillOffer(tools).ifPresent(advisors::add);

    return new AgentComposition(tools.toArray(), List.copyOf(advisors), agentTools.mcpTools());
  }

  /**
   * The block describing this request's memories, or nothing where it has none to describe.
   *
   * <p>Empty only where the request carries no user id — an unattended run assembled without one —
   * and then there is no home to write a memory into either, so the paragraph would be describing
   * tools that can only refuse. Every ordinary run has at least its own.
   */
  private Optional<String> memoryScopes(final AgentRequest request) {
    final var scopes =
        MemoryScopes.forRequest(
            userWorkspaceFactory,
            admins.isAdmin(request.userId()),
            request.userId(),
            request.groupId(),
            request.tenantId());
    final var block = scopes.describe(messages);
    return block.isBlank() ? Optional.empty() : Optional.of(block);
  }

  /**
   * The advisor that offers the user a skill once a turn has cost more tool calls than anyone would
   * want to pay twice, or nothing where this run could not act on the offer anyway.
   *
   * <p>Two gates. The deployment has to want the offer made at all, and the run has to have been
   * given the tools that write a skill — the scenario decides that, and the composed list is where
   * its decision has already been made, so it is read rather than asked a second time. Offering a
   * skill to a run that cannot write one costs the user a turn to find that out.
   *
   * <p>Core's own prompt in the workspace's language, for the reason the memory advisor above
   * gives.
   */
  private Optional<Advisor> skillOffer(final List<Object> tools) {
    final var skills = appConfiguration.ai().tools().skills();
    if (!skills.offerAfterExpensiveRuns()) {
      return Optional.empty();
    }
    if (tools.stream().noneMatch(SkillManagementTools.class::isInstance)) {
      return Optional.empty();
    }
    return Optional.of(
        AutoSkillToolsAdvisor.builder()
            .toolCallThreshold(skills.toolCallThreshold())
            .skillSystemPrompt(LocalizedPrompt.resource(SKILL_PROMPT, appConfiguration.locale()))
            .build());
  }

  /**
   * The advisor that consults the knowledge base before the model answers, scoped to what this
   * request may read.
   *
   * <p>Three independent gates, all of which have to open: a {@code spring-agent-rag-*} module has
   * to be installed, the deployment has to want automatic retrieval, and the scenario has to be one
   * where it makes sense. They are separate because they answer to different people — whoever
   * assembles the build, whoever configures the deployment, and whoever wrote the scenario.
   *
   * <p>The scope filter is baked into the retriever rather than passed as a per-request advisor
   * parameter, which is available but unnecessary here: a fresh advisor is built for every request
   * anyway, so there is nothing for a parameter to vary.
   *
   * <p>What is retrieved, and what it is retrieved against, are the request's to state. Almost no
   * request does: the scope is then the run's own identity and the query is what the person said,
   * which is the only sensible reading of "search my knowledge base for what I just asked". A
   * request that does state a {@link KnowledgeRetrieval} is one whose knowledge base was chosen by
   * configuration rather than by who is asking — event triage — and that record's javadoc is where
   * the reasoning for each of its three fields lives.
   *
   * <p><b>A stated query is used for retrieval only.</b> {@link RetrievalAugmentationAdvisor} runs
   * the query transformers, retrieves with the transformed query, and then calls its augmenter with
   * the <i>original</i> query, so the user message the model sees is unchanged. That is what makes
   * a fixed retrieval query safe to set: a triage run still gets its situation brief, and only the
   * lookup is pinned. Were that to change upstream, the symptom would be a triage prompt replaced
   * wholesale by the configured query string, which is worth recognising quickly.
   *
   * <p>{@code allowEmptyContext} is load-bearing. Spring AI's default is to instruct the model not
   * to answer when retrieval found nothing, which for a general-purpose agent would turn every
   * question the knowledge base has no opinion about — that is, nearly all of them — into a
   * refusal. Here retrieval is an augmentation, not the point of the run.
   *
   * <p>The prompt template is overridden for the same reason. Spring AI's default tells the model
   * to answer strictly from the retrieved passages and "just say you don't know" otherwise — a
   * classic RAG-QA prompt, and a direct contradiction of a general-purpose agent's own system
   * prompt, which expects it to use tools and its own knowledge freely. Left at the default, that
   * contradiction lands on every turn where retrieval finds even a loosely related document, not
   * just the ones it was written for.
   */
  private Optional<Advisor> knowledgeRetrieval(
      final AgentRequest request, final Consumer<List<KnowledgeReference>> knowledgeHandler) {
    final var rag = appConfiguration.ai().rag();
    if (!rag.enabled() || !request.scenario().knowledgeRetrieval()) {
      return Optional.empty();
    }
    final var stated = request.knowledgeRetrieval();
    final var scope =
        stated == null
            ? new KnowledgeScope(request.userId(), request.groupId(), request.tenantId())
            : stated.scope();
    final var extra = stated == null ? null : stated.filter();
    final List<QueryTransformer> transformers =
        stated != null && stated.hasQuery()
            ? List.of(query -> query.mutate().text(stated.query()).build())
            : List.of();
    return Optional.ofNullable(knowledgeBase.getIfAvailable())
        .map(
            base ->
                RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(reporting(base.retrieverFor(scope, extra), knowledgeHandler))
                    .queryTransformers(transformers)
                    .queryAugmenter(
                        ContextualQueryAugmenter.builder()
                            .allowEmptyContext(true)
                            .promptTemplate(
                                new PromptTemplate(
                                    LocalizedPrompt.resource(
                                        KNOWLEDGE_RETRIEVAL_PROMPT, appConfiguration.locale())))
                            .build())
                    // After the chat memory advisor, and this is the constraint that decides the
                    // number — not tidiness.
                    //
                    // Augmentation rewrites the user message to carry the retrieved passages, and
                    // MessageChatMemoryAdvisor.before() writes the request's user message into the
                    // conversation history. Order this ahead of memory and the augmented message is
                    // what gets persisted: the passages become a permanent part of the history,
                    // replayed on every later turn whether or not they are relevant, and no change
                    // of threshold or scope can dislodge them afterwards.
                    //
                    // The cost of sitting here instead is that ToolCallingAdvisor re-invokes
                    // everything ordered after it once per tool round, so a turn with several tool
                    // calls searches more than once. That is wasted embedding calls, and it is the
                    // cheaper of the two failures by a wide margin — the other one corrupts data.
                    .order(ToolCallingAdvisor.DEFAULT_ORDER + 200)
                    .build());
  }

  /**
   * The retriever, wrapped so that what it found is reported as well as used.
   *
   * <p>A decorator rather than reading the documents back out of the advisor's response context,
   * which is where Spring AI also leaves them: retrieval is the only thing that knows it happened,
   * and the run streams rather than handing back a response for anything to inspect afterwards.
   *
   * <p>Reporting must never break retrieval, so a listener that throws is logged and swallowed —
   * the alternative is a surface's rendering bug costing the user their answer. Nothing is reported
   * when nothing was found, so being called means there is something to show.
   */
  private static DocumentRetriever reporting(
      final DocumentRetriever delegate, final Consumer<List<KnowledgeReference>> handler) {
    if (handler == null) {
      return delegate;
    }
    return query -> {
      final var documents = delegate.retrieve(query);
      if (documents != null && !documents.isEmpty()) {
        try {
          handler.accept(KnowledgeReference.of(documents));
        } catch (Exception e) {
          log.warn("A knowledge reference listener failed", e);
        }
      }
      return documents;
    };
  }

  /**
   * The ask, built to end the turn as soon as it has run.
   *
   * <p>Spring AI returns a {@code returnDirect} tool's result to the application instead of looping
   * it back to the model, which is what stops the run without asking the model to stop itself —
   * something it does not reliably do, however plainly the result says to. The library's
   * {@code @Tool} annotation does not set it, and metadata is fixed once a callback is built, so
   * the library's own callback is wrapped rather than rebuilt: everything but the one flag
   * delegates, and nothing here needs the tool method's name or signature.
   *
   * <p>The result also becomes what the user reads, so what the ask returns on this path is written
   * for them rather than for the model.
   */
  private static ToolCallback endsTurnCallback(final AskUserQuestionTool askTool) {
    final var delegate = ToolCallbacks.from(askTool)[0];
    final var endsTurn = ToolMetadata.builder().returnDirect(true).build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
      }

      @Override
      public ToolMetadata getToolMetadata() {
        return endsTurn;
      }

      @Override
      public String call(final String toolInput) {
        return delegate.call(toolInput);
      }

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
      }
    };
  }

  /**
   * Refuses a composition offering two tools of the same name.
   *
   * <p>Spring AI rejects such a request itself, deeper down and without saying which name or where
   * either came from, so this is the same failure reported where the answer is: a chosen {@code
   * toolPrefix} on one MCP server that another server the caller reaches already uses. Registration
   * checks that (see {@code McpServerManagementTools}), but only against what the *owner* can
   * reach, and a server shared with somebody afterwards is a collision nobody was there to catch.
   *
   * <p>Throwing costs the run, which is the point: the alternative is dropping one of the two, and
   * then a tool call goes to whichever server survived the tie — a request meant for staging
   * answered by production. The run is lost either way once Spring AI sees the request, so the only
   * thing left to decide is whether the user is told why.
   *
   * <p>Only the callbacks are checked, not the tool objects beside them: a name is on a callback
   * already, whereas reading one off a {@code @Tool} method means deriving callbacks that would
   * then be thrown away. The names that can collide are the ones assembled from configuration
   * anyway — a duplicate among this repository's own tools is a compile-time-visible mistake, not
   * something a deployment can cause.
   */
  private static void rejectDuplicateToolNames(final List<ToolCallback> callbacks) {
    final var seen = new HashSet<String>(callbacks.size());
    for (final var callback : callbacks) {
      final var name = callback.getToolDefinition().name();
      if (!seen.add(name)) {
        throw new IllegalStateException(
            "Two tools offered to this run are both named '"
                + name
                + "'. Where these come from MCP servers, give one of them a different tool prefix"
                + " (AddMcpServer's toolPrefix) so its tools are named apart.");
      }
    }
  }

  /**
   * Tools contributed to the application as a whole rather than built for one run: the callbacks of
   * every {@link ToolCallbackProvider} bean in the context.
   *
   * <p>Spring AI's MCP client auto-configuration is the one every consumer gets without writing a
   * line of code — servers listed under {@code spring.ai.mcp.client.*} become such a bean — and
   * nothing else would ever assemble it, because a run is handed exactly the tools composed here
   * and Spring AI does not fold provider beans into a {@code ChatClient} on its own.
   *
   * <p>Whatever sits behind these callbacks belongs to the context and outlives the run, so they
   * are deliberately kept out of {@link McpTools} and are not closed when the run ends. A provider
   * is also expected to namespace what it offers: Spring AI refuses a request carrying two tools of
   * the same name, so a clash with a per-request tool would cost every run rather than one call.
   * The MCP provider does this by prefixing each tool with its connection name.
   */
  private List<ToolCallback> globalToolCallbacks() {
    final var callbacks = new ArrayList<ToolCallback>();
    applicationContext
        .getBeanProvider(ToolCallbackProvider.class)
        .forEach(
            provider -> {
              try {
                // Listing tools is a round trip to a remote server for the MCP provider, and a
                // server that is down or slow costs the run those tools, never the run itself —
                // the same bargain the per-request MCP path strikes above.
                Collections.addAll(callbacks, provider.getToolCallbacks());
              } catch (Exception e) {
                log.warn(
                    "Skipping tools from {}: {}",
                    provider.getClass().getSimpleName(),
                    e.getMessage());
              }
            });
    return callbacks;
  }

  /**
   * The {@code @AgentTool} beans a run in {@code scenario} for {@code userId} is offered, in
   * registration order.
   *
   * <p>Two rulings. {@link AgentScenario#offers} decides each tool for this kind of run; {@code
   * app.ai.admins} decides the ones declared {@link AgentTool#admin()}, on the run's user id alone.
   * See that attribute for why the user id is the whole of the test, and why the identity a run
   * assumes is therefore the boundary worth guarding.
   *
   * <p>Resolved by bean name rather than through {@code getBeansWithAnnotation}, because the
   * annotation is honoured on a {@code @Bean} factory method as well as on a class — {@code
   * PlaybookTools} is registered that way — and reading the attribute off {@code bean.getClass()}
   * would silently see the default on every one of those, which for {@code admin} means offering an
   * admin tool to everybody. {@code findAnnotationOnBean} looks at whichever of the two carries it.
   *
   * <p>That it is the same lookup discovery uses is what makes the failure mode safe rather than
   * dangerous. {@code getBeansWithAnnotation} is itself {@code getBeanNamesForAnnotation} followed
   * by {@code getBean}, and the names come from this very call returning non-null — so anywhere the
   * annotation cannot be resolved, native image included, the bean is not found to be a tool at all
   * and is offered to nobody. There is no state in which a tool is discovered but its {@code admin}
   * attribute reads as the default.
   */
  public List<Object> resolveScenarioTools(final AgentScenario scenario, final String userId) {
    final var admin = admins.isAdmin(userId);
    final var tools = new ArrayList<>();
    for (final var name : applicationContext.getBeanNamesForAnnotation(AgentTool.class)) {
      final var annotation = applicationContext.findAnnotationOnBean(name, AgentTool.class);
      if (annotation != null && annotation.admin() && !admin) {
        continue;
      }
      final var tool = applicationContext.getBean(name);
      if (scenario.offers(tool)) {
        tools.add(tool);
      }
    }
    return List.copyOf(tools);
  }

  /**
   * What {@code scenario} allows, for a caller with no particular person in mind — listing the
   * tools a deployment has, rather than composing a run. Never includes an admin tool, since nobody
   * has been named who could hold one.
   */
  public List<Object> resolveScenarioTools(final AgentScenario scenario) {
    return resolveScenarioTools(scenario, null);
  }

  public AgentTools build(String userId, String chatId, Map<String, Object> toolContext)
      throws IOException {
    // The sandbox and the skills index span every scope the request reaches, or a group's shared
    // skill would be listed by SkillManagementTools and then refused by the tools meant to run it.
    final var context = new ToolContext(toolContext == null ? Map.of() : toolContext);
    final var home =
        userWorkspaceFactory.forRequest(
            userId,
            ToolContexts.get(context, ToolContexts.GROUP_ID),
            ToolContexts.get(context, ToolContexts.TENANT_ID));

    final var fileSystemTools =
        FileSystemTools.builder().allowedDirectories(home.roots().toArray(Path[]::new)).build();

    // Only the directories that are actually there: an empty skills directory holds no SKILL.md
    // and so contributes nothing to the index, and the one a new skill is written to is created
    // by the write itself.
    final var skillsDirs = home.dirs(HomeDir.Folder.SKILLS).stream().map(Path::toString).toList();
    final var skillsToolBuilder = SkillsTool.builder();
    skillsToolBuilder.addSkillsDirectories(skillsDirs);
    // This one tool cannot be translated the way every other one is. Its description is composed
    // rather than declared — the library formats the list of installed skills into it — so
    // replacing
    // the finished description would silently drop that list and leave the model told to use only
    // skills it can no longer see. The template is the seam, and the %s in it is load-bearing.
    //
    // Nothing ships an English copy: absent a translation the library's own template stands, which
    // is
    // why this reads the optional form rather than the one that throws.
    LocalizedPrompt.findText(SKILL_TOOL_PROMPT, appConfiguration.locale())
        .ifPresent(skillsToolBuilder::toolDescriptionTemplate);
    Optional<ToolCallback> skillsTool;
    try {
      skillsTool = Optional.of(skillsToolBuilder.build());
    } catch (IllegalArgumentException e) {
      log.debug("No skills configured for directories: {}", skillsDirs);
      skillsTool = Optional.empty();
    }

    final var mcpTools = buildMcpTools(userId, chatId, toolContext);

    return new AgentTools(fileSystemTools, skillsTool, mcpTools);
  }

  /**
   * Connects to every MCP server the user can reach, at once rather than in turn.
   *
   * <p>Each one costs a connection, an initialize handshake and a listTools round trip, and each
   * may take the full request timeout to fail. In turn, that is the sum of every server's latency
   * added to the front of every request, before the model has been asked anything — and it grows
   * with each server registered. Concurrently it is the slowest one.
   *
   * <p>Virtual threads, not {@code parallelStream}: this is blocking I/O with a timeout measured in
   * tens of seconds, which is not what the common ForkJoinPool is for.
   */
  private McpTools buildMcpTools(
      final String userId, final String chatId, final Map<String, Object> toolContext) {
    final var identifiers = McpServerConfig.accessIdentifiers(userId, chatId);
    final var configs =
        mcpServerConfigRepo.findAccessibleTo(userId, identifiers).stream()
            .filter(McpServerConfig::enabled)
            .toList();
    rejectDuplicateToolPrefixes(configs);
    final var clients = new ArrayList<McpSyncClient>(configs.size());
    if (!configs.isEmpty()) {
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        final var pending =
            configs.stream()
                .map(
                    config ->
                        Map.entry(
                            config,
                            CompletableFuture.supplyAsync(
                                () -> mcpClientFactory.createAndInitialize(config, toolContext),
                                executor)))
                .toList();
        for (final var entry : pending) {
          try {
            // join rather than get: every task has to be collected, because one already past its
            // handshake holds an open connection that nothing else will ever close, and join is the
            // one that keeps waiting through an interrupt instead of abandoning it.
            clients.add(entry.getValue().join());
          } catch (CompletionException | CancellationException e) {
            // A server that is down, misconfigured or slow costs the run its tools, never the run
            // itself.
            final var cause = e.getCause() != null ? e.getCause() : e;
            log.warn(
                "Skipping MCP server '{}' for user {}: {}",
                entry.getKey().name(),
                userId,
                cause.getMessage());
          }
        }
      }
    }
    if (clients.isEmpty()) {
      return new McpTools(clients, new ToolCallback[0]);
    }
    try {
      final var callbacks =
          SyncMcpToolCallbackProvider.builder()
              .mcpClients(clients)
              .toolNamePrefixGenerator(new ServerNameToolPrefixGenerator())
              .build()
              .getToolCallbacks();
      return new McpTools(clients, callbacks);
    } catch (RuntimeException e) {
      // Every client is connected by now and the caller learns of them only through the McpTools
      // this returns, so anything thrown here has to close them itself. Listing tools is where
      // Spring AI validates the assembled names, and it throws on a duplicate — which is exactly
      // the case rejectDuplicateToolPrefixes above cannot see, two servers with different prefixes
      // whose tool names collide anyway.
      new McpTools(clients, new ToolCallback[0]).close();
      throw e;
    }
  }

  /**
   * Refuses a run where two of the servers it would reach name their tools the same way.
   *
   * <p>A prefix is unique by construction while it is derived from the server name, so this only
   * fires on a chosen {@code toolPrefix}. Registration checks the same thing, but only against what
   * the *owner* could reach at the time: a server shared with somebody afterwards, or an
   * application-wide one added later, is a collision nobody was there to catch.
   *
   * <p>Before the fan-out rather than after, so a run that cannot be composed does not pay for the
   * handshakes first. And a refusal rather than dropping one of the two, because a dropped server
   * means a tool call meant for staging answered by production — a wrong answer is worse than an
   * error the user can act on. Spring AI refuses the request over duplicate names anyway; all this
   * decides is whether the user is told what to change.
   */
  private static void rejectDuplicateToolPrefixes(final List<McpServerConfig> configs) {
    final var byPrefix = new HashMap<String, String>(configs.size());
    for (final var config : configs) {
      final String prefix;
      try {
        prefix = McpClientFactory.toolPrefix(config);
      } catch (IllegalArgumentException e) {
        // A stored prefix that cannot be honoured costs that one server its tools, in
        // createAndInitialize below, the same as a server that is unreachable. It is not a reason
        // to refuse every other server's.
        continue;
      }
      final var owner = byPrefix.putIfAbsent(prefix, config.name());
      if (owner != null) {
        throw new IllegalStateException(
            "MCP servers '"
                + owner
                + "' and '"
                + config.name()
                + "' both name their tools '"
                + prefix
                + "_*'. Give one of them a different tool prefix (AddMcpServer's toolPrefix).");
      }
    }
  }
}
