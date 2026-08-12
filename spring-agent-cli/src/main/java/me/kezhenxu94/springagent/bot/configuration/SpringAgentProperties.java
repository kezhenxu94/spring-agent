package me.kezhenxu94.springagent.bot.configuration;

import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record SpringAgentProperties(Dashscope dashscope, Ai ai) {

  public record Ai(
      BotInterceptor botInterceptor,
      Set<String> admins,
      Map<String, ModelPricing> modelPricing,
      String systemPrompt) {
    public Ai {
      if (systemPrompt == null || systemPrompt.isBlank()) {
        throw new IllegalArgumentException("app.ai.system-prompt must not be blank");
      }
      if (admins == null) {
        admins = Set.of();
      }
      if (modelPricing == null) {
        modelPricing = Map.of();
      }
    }

    public record BotInterceptor(int guideThreshold) {}

    /** Per-model token pricing used to estimate the approximate cost of a chat completion. */
    public record ModelPricing(
        double nonThinkingInputPerMillion,
        double thinkingInputPerMillion,
        double outputPerMillion,
        Currency currency) {

      @Getter
      @Accessors(fluent = true)
      @RequiredArgsConstructor
      public enum Currency {
        CNY("¥"),
        USD("$");

        private final String symbol;
      }
    }
  }

  public record Dashscope(Image image, Vision vision) {
    public record Image(String apiKey, String baseUrl, String model) {}

    public record Vision(String apiKey, String baseUrl, String model) {}
  }
}
