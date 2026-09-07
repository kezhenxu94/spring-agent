package me.kezhenxu94.springagent.core.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.FileSystemResource;

/**
 * Transcribes a local audio file with whichever {@code TranscriptionModel} the deployment's
 * provider published. Registered only where there is one — see {@code ModelToolsConfiguration}.
 */
@Slf4j
@AgentTool
@RequiredArgsConstructor
public class AudioTranscriptionTool {
  final TranscriptionModel transcriptionModel;

  @Tool(name = "TranscribeAudio", description = "Transcribe a local audio file to text")
  public String transcribeAudio(
      @ToolParam(description = "Absolute path to the local audio file to transcribe")
          final String filePath) {
    log.info("Transcribing audio file: {}", filePath);
    final var transcription =
        transcriptionModel.call(new AudioTranscriptionPrompt(new FileSystemResource(filePath)));
    final var transcribedText = transcription.getResult().getOutput();
    log.info("Transcribed audio file: {}, result length={}", filePath, transcribedText.length());
    return transcribedText;
  }
}
