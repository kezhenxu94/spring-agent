package me.kezhenxu94.springagent.core.memory;

/**
 * One memory file's contents, and whether it is the kind of thing that can be shown at all.
 *
 * <p>The same three-way answer {@code SkillFile} gives, and the two refusing cases are kept apart
 * for the same reason: {@code binary} says this will never be text, {@code tooLarge} says it is
 * text the page declines to carry, and a reader told only "cannot show this" would try the wrong
 * half of the fix.
 *
 * <p>A memory is prose the agent wrote, so neither case should ever happen — which is exactly why
 * they are reported rather than assumed away. A memories directory is inside a home the shell and
 * the file tools can both write, so something that is not a memory can end up in it, and the page
 * has to be able to say so instead of drawing a megabyte of bytes as text.
 *
 * @param entry the file itself, with the size it really has whether or not the text came
 * @param text the contents as UTF-8, or null where {@code binary} or {@code tooLarge}
 * @param binary a NUL byte in the first block, or bytes that are not UTF-8
 * @param tooLarge over {@link MemoryStore#MAX_TEXT_BYTES}
 */
public record MemoryFile(MemoryEntry entry, String text, boolean binary, boolean tooLarge) {}
