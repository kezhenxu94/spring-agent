package me.kezhenxu94.springagent.core.usermodels;

import java.util.List;

/**
 * What the application's own endpoint says it can serve, so that choosing a model is a menu rather
 * than a guess.
 *
 * <p>One of three contracts a {@code spring-agent-provider-*} module implements itself — see {@link
 * UserChatClients} for why these and nothing else. Listing what an endpoint offers is not something
 * Spring AI models do: {@code GET /models} is an OpenAI API, other providers enumerate differently,
 * and several gateways do not answer at all.
 *
 * <p>Which is why {@link #list()} is best-effort by contract. Any failure is an empty list, and the
 * caller shows the single {@link #defaultModel()} entry it would have shown anyway. A card that
 * opens is worth more than a complete one that does not, especially this card — it is what somebody
 * reaches for when their chosen model has stopped answering.
 */
public interface BuiltinModels {

  /** The model the application is configured to use, which is what "the built-in model" means. */
  String defaultModel();

  /** Every model the application's endpoint offers, or an empty list where it will not say. */
  List<String> list();
}
