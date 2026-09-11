package me.kezhenxu94.springagent.core.usermodels;

import java.util.List;
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
 * <p><b>A client built here must be built with the context's {@code ToolCallingManager}.</b> The
 * advisor {@code SpringAgent} registers executes a run's tool calls, but the tool list a request
 * carries is resolved by the chat model, from the manager it was built with — and a builder given
 * none substitutes a plain default. A client built without it works, answers, and calls tools,
 * while offering the endpoint definitions that none of the runtime's rewrites reached: no {@code
 * core/tools/DisplayDescription} parameter, so no tool call has a title on any surface, and no
 * localized tool or parameter descriptions. See {@code OpenAiUserChatClients#build}.
 *
 * <p>Implementations are expected never to throw and never to return null from {@link #forUser}: a
 * user whose stored endpoint cannot be read must get the application's model, because failing here
 * would fail the very run they would use to fix it.
 */
public interface UserChatClients {

  /**
   * Which protocols a row may name on this deployment, as {@link ProviderChatClients#provider()}
   * spells them — exactly the provider modules on the classpath, and never a fixed list.
   *
   * <p>Here so a form can draw a select of what is actually served rather than of what exists in
   * the world: offering a protocol no module implements would let somebody register an endpoint
   * that can only ever fail, which is the shape of mistake this project avoids everywhere else. A
   * deployment carrying one provider returns one entry, and a surface should then draw nothing at
   * all — there is no choice to make.
   */
  List<String> providers();

  /**
   * What a row naming no provider is spoken in, or null where nothing serves user models. The value
   * a form should preselect, and what every row written before the field existed means.
   */
  String defaultProvider();

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
