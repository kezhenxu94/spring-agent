package me.kezhenxu94.springagent.events.tools;

import com.google.common.base.Strings;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeSource;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.situation.PlaybookFilters;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Reading and writing the playbooks a triage run is steered by, from a chat.
 *
 * <p>A playbook is an ordinary knowledge base document that happens to be the one {@code
 * SituationSweeper} retrieves before it triages a source's events — see {@code
 * EventsProperties.Playbook}. Which document that is, is settled by three things a deployment
 * configures and nothing else: the source's {@code owner}, whose knowledge base it is read from —
 * the user's, and its group's and tenant's where the deployment configured them; {@code
 * playbook.query}, a fixed question that never contains the event's own text; and {@code
 * playbook.filter}, which names the document ids that count.
 *
 * <p>Before this, writing one meant being logged in as the source's owner, because {@code
 * IndexKnowledge} writes into the base of whoever is running — and an owner is meant to be an
 * identity of the agent's own rather than a person, so nobody was. The deployment's runbooks were
 * therefore configured but unwritable, which is how a source ends up with a playbook query and no
 * playbook behind it.
 *
 * <p><b>Declared {@code @AgentTool(admin = true)}, and that is the whole of the safety story.</b>
 * These write into another identity's knowledge base and steer every future unattended run about a
 * source, so who may call them matters more than what they check. Only somebody named in {@code
 * app.ai.admins} is offered them at all.
 *
 * <p>Which puts the weight on the identity a run assumes rather than on anything checked here. A
 * situation triage assumes the source's {@code owner.user-id} and reads text whoever caused the
 * event wrote, so were that identity an administrator these tools would be in a stranger's reach —
 * and the playbook they wrote would steer every triage after it. {@code SituationSweeper} refuses
 * to start on that pairing; nothing in this class could detect it.
 *
 * <p>The document id check below is a different kind of guard, and a weaker one on purpose: it
 * catches an administrator writing a playbook that will silently never be retrieved, which is a
 * mistake rather than an attack.
 */
@Slf4j
@RequiredArgsConstructor
public class PlaybookTools {

  private final KnowledgeBase knowledgeBase;
  private final EventsProperties properties;
  private final PlaybookFilters playbookFilters;
  private final EventsMessages messages;

  @Tool(
      name = "ListPlaybooks",
      description =
          """
          List the event sources this deployment watches and what each one's playbook is: which \
          knowledge bases it is read from, the fixed question it is retrieved with, the filter that \
          decides which documents count, and the document ids that filter accepts. A source whose \
          owner has a group or a tenant reads from those bases too, and anybody who can write into \
          one of them can therefore file a playbook. Administrators only. Call this before WritePlaybook, because a playbook written under \
          any other document id is stored successfully and then never read. To read back what a \
          source's playbook currently says, pass the owner it names to ListOwnerKnowledgeBase or \
          SearchOwnerKnowledge — nobody logs in as that identity, so those are the only way to see \
          its knowledge base.
          """)
  public String listPlaybooks() {
    final var sources = new TreeSet<>(properties.sources().keySet());
    if (sources.isEmpty()) {
      return messages.get("playbook-no-sources");
    }
    final var result = new StringBuilder();
    for (final var source : sources) {
      final var policy = properties.policyFor(source).orElse(null);
      if (policy == null) {
        result.append(messages.get("playbook-source-off", source)).append("\n");
        continue;
      }
      result
          .append(
              messages.get(
                  "playbook-source",
                  source,
                  describeBases(policy),
                  Strings.nullToEmpty(policy.playbook().query()),
                  Strings.nullToEmpty(policy.playbook().filter()),
                  describeAcceptedIds(source)))
          .append("\n");
    }
    return result.toString().strip();
  }

