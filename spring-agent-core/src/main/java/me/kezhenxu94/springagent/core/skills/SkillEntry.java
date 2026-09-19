package me.kezhenxu94.springagent.core.skills;

/**
 * One file or folder inside a skill, addressed the way the wire and the page address it.
 *
 * <p>{@code path} is relative to the skill's own folder and always spelt with {@code /}, whatever
 * the filesystem separator is. That is not cosmetic: the path is half of a URL's query string and
 * half of the page's hash, and a backslash arriving from a Windows host would be two different
 * spellings of one file — which, in a list the page marks a selection in, is a file that can be
 * opened but never appears open.
 *
 * @param path relative to the skill folder, {@code /}-separated, never leading or trailing
 * @param dir whether this is a folder rather than a file
 * @param size bytes, or 0 for a folder
 */
public record SkillEntry(String path, boolean dir, long size) {}
