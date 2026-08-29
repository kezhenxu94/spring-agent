package me.kezhenxu94.springagent.core.dao.repo;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared — see {@link ScheduledTaskRepo}.
 *
 * <p>Named after the first thing that needed it, and used for two now. A surface claims the id of a
 * message it is about to answer, so a redelivery is passed over rather than answered twice; and a
 * replica claims a unit of work it is about to start — the evaluation of one situation, keyed by
 * that situation and the number of the attempt — so that two of them sweeping the same database do
 * not both wake the agent for it. Both want the same thing from the same operation: an atomic
 * first-caller-wins across replicas, which is why there is one method rather than two.
 */
public interface ProcessedMessageRepo {

  /**
   * Claims {@code id} for this caller, and says whether it got it.
   *
   * <p>The point of the method is that it is atomic across replicas: two of them handed the same
   * message at the same time must not both be told they may answer it. A read followed by a write
   * would let both through, which is the case this exists for — so no backend implements it that
   * way, and none of them can derive it either.
   *
   * <p>A claim does not expire, and that is deliberate rather than an omission to be tidied up
   * later. A message is answered once or never; a claim that lapsed would let a redelivery arriving
   * after it be answered a second time, which is the one thing this exists to prevent. What it
   * costs is one small record per message received, kept for good — worth it against reintroducing
   * the duplicate. {@link #release} is how a claim is given up, and it is only ever right where
   * nothing answered the message.
   *
   * @return true when this caller is the first to claim {@code id}, false when it was already
   *     claimed
   */
  boolean claim(String id);

  /**
   * Gives up a claim, so that being handed the message again takes it up rather than passing it
   * over.
   *
   * <p>For the case where the claim was taken and then nothing came of it. Holding it would leave
   * the message unanswered and every redelivery of it ignored, which is a worse failure than the
   * duplicate answer the claim exists to prevent.
   */
  void release(String id);
}
