package me.kezhenxu94.springagent.agent;

import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.SignalType;

public interface AgentResponseListener {
  default void onSubscribe() {}

  default void onModel(String model) {}

  void onContent(String contentSoFar);

  void onUsage(String model, Usage usage);

  void onError(Throwable error);

  default void onFinished(SignalType signal) {}

  default boolean shouldContinue() {
    return true;
  }
}
