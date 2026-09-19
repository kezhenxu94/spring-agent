package me.kezhenxu94.springagent.core.skills;

/**
 * One file inside a skill, and whether it is the kind of thing that can be shown at all.
 *
 * <p>{@code text} is null in both refusing cases, and they are kept apart because they are not the
 * same news: {@code binary} says this file is not text and never will be, {@code tooLarge} says it
 * is text the page declines to carry. A reader told only "cannot show this" would try the other
 * half of the fix — reformatting a binary, or converting a large file — and neither would help.
 *
 * @param entry the file itself, with the size it really has whether or not the text came
 * @param text the contents as UTF-8, or null where {@code binary} or {@code tooLarge}
 * @param binary a NUL byte in the first block, or bytes that are not UTF-8
 * @param tooLarge over {@link SkillFiles#MAX_TEXT_BYTES}
 */
public record SkillFile(SkillEntry entry, String text, boolean binary, boolean tooLarge) {}
