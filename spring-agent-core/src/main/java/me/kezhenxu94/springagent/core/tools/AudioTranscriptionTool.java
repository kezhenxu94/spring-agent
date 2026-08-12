package me.kezhenxu94.springagent.core.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class AudioTranscriptionTool {
  final OpenAiAudioTranscriptionModel openAiAudioTranscriptionModel;

  @Tool(name = "TranscribeAudio", description = "Transcribe a local audio file to text")
  public String transcribeAudio(
      @ToolParam(description = "Absolute path to the local audio file to transcribe")
          final String filePath) {
    log.info("Transcribing audio file: {}", filePath);
    final var transcription =
        openAiAudioTranscriptionModel.call(
            new AudioTranscriptionPrompt(new FileSystemResource(filePath)));
    final var transcribedText = transcription.getResult().getOutput();
    log.info("Transcribed audio file: {}, result length={}", filePath, transcribedText.length());
    return transcribedText;
  }
}
