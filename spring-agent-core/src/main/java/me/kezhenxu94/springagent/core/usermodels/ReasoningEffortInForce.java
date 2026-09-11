package me.kezhenxu94.springagent.core.usermodels;

/**
 * How hard the model behind a run is being asked to think, in the vocabulary the person choosing it
 * sees — one of {@link ReasoningEfforts#VALUES}, or null where the parameter is not sent at all.
 *
 * <p>A contract rather than a property read, because the answer is not a property. It is the effort
 * a <em>particular run</em> will be made with, which is the deployment's configured one only until
 * somebody registers a model of their own; after that, reading configuration would label a user's
 * run with a number that had nothing to do with it. And it is never in the answer: a completion
 * reports the reasoning tokens it produced, never the effort it was asked for, so only the request
 * side knows.
 *
 * <p><b>A contract of core's rather than a provider's type, because every provider has this concept
 * and each spells it differently.</b> OpenAI has {@code reasoning_effort} on the ladder core owns.
 * Gemini has a {@code thinkingLevel} enum of four values and a separate numeric {@code
 * thinkingBudget}. A surface that wanted to print the effort used to reach for {@code
 * OpenAiChatProperties}, which made the label wrong on one provider and stopped the application
 * starting on another — the bean does not exist when that auto-configuration has backed off.
 * Translating into core's words is the provider's job, the same way {@link BuiltinModels}
 * translates "what can this endpoint serve".
 *
 * <p>Published unconditionally by whichever {@code spring-agent-provider-*} module built the chat
 * model, and independent of whether users may register models of their own: a deployment with no
 * {@code app.ai.user-models.encryption-key} still has an effort in force, it is simply the
 * configured one for everybody. Where user models <em>are</em> enabled, an implementation is
 * expected to consult {@link UserChatClients#effortInForce(String)} so that a user on a model of
 * their own is reported honestly.
 *
 * <p>Consumers should inject it optionally. A provider that has no such setting — or a deployment
 * whose provider module predates this — publishes none, and a surface then shows no effort rather
 * than failing.
 */
@FunctionalInterface
public interface ReasoningEffortInForce {

  /**
   * The effort a run for {@code userId} will actually be made with, or null where the parameter is
   * not sent at all.
   *
   * <p>Must never throw: this is a label, and failing to draw one is not worth failing the run that
   * would be used to fix it.
   *
   * @param userId whose run this is, or null to ask what the deployment itself is configured with
   */
  String forUser(String userId);
}
