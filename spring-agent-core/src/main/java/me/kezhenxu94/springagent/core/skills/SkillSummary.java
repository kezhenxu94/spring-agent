package me.kezhenxu94.springagent.core.skills;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One skill, as a list row reads it.
 *
 * <p>{@code name} is the <b>folder name</b> and not the {@code name:} in the front matter, and the
 * two can disagree. The folder is what {@code HomeDir} addresses, what a delete names and what a
 * file inside it is resolved against, so it is the identity; the front matter is a claim the file
 * makes about itself. {@code SkillsTool} derives the model's tool name from the front matter, which
 * is why a disagreement matters enough to be visible — see {@link #declaredName}.
 *
 * @param directory where the folder actually is. Present because the agent's own tools address a
 *     skill by absolute path, and recomputing one from a name and a scope is the second resolution
 *     that ends up without the guard on it. Never put on the wire: an absolute path under {@code
 *     app.storage.location} tells a browser the shape of the storage behind it.
 * @param name the folder the skill lives in, which is its identity
 * @param declaredName what its SKILL.md front matter calls it, or blank where it says nothing —
 *     blank is the case {@code SkillsTool} silently drops, so the page is where it must show
 * @param description the front matter's one-line description, or blank
 * @param fileCount every file under the folder, SKILL.md included
 * @param updatedAt the newest modification time under the folder
 */
public record SkillSummary(
    Path directory,
    String name,
    String declaredName,
    String description,
    int fileCount,
    Instant updatedAt) {}
