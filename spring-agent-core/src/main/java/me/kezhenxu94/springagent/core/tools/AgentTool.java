package me.kezhenxu94.springagent.core.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an object whose {@code @Tool} methods are offered to the agent. Allowed on a {@code @Bean}
 * factory method too, for a tool whose type comes from a library and cannot be annotated itself.
 *
 * <p>Every annotated bean is offered to every run, unless the run's {@code AgentScenario} keeps it
 * out — the scenario decides, so a consumer's own scenario can rule on tools this runtime ships and
 * the other way round.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

  /**
   * Whether only an administrator may be offered this tool — somebody named in {@code
   * app.ai.admins}. False by default, so a tool is ordinary unless it says otherwise.
   *
   * <p>Left out of the run rather than refused inside the tool, so that a model which may not call
   * one never reads its description either. That is a refusal the model cannot misreport as the
   * tool being broken, and it is a few hundred tokens off every ordinary user's turn.
   *
   * <p>The test is the run's user id and nothing else, so an administrator holds these in every run
   * that is theirs — including a scheduled task they wrote and a subagent their run started. Both
   * act on a brief that same administrator authored, so refusing there would only stop them
   * delegating or deferring work they could do in the chat they are sitting in.
   *
   * <p><b>The identity is therefore the whole of the boundary.</b> A run that assumes an identity
   * holds whatever that identity holds, and nothing about the run can narrow it afterwards. So an
   * identity that reads text written by strangers must never be an administrator: a situation
   * triage assumes {@code app.events.sources.<name>.owner.user-id}, and were that an admin, an
   * issue body could reach {@code WritePlaybook} and rewrite the playbook the next triage is
   * steered by. {@code SituationSweeper} refuses to start on exactly that pairing, which is the
   * earliest point anyone can tell those runs apart from an administrator's own.
   */
  boolean admin() default false;
}