  @Tool(
      name = "WritePlaybook",
      description =
          """
          Write, or rewrite, the playbook a source's triage runs are steered by. Administrators \
          only.

          The text is what an unattended run will be shown about this source before it decides \
          anything, so write it as instructions to the agent: what these events usually mean, what \
          to look at, what to do, who to tell, and what never to do without asking. It is read by \
          a run with nobody watching, so say what it may not decide on its own.

          docId must be one of the ids ListPlaybooks says the source's filter accepts. Writing \
          again under the same id replaces that playbook; a different one stores a second document \
          the runs will read alongside the first.

          This writes into the owner's own knowledge base, which is the first base ListPlaybooks \
          lists. To put a playbook somewhere a team can edit it, file it into that group's or \
          tenant's base with the ordinary knowledge tools, under an id the filter accepts.
          """)
  public String writePlaybook(
      @ToolParam(description = "The event source, as ListPlaybooks names it") String source,
      @ToolParam(description = "The document id the source's filter accepts") String docId,
      @ToolParam(description = "A short descriptive title for the playbook") String title,
      @ToolParam(
              description =
                  "The playbook itself, as instructions to the agent. Takes a file reference:"
                      + " @file:<path> for a saved tool result, or @file:<path>#/json/pointer for"
                      + " one part of it")
          String text,
      final ToolContext context) {

    if (Strings.isNullOrEmpty(source)) {
      return messages.get("playbook-source-required");
    }
    if (Strings.isNullOrEmpty(docId)) {
      return messages.get("playbook-doc-id-required");
    }
    if (Strings.isNullOrEmpty(title) || Strings.isNullOrEmpty(text)) {
      return messages.get("playbook-content-required");
    }

    final var policy = properties.policyFor(source).orElse(null);
    if (policy == null) {
      return messages.get("playbook-source-unknown", source);
    }
    if (Strings.isNullOrEmpty(policy.owner().userId())) {
      // The owner is the whole address of the knowledge base being written to. Without one there is
      // nowhere to put this, and guessing an owner would write a document into somebody's base that
      // nothing will ever read.
      return messages.get("playbook-no-owner", source);
    }
    if (!policy.playbook().hasQuery()) {
      // No query means the sweeper retrieves nothing for this source whatever is stored, so a write
      // here would be accepted and then ignored for ever. See SituationSweeper#playbookFor.
      return messages.get("playbook-no-query", source);
    }

    final var accepted = playbookFilters.docIdsFor(source);
    if (!accepted.isEmpty() && !accepted.contains(docId)) {
      return messages.get("playbook-doc-id-rejected", source, docId, String.join(", ", accepted));
    }

    try {
      final var storedId =
          knowledgeBase.index(
              KnowledgeSource.ofText(
                  // The owner alone, and OWN, so this lands in the identity's own knowledge base.
                  // Narrower than what SituationSweeper#playbookFor reads, which is the owner's
                  // group and tenant as well: a read may span the three, a write picks one, and the
                  // private base is the one whose contents only something running as the owner can
                  // have put there. An administrator who wants a playbook the team can edit files
                  // it
                  // with the ordinary knowledge tools under a doc id the source's filter accepts.
                  new KnowledgeScope(policy.owner().userId(), "", ""),
                  KnowledgeScope.Target.OWN,
                  title,
                  text,
                  origin(context),
                  docId));
      log.info(
          "Playbook {} for source {} written into {}'s knowledge base",
          storedId,
          source,
          policy.owner().userId());
      return accepted.isEmpty()
          ? messages.get(
              "playbook-written-unverified", storedId, source, policy.owner().userId(), source)
          : messages.get("playbook-written", storedId, source, policy.owner().userId());
    } catch (RuntimeException e) {
      return messages.get("playbook-write-failed", e.getMessage());
    }
  }

  /**
   * The knowledge bases a source's playbook is read from, one line each.
   *
   * <p>Spelt out rather than reduced to the owner's user id because they are no longer one base and
   * they are not equally safe to file a playbook in: {@code SituationSweeper#playbookFor} reads the
   * owner's own, its group's and its tenant's as alternatives, and only the first is one that
   * nothing but the agent's own identity can write into. An administrator deciding where a runbook
   * goes needs to be told that here, in the answer they are already reading, rather than by finding
   * out later that a colleague's note became a playbook.
   */
  private String describeBases(final EventsProperties.Policy policy) {
    final var owner = policy.owner();
    final var bases = new StringBuilder();
    if (!Strings.isNullOrEmpty(owner.userId())) {
      bases.append("\n    ").append(messages.get("playbook-base-user", owner.userId()));
    }
    if (!Strings.isNullOrEmpty(owner.groupId())) {
      bases.append("\n    ").append(messages.get("playbook-base-group", owner.groupId()));
    }
    if (!Strings.isNullOrEmpty(owner.tenantId())) {
      bases.append("\n    ").append(messages.get("playbook-base-tenant", owner.tenantId()));
    }
    return bases.toString();
  }

  /**
   * What to say about the ids a source's filter accepts, which is three different answers: the ids
   * themselves, that the filter accepts anything the owner owns, or that it was not a filter this
   * can read the ids out of.
   *
   * <p>The three are kept apart because only the first lets {@code WritePlaybook} promise the
   * document will be retrieved. The other two are written where a run can see them, so an
   * administrator finds out here rather than by writing a playbook that never takes effect.
   */
  private String describeAcceptedIds(final String source) {
    final Set<String> ids = playbookFilters.docIdsFor(source);
    if (!ids.isEmpty()) {
      return String.join(", ", ids);
    }
    return playbookFilters.forSource(source) == null
        ? messages.get("playbook-ids-any")
        : messages.get("playbook-ids-unreadable");
  }

  /**
   * Where the playbook came from, for a reader who later wants to know who wrote this and why.
   *
   * <p>The message the administrator asked in, which is the same attribution {@code
   * KnowledgeBaseTools} falls back to for something a user simply said. A playbook has no file and
   * no URL behind it — it was written in a conversation — so the conversation is the origin.
   */
  private String origin(final ToolContext context) {
    final var messageId = ToolContexts.get(context, ToolContexts.REPLY_MESSAGE_ID);
    return Strings.isNullOrEmpty(messageId) ? messages.get("playbook-origin-unknown") : messageId;
  }
}
