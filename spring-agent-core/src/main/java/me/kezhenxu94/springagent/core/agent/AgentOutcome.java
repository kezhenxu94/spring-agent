package me.kezhenxu94.springagent.core.agent;

/**
 * How an agent run ended. Core owns this classification so integrations never have to infer it from
 * reactor signals or from the shape of the error that surfaced.
 */
public enum AgentOutcome {
  /** The agent finished producing its response. */
  COMPLETED,

  /** The run was aborted by the model, the transport, or the assembly of the request itself. */
  FAILED,

  /**
   * The run was stopped on request, either through {@link SpringAgent#cancel(String)} or a listener
   * that stopped consuming.
   */
  CANCELLED
}
