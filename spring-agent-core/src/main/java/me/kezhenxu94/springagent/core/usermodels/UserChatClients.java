package me.kezhenxu94.springagent.core.usermodels;

import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Which {@link ChatClient} a run goes through: the user's own where they have chosen one, the
 * application's otherwise.
 *
 * <p>One of three contracts a {@code spring-agent-provider-*} module implements itself — the others
 * being {@link BuiltinModels} and {@code core/agent/ProviderRejection}. Everything else a model
 * provider offers — a chat model, an embedding model, a transcription model, an image model — is a
 * Spring AI interface this project simply injects, and inventing a second name for one of those
 * would only add a layer to unwrap. There is no Spring AI interface for <em>this</em>, though: it
 * is "build a client for an endpoint somebody typed into a chat five seconds ago", and Spring AI's
 * model beans are all built once, at startup, from configuration.
 *
 * <p>Implementations are expected never to throw and never to return null from {@link #forUser}: a
 * user whose stored endpoint cannot be read must get the application's model, because failing here
 * would fail the very run they would use to fix it.
 */
public interface UserChatClients {

  /** The client {@code userId}'s runs should go through. */
  ChatClient forUser(String userId);

  /**
   * The reasoning effort a run for this user will actually be made with, or null where the
   * parameter is not sent at all.
   *
   * <p>On the contract rather than left to the caller, because a surface that tells the user how
   * hard their model was asked to think must not answer that question from the deployment's
   * configuration: a user on a model of their own would be shown a number that had nothing to do
   * with their run.
   */
  String effortInForce(String userId);

  /** The client for one stored row. */
  ChatClient clientFor(UserModelConfig config);

  /**
   * A client for an endpoint that has not been stored yet, so that a registration can be tested
   * before its token is written anywhere.
   *
   * @param token the plaintext token, since there is nothing sealed to open yet
   */
  ChatClient probeClient(UserModelConfig config, String token);
}
