package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeReference;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The References footer: which documents the run was handed before it answered.
 *
 * <p>Its own element in the footer rather than a note under the answer, for the same reason the
 * spend line is: the answer's element is rewritten whole on every streaming tick, so anything
 * written there would be gone by the next one.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterReferencesTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private FeishuMessages messages;
  private FeishuCard card;
  private JsonMapper om;

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    om = new JsonMapper();
  }

  private ContentCardElementReq lastWrite() throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    return captor.getValue();
  }

  private FeishuCardUpdater updater() {
    return FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);
  }

  private static KnowledgeReference reference(
      final String docId,
      final String title,
      final String source,
      final KnowledgeScope.Target scope) {
    return new KnowledgeReference(docId, title, source, scope, 0.8d);
  }

  @Test
  @DisplayName(
      "what the run was given is named in the footer, with its scope and where it came from")
  void namesEachDocument() throws Exception {
    updater()
        .onKnowledgeRetrieved(
            List.of(
                reference("d-1", "Release runbook", "/w/runbook.md", KnowledgeScope.Target.OWN)));

    // Written into the panel's body, not the panel: the title lives in the header the insert
    // carried, so only the sources themselves are streamed.
    assertThat(lastWrite().getElementId()).isEqualTo("references_body");
    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content).contains("Release runbook").contains("yours").contains("/w/runbook.md");
  }

  @Test
  @DisplayName("colour is opened and closed on every line, so no tag is left dangling as text")
  void colourDoesNotSpanLines() throws Exception {
    // A font tag does not survive the line breaks between its ends: wrapped around the block, the
    // trailing one had nothing to close and showed up on the card as the literal text </font>.
    updater()
        .onKnowledgeRetrieved(
            List.of(
                reference("d-1", "First", "first.md", KnowledgeScope.Target.OWN),
                reference("d-2", "Second", "second.md", KnowledgeScope.Target.GROUP)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content.split("<font color='grey'>", -1)).as("one opening tag per line").hasSize(3);
    assertThat(content.split("</font>", -1)).as("and one closing tag each").hasSize(3);
    assertThat(content.lines())
        .allSatisfy(
            line ->
                assertThat(line)
                    .as("every line closes what it opened")
                    .satisfies(
                        l -> {
                          assertThat(l.contains("<font")).isEqualTo(l.contains("</font>"));
                        }));
  }

  @Test
  @DisplayName("which knowledge base each document came from is said, not just its name")
  void namesTheScope() throws Exception {
    updater()
        .onKnowledgeRetrieved(
            List.of(
                reference("d-1", "Team norms", "norms.md", KnowledgeScope.Target.GROUP),
                reference("d-2", "Expenses", "expenses.md", KnowledgeScope.Target.TENANT)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content).contains("this group").contains("company-wide");
  }

  @Test
  @DisplayName("a document retrieved again on a later tool round is listed once, not twice")
  void deduplicatesAcrossRounds() throws Exception {
    // Retrieval sits inside the tool-calling loop, so a turn making several tool calls reports the
    // same passages once per round. Listing them per report would grow the footer on every call.
    final var updater = updater();
    final var same = reference("d-1", "Release runbook", "runbook.md", KnowledgeScope.Target.OWN);

    updater.onKnowledgeRetrieved(List.of(same));
    updater.onKnowledgeRetrieved(List.of(same));
    updater.onKnowledgeRetrieved(List.of(same));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content.split("Release runbook", -1)).hasSize(2);
  }

  @Test
  @DisplayName("a document found on a later round joins the ones already listed")
  void accumulatesAcrossRounds() throws Exception {
    final var updater = updater();

    updater.onKnowledgeRetrieved(
        List.of(reference("d-1", "First", "first.md", KnowledgeScope.Target.OWN)));
    updater.onKnowledgeRetrieved(
        List.of(reference("d-2", "Second", "second.md", KnowledgeScope.Target.OWN)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content).contains("First").contains("Second");
  }

  @Test
  @DisplayName("a turn that retrieved nothing puts no element on the card at all")
  void nothingRetrievedAddsNothing() throws Exception {
    updater().onKnowledgeRetrieved(List.of());

    verify(feishu.cardkit().v1().cardElement(), org.mockito.Mockito.never())
        .create(any(CreateCardElementReq.class));
  }

  @Test
  @DisplayName("a source that only repeats the title is left off rather than padding the line")
  void doesNotRepeatTheTitleAsSource() throws Exception {
    updater()
        .onKnowledgeRetrieved(
            List.of(reference("d-1", "Staging URL", "Staging URL", KnowledgeScope.Target.OWN)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content.split("Staging URL", -1)).hasSize(2);
  }

  @Test
  @DisplayName("a document fetched from a URL is shown as a link a reader can open")
  void urlSourceBecomesALink() throws Exception {
    updater()
        .onKnowledgeRetrieved(
            List.of(
                reference(
                    "d-1",
                    "Idol v1.5.5",
                    "https://wiki.example.com/idol-155",
                    KnowledgeScope.Target.OWN)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    // The title carries the link, rather than the address being printed beside it: a wiki URL is
    // long and the only useful thing about it is that it can be clicked.
    assertThat(content).contains("[Idol v1.5.5](https://wiki.example.com/idol-155)");
  }

  @Test
  @DisplayName("a file path is shown as text, since a reader cannot open it from their phone")
  void filePathIsNotLinked() throws Exception {
    updater()
        .onKnowledgeRetrieved(
            List.of(reference("d-1", "Runbook", "/w/runbook.md", KnowledgeScope.Target.OWN)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content).contains("/w/runbook.md").doesNotContain("](");
  }

  @Test
  @DisplayName("a note that came from the conversation shows its title alone")
  void noSourceShowsTitleOnly() throws Exception {
    updater()
        .onKnowledgeRetrieved(
            List.of(reference("d-1", "Staging URL", "", KnowledgeScope.Target.OWN)));

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content).contains("Staging URL").doesNotContain("](");
    assertThat(content.split("·", -1)).as("title and scope only").hasSize(2);
  }

  @Test
  @DisplayName("the title says how many sources there are, once the run has finished")
  void titleCarriesTheCount() throws Exception {
    final var updater = updater();
    updater.onKnowledgeRetrieved(
        List.of(
            reference("d-1", "First", "first.md", KnowledgeScope.Target.OWN),
            reference("d-2", "Second", "second.md", KnowledgeScope.Target.GROUP),
            reference("d-3", "Third", "third.md", KnowledgeScope.Target.TENANT)));

    updater.onFinished(AgentOutcome.COMPLETED);

    final var panel = lastReplacement();
    assertThat(panel).contains("(3)");
  }

  @Test
  @DisplayName("the count goes inside the title's colour, not after it")
  void countIsInsideTheWrapper() throws Exception {
    // Appended naively it would land after the closing tag, leaving a grey title followed by a
    // black count.
    final var updater = updater();
    updater.onKnowledgeRetrieved(
        List.of(reference("d-1", "Only one", "one.md", KnowledgeScope.Target.OWN)));

    updater.onFinished(AgentOutcome.COMPLETED);

    assertThat(lastReplacement()).contains("(1)</font>").doesNotContain("</font>(1)");
  }

  @Test
  @DisplayName("a run that retrieved nothing gets no panel and so no count")
  void noCountWithoutSources() throws Exception {
    updater().onFinished(AgentOutcome.COMPLETED);

    verify(feishu.cardkit().v1().cardElement(), org.mockito.Mockito.never())
        .create(any(CreateCardElementReq.class));
  }

  /** The whole-element rewrite the run makes as it ends, as opposed to the streamed body. */
  private String lastReplacement() throws Exception {
    final var captor = ArgumentCaptor.forClass(UpdateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).update(captor.capture());
    return captor.getAllValues().stream()
        .filter(req -> "references".equals(req.getElementId()))
        .map(req -> req.getUpdateCardElementReqBody().getElement())
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
