package me.kezhenxu94.springagent.core.tools;

/**
 * A tool only an administrator may use, and only in a run a person is watching.
 *
 * <p>Marked rather than checked inside the tool, because the check has two halves that belong in
 * two different places and both have to hold:
 *
 * <ul>
 *   <li><b>Who.</b> The person the run belongs to is named in {@code app.ai.admins}.
 *   <li><b>Which kind of run.</b> {@code AgentScenario.adminTools()} says this kind of run may hold
 *       one. It is off unless a scenario says otherwise, so only {@code BuiltInScenarios.CHAT} —
 *       somebody talking to the agent and waiting for the answer — qualifies out of the box. That
 *       half is the load-bearing one. A triage run assumes the identity in {@code
 *       app.events.sources.<name>.owner-user-id}, and a deployment has every reason to list that
 *       identity as an admin, since it is the one that needs to reach every chat; without this half
 *       such a run could use an admin tool to write the very playbook its successors are steered
 *       by, on the say-so of whoever wrote the event it is triaging.
 * </ul>
 *
 * <p>{@code AgentToolsProvider} asks the two together and leaves the tool out of the run when
 * either says no, so a model that may not call one never reads its description either.
 *
 * <p>So the rule is: an admin tool is for a person, present, asking for it. Nothing that runs on a
 * timer or on somebody else's text gets one.
 */
public interface AdminTool {}
