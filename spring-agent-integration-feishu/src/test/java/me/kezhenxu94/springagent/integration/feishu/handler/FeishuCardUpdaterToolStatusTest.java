package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import java.nio.file.Path;
import java.util.Locale;
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
 * The line a card shows while a tool runs is all the reader has to go on, so it says what the call
 * does whenever the call itself told us — and falls back to the tool's name when it did not.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterToolStatusTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private FeishuCardUpdater updater;

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    updater =
        FeishuCardUpdater.forRun(
            new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages),
            new JsonMapper(),
            null,
            messages,
            cardElements(messages),
            null);
  }

  private String lastContentSent() throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    return captor.getValue().getContentCardElementReqBody().getContent();
  }

  @Test
  @DisplayName("the line comes off the card when the call comes back, before the model writes")
  void theLineComesOffWhenTheCallReturns() throws Exception {
    updater.onContent("Let me look.");
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);
    assertThat(lastContentSent()).isEqualTo("Let me look.\nListing files");

    updater.clearToolStatus();

    // What the run had said, and nothing about a call that is over. Nothing else would take it
    // down until the model wrote its next word, which on a thinking model is a long wait.
    assertThat(lastContentSent()).isEqualTo("Let me look.");
  }

  @Test
  @DisplayName("a round calling several tools keeps its line until the last of them returns")
  void theLineStaysWhileAnotherCallIsStillOut() throws Exception {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);
    updater.setToolStatus("Bash", "{\"description\":\"Reading the log\"}", null);

    updater.clearToolStatus();

    // The first call back does not speak for the second: the run is still waiting on something.
    assertThat(lastContentSent()).isEqualTo("\nReading the log");

    updater.clearToolStatus();

    assertThat(lastContentSent()).isEmpty();
  }

  @Test
  @DisplayName("a description in the input becomes the line, and is not repeated in the fields")
  void descriptionLeadsTheLine() throws Exception {
    updater.setToolStatus(
        "Bash",
        "{\"command\":\"ls -la\",\"description\":\"List files in the current directory\"}",
        null);

    final var content = lastContentSent();
    assertThat(content).isEqualTo("\nList files in the current directory\n> command: ls -la");
    assertThat(content).doesNotContain("Calling");
    assertThat(content).doesNotContain("description:");
  }

  @Test
  @DisplayName("a description spanning lines is folded onto the one line the card gives it")
  void descriptionIsFoldedOntoOneLine() throws Exception {
    updater.setToolStatus(
        "Bash",
        "{\"command\":\"ls\",\"description\":\"  List files\\n   in the directory  \"}",
        null);

    assertThat(lastContentSent()).isEqualTo("\nList files in the directory\n> command: ls");
  }

  @Test
  @DisplayName("a description that is the whole input leaves no empty quote line behind")
  void descriptionAloneLeavesNoQuotedBlock() throws Exception {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);

    assertThat(lastContentSent()).isEqualTo("\nListing files");
  }

  @Test
  @DisplayName("without a description the tool's name still announces the call, with every field")
  void withoutDescriptionTheToolNameIsUsed() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls -la\",\"timeout\":1000}", null);

    assertThat(lastContentSent())
        .isEqualTo("\nCalling Bash ...\n> command: ls -la\n> timeout: 1000");
  }

  @Test
  @DisplayName("a blank or non-textual description is no description at all")
  void unusableDescriptionFallsBack() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":\"   \"}", null);
    assertThat(lastContentSent()).startsWith("\nCalling Bash ...\n> command: ls");

    updater.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":42}", null);
    assertThat(lastContentSent()).isEqualTo("\nCalling Bash ...\n> command: ls\n> description: 42");
  }

  @Test
  @DisplayName("input that is not a JSON object is quoted as it came")
  void nonObjectInputIsQuotedVerbatim() throws Exception {
    updater.setToolStatus("Bash", "not json at all", null);
    assertThat(lastContentSent()).isEqualTo("\nCalling Bash ...\n> not json at all");

    updater.setToolStatus("Bash", "\"a bare string\"", null);
    assertThat(lastContentSent()).isEqualTo("\nCalling Bash ...\n> \"a bare string\"");
  }

  @Test
  @DisplayName("a field holding an array is shown as JSON, not dropped along with the rest")
  void containerFieldsSurvive() throws Exception {
    updater.setToolStatus("TodoWrite", "{\"todos\":[{\"content\":\"do it\"}]}", null);

    assertThat(lastContentSent())
        .isEqualTo("\nCalling TodoWrite ...\n> todos: [{\"content\":\"do it\"}]");
  }

  @Test
  @DisplayName("an empty input adds nothing under the line")
  void emptyInputAddsNothing() throws Exception {
    updater.setToolStatus("DateTime", "", null);

    assertThat(lastContentSent()).isEqualTo("\nCalling DateTime ...");
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"));
  }
}
