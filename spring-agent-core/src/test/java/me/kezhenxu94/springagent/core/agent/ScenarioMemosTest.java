package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScenarioMemosTest {

  private final ScenarioMemos memos = new ScenarioMemos(List.of());

  private ScenarioMemos.Chosen parse(final String text) {
    return memos.parse(text, BuiltInScenarios.CHAT);
  }

  @Test
  void aMemoChoosesTheScenarioAndLeavesTheQuestion() {
    final var chosen = parse("/kb Tell me what MicroSoft is");
    assertThat(chosen.scenario()).isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
    assertThat(chosen.text()).isEqualTo("Tell me what MicroSoft is");
  }

  @Test
  @DisplayName("every spelling and every casing, because nobody consults a manual to type at a bot")
  void spellingsAndCasing() {
    for (final var memo :
        List.of("/kb", "/KB", "/Kb", "/knowledge-base", "/KNOWLEDGE-BASE", "/knowledge_base")) {
      assertThat(parse(memo + " what do we know").scenario())
          .as(memo)
          .isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
    }
  }

  @Test
  @DisplayName("a memo is found after a mention, which is the only way one arrives in a group chat")
  void aMemoIsFoundAnywhereInTheMessage() {
    // Feishu and Slack both require the bot to be mentioned, so what arrives is never the memo
    // first. A rule about the first characters would mean memos never worked in a group, which is
    // where most of those conversations happen.
    final var chosen = parse("@_user_1 /kb what is this");
    assertThat(chosen.scenario()).isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
    assertThat(chosen.text()).isEqualTo("@_user_1 what is this");
  }

  @Test
  void aMemoAtTheEndLeavesNoTrailingSpace() {
    final var chosen = parse("what do we do when a deployment failed? /kb");
    assertThat(chosen.scenario()).isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
    assertThat(chosen.text()).isEqualTo("what do we do when a deployment failed?");
  }

  @Test
  void aMemoInTheMiddleLeavesNoDoubleSpace() {
    assertThat(parse("hey /kb what is this").text()).isEqualTo("hey what is this");
  }

  @Test
  @DisplayName("a memo typed mid-sentence takes its own punctuation with it")
  void punctuationAfterAMemo() {
    // A comma after an interjected word belongs to the word, so it goes; a question mark or a full
    // stop belongs to the sentence, so it stays where it is and closes what is left.
    assertThat(parse("what do we do when a deployment failed? /kb, tell me something").text())
        .isEqualTo("what do we do when a deployment failed? tell me something");
    assertThat(parse("what is this /kb?").text()).isEqualTo("what is this?");
    assertThat(parse("what is this /kb.").text()).isEqualTo("what is this.");
    assertThat(parse("/kb; and be brief").text()).isEqualTo("and be brief");
    for (final var text :
        List.of(
            "what do we do when a deployment failed? /kb, tell me something",
            "what is this /kb?",
            "/kb; and be brief")) {
      assertThat(parse(text).scenario()).as(text).isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
    }
  }

  @Test
  @DisplayName("a path or a URL is not a memo, which is what whole-token matching is for")
  void aPathIsNotAMemo() {
    // The one thing narrowing an anywhere-match back down. Without it every message quoting a
    // wiki link would silently answer from the knowledge base alone.
    for (final var text :
        List.of(
            "read /kb/notes/2024 for me",
            "see https://wiki.internal/kb for the rest",
            "look at /kbextra",
            "what about x/kb",
            // A dot that is not the end of the sentence makes it a filename, not a memo.
            "open /kb.md please")) {
      final var chosen = parse(text);
      assertThat(chosen.scenario()).as(text).isEqualTo(BuiltInScenarios.CHAT);
      assertThat(chosen.text()).as(text).isEqualTo(text);
    }
  }

  @Test
  @DisplayName("a slash word nobody claims reaches the model untouched")
  void anUnknownSlashWordIsLeftAlone() {
    // Answering "no such scenario" instead would swallow every message that happens to open with
    // a slash — including /config, which the chat surfaces handle before this is ever consulted.
    final var chosen = parse("/config please");
    assertThat(chosen.scenario()).isEqualTo(BuiltInScenarios.CHAT);
    assertThat(chosen.text()).isEqualTo("/config please");
  }

  @Test
  void nothingToParseIsTheFallback() {
    assertThat(parse(null).scenario()).isEqualTo(BuiltInScenarios.CHAT);
    assertThat(parse("").text()).isEmpty();
    assertThat(parse("just a question").text()).isEqualTo("just a question");
  }

  @Test
  @DisplayName("strip takes the memo out of text the scenario was already chosen from")
  void stripRemovesTheMemoFromTextAssembledLater() {
    // Feishu and Slack decide the scenario from what was typed and assemble the prompt afterwards,
    // off the event thread, so the removal cannot happen in the same breath as the decision.
    final var scenario = BuiltInScenarios.KNOWLEDGE_BASE;
    // The line break survives: what Feishu assembles is the quoted parent, a newline, then what
    // was typed, and running the two together would attach the question to the quotation.
    assertThat(memos.strip("quoting an earlier message\n/kb what is this", scenario))
        .isEqualTo("quoting an earlier message\nwhat is this");
    // A no-op where the memo is not in it, which is every message whose text came out of a file.
    assertThat(memos.strip("a transcribed audio message", scenario))
        .isEqualTo("a transcribed audio message");
    // And where the scenario claims no memo at all, so nothing is looked for.
    assertThat(memos.strip("/kb what is this", BuiltInScenarios.CHAT))
        .isEqualTo("/kb what is this");
  }

  @Test
  @DisplayName("a second scenario's memos resolve to it and not to the first")
  void memosAreScenarioSpecific() {
    // Two built-ins carry memos now, so this is the check that the registry keys them apart rather
    // than answering whichever it happened to register first.
    for (final var memo : List.of("/one-off", "/one_off", "/mini", "/MINI")) {
      assertThat(parse(memo + " summarize this").scenario())
          .as(memo)
          .isEqualTo(BuiltInScenarios.ONE_OFF);
    }
    assertThat(parse("/kb what is this").scenario()).isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
  }

  @Test
  void aWordIsResolvedWithoutItsSlash() {
    assertThat(memos.named("KB")).contains(BuiltInScenarios.KNOWLEDGE_BASE);
    assertThat(memos.named("help")).isEmpty();
    assertThat(memos.named(null)).isEmpty();
  }

  @Test
  @DisplayName("two scenarios claiming one word fail the context rather than one losing silently")
  void aClaimedMemoCannotBeClaimedTwice() {
    // At startup and fatally, because which of the two won would otherwise depend on bean
    // registration order, and the loser would be unreachable with nothing anywhere saying why.
    final var impostor =
        new AgentScenario() {
          @Override
          public Set<String> memoNames() {
            return Set.of("KB");
          }
        };
    assertThatThrownBy(() -> new ScenarioMemos(List.of(impostor)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/kb");
  }

  /** A scenario of a consumer's own, selectable by declaring it as a bean and nothing else. */
  @Test
  void aConsumersOwnScenarioIsSelectableByDeclaringIt() {
    final var mine =
        new AgentScenario() {
          @Override
          public Set<String> memoNames() {
            return Set.of("triage");
          }
        };
    final var withMine = new ScenarioMemos(List.of(mine));
    assertThat(withMine.parse("/TRIAGE look at this", BuiltInScenarios.CHAT).scenario())
        .isSameAs(mine);
    // And is not reachable through a registry that was not given it.
    assertThat(parse("/triage look at this").scenario()).isEqualTo(BuiltInScenarios.CHAT);
  }
}
