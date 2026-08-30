package me.kezhenxu94.springagent.appcli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CliSessionTest {

  @Test
  void clearStartsANewConversation() {
    final var session = new CliSession();
    final var first = session.conversationId();

    final var second = session.clear();

    // The conversation id is the chat-memory key, so this is the whole of what /clear does: the
    // next turn replays nothing from the last one.
    assertThat(second).isNotEqualTo(first);
    assertThat(session.conversationId()).isEqualTo(second);
  }

  @Test
  void tracksTheRunInFlight() {
    final var session = new CliSession();
    assertThat(session.activeRunId()).isNull();

    session.runStarted("run-1");
    assertThat(session.activeRunId()).isEqualTo("run-1");

    session.runEnded();
    // Cleared rather than left behind: Ctrl-C at an idle prompt would otherwise cancel a run that
    // has already finished, and /stop would claim it stopped something.
    assertThat(session.activeRunId()).isNull();
  }

  @Test
  void quittingIsWhatEndsTheLoop() {
    final var session = new CliSession();
    assertThat(session.quitting()).isFalse();

    session.quit();

    assertThat(session.quitting()).isTrue();
  }
}
