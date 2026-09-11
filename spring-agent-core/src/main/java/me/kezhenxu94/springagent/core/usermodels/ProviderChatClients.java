package me.kezhenxu94.springagent.core.usermodels;

import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import org.springframework.ai.chat.client.ChatClient;

/**
 * One provider's half of bring-your-own-model: how to reach an endpoint <em>in that provider's
 * protocol</em>, given a row somebody registered.
 *
 * <p>Split out from {@link UserChatClients} so a person can choose the protocol, not only the URL.
 * Before this, a deployment published exactly one {@code UserChatClients} — whichever module built
 * the chat model — and every registered endpoint was spoken to with that module's SDK whatever the
 * user typed. On a Gemini deployment a user's OpenAI key went out over {@code generateContent}; on
 * an OpenAI one a Gemini key went to {@code /chat/completions}. Nothing in the form said so,
 * because there was nothing to say: {@link UserModelConfig} had no provider to record.
 *
 * <p>So each {@code spring-agent-provider-*} module publishes one of these, named by {@link
 * #provider()}, and core's {@link DispatchingUserChatClients} picks between them per row. A
 * deployment carrying one provider module behaves exactly as before; carrying two lets a row say
 * which.
 *
 * <p><b>An implementation must work without its own module having built the application's chat
 * model.</b> That is the whole point — the OpenAI provider has to serve a user's OpenAI endpoint on
 * a deployment whose own chat model is Gemini's — so it cannot assume its properties beans exist or
 * copy the application's options. A row carries the base URL, the credential and the model name,
 * which is everything the endpoint needs; anything else is a default of the provider's own.
 *
 * <p>Implementations are expected never to throw from {@link #configuredEffort()} and to cache
 * clients per endpoint rather than per user, since building one opens an HTTP client and a table
 * users can write to must not become unbounded sockets.
 */
public interface ProviderChatClients {

  /**
   * The name a row's {@code provider} field holds to select this one, spelled as Spring AI spells
   * it under {@code spring.ai.model.*} — {@code openai}, {@code google-genai}. The same word an
   * operator already types, so there is one vocabulary rather than two.
   */
  String provider();

  /**
   * A client for one stored row.
   *
   * <p>A row with no base URL is a model of the application's own that the user picked off a list
   * rather than an endpoint of theirs: it borrows the deployment's credential and changes only the
   * model asked for. That case only arises for the provider that <em>did</em> build the
   * application's chat model; for any other, a row with no base URL cannot be served and the
   * implementation should say so by throwing, which {@link DispatchingUserChatClients} turns into
   * the application's own client and a warning.
   */
  ChatClient clientFor(UserModelConfig config);

  /**
   * A client for an endpoint that has not been stored yet, so a registration can be tested before
   * its token is written anywhere.
   *
   * @param token the plaintext token, since there is nothing sealed to open yet
   */
  ChatClient probeClient(UserModelConfig config, String token);

  /**
   * Whether a person registering an endpoint here has to give a base URL.
   *
   * <p>True by default, because most protocols are spoken by many hosts and naming one is the whole
   * point of registering. It is false where the protocol has a single well-known endpoint —
   * Gemini's Developer API — and there asking for a URL asks somebody to invent one.
   *
   * <p>Read by {@code UserModelTools} so the tool does not refuse a registration for want of a
   * field that has no meaningful value. A provider returning false should still honour a base URL
   * that is given, since a gateway may re-serve its protocol.
   */
  default boolean requiresBaseUrl() {
    return true;
  }

  /**
   * How hard this provider's own configured model is asked to think, in {@link ReasoningEfforts}'
   * vocabulary, or null where nothing is configured or this provider built no model here.
   *
   * <p>Here rather than on the dispatcher because the answer is the provider's to translate: OpenAI
   * stores core's own word, Gemini stores a thinking level that has to be read back.
   */
  String configuredEffort();
}
