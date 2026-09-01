package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which of the models an endpoint lists are offered as something to chat with.
 *
 * <p>The names here are real ones from gateways this runs against, because that is the only thing
 * the filter can be judged against — see {@code BuiltinModels#chatModelsAmong}.
 */
class BuiltinModelsTest {

  @Test
  @DisplayName("what cannot answer a chat completion is left out")
  void dropsNonChatModels() {
    final var offered =
        List.of(
            "gpt-5",
            "text-embedding-3-large",
            "Qwen/Qwen3-Embedding-8B",
            "bge-m3",
            "BAAI/bge-reranker-v2-m3",
            "whisper-1",
            "gpt-4o-transcribe",
            "cosyvoice-v2-tts",
            "dall-e-3",
            "gpt-image-1",
            "omni-moderation-latest",
            "gpt-4o-realtime-preview",
            "stable-diffusion-3.5-large");

    assertThat(BuiltinModels.chatModelsAmong(offered)).containsExactly("gpt-5");
  }

  @Test
  @DisplayName("a chat model is kept however it is named")
  void keepsChatModels() {
    // vision, audio and ocr are chat models on several gateways, so no stem may match them.
    final var offered =
        List.of(
            "claude-opus-4-5",
            "deepseek-v3",
            "gpt-4o-audio-preview",
            "gpt-5-mini",
            "qwen-vl-ocr",
            "step-1o-turbo-vision");

    assertThat(BuiltinModels.chatModelsAmong(offered)).isEqualTo(offered);
  }

  @Test
  @DisplayName("a listing the filter would empty is offered whole")
  void neverEmpties() {
    // Nothing here is recognisable, so the guess has understood nothing about this gateway and an
    // unfiltered menu beats an empty one.
    final var offered = List.of("internal-embed-1", "internal-embed-2");

    assertThat(BuiltinModels.chatModelsAmong(offered)).isEqualTo(offered);
  }
}
