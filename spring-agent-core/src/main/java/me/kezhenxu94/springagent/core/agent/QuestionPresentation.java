package me.kezhenxu94.springagent.core.agent;

import java.util.Map;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;

/**
 * A {@link QuestionHandler} that can say whether a call reached the user.
 *
 * <p>Core writes a note into the conversation saying questions were put to the user, and that note
 * has to be true. A handler answers every call, including the ones where it declined to ask —
 * because a form is already waiting, or because it could not post one — and those must leave no
 * note: a second identical one is what a model asking twice produces, and a note after a failed ask
 * claims something that never happened.
 *
 * <p>Implementing this is optional. A handler that always presents what it is given, as an
 * interactive one does, has nothing to add.
 */
public interface QuestionPresentation {

  /** Whether the answers just returned mean the questions reached the user. */
  boolean presented(Map<String, String> answers);
}
