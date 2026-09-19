package me.kezhenxu94.springagent.core.skills;

import java.util.List;

/**
 * A skill and everything in it, which is what opening one asks for.
 *
 * <p>One record rather than two calls because the page cannot draw either half alone: a tree with
 * no name above it is a file list belonging to nothing, and the name arrives from the same walk the
 * tree does. Answering both from one read also means the two cannot disagree about a skill deleted
 * between them.
 */
public record SkillDetail(SkillSummary skill, List<SkillEntry> entries) {}
