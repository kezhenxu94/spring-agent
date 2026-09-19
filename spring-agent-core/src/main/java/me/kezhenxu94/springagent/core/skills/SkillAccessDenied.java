package me.kezhenxu94.springagent.core.skills;

/**
 * A path that would have left the skills directory it was addressed in.
 *
 * <p>Its own type rather than an {@code IllegalArgumentException} because the two callers answer it
 * differently and both have to be able to tell it apart from a plain bad argument: {@code
 * SkillManagementTools} turns it into the sentence the model reads, and the browser's controller
 * into a refusal the page can show. A caller that cannot tell "outside your home" from "that file
 * is not there" ends up reporting one as the other, and a traversal attempt reads like a typo.
 *
 * <p>Carries no path in its message on purpose. What was asked for is already in the request the
 * caller has; repeating a constructed absolute path back out of the process is how a probe learns
 * the shape of the storage it did not reach.
 */
public class SkillAccessDenied extends RuntimeException {

  public SkillAccessDenied(final String message) {
    super(message);
  }
}
