package me.kezhenxu94.springagent.core.memory;

import java.time.Instant;

/**
 * One memory file, as a list row reads it.
 *
 * <p>{@code path} is relative to the scope's memories root and always spelt with {@code /},
 * whatever the filesystem separator is — the rule {@code SkillEntry} states, for the same reason:
 * this path is half of a URL's query string and half of the page's hash, and two spellings of one
 * file is a row that can be opened but never appears open.
 *
 * <p>The three front-matter fields are a <b>claim the file makes about itself</b> and not an
 * identity. The path is the identity: it is what a read resolves, what a delete names and what
 * {@code MEMORY.md} indexes. A memory whose front matter is missing, malformed or absent still has
 * every one of those, so each field is blank rather than the record being refused — a file the
 * agent wrote badly must still be visible to the person who would fix it.
 *
 * <p><b>This record is the wire shape.</b> {@code MemoryController} hands it straight to Jackson
 * rather than copying it field by field into a map, which it can do only because there is no {@code
 * Path} on it — {@code SkillSummary} carries the directory its skill is in and therefore needs a
 * controller to strip it on the way out. So anything added here is something every browser that can
 * open the page will see, and an absolute path under {@code app.storage.location} describes the
 * shape of the storage behind it.
 *
 * @param path relative to the memories root, {@code /}-separated, never leading or trailing
 * @param size bytes
 * @param updatedAt last modification time
 * @param name the front matter's {@code name}, or blank
 * @param description the front matter's {@code description}, or blank
 * @param type the front matter's {@code type} — user, feedback, project, reference — or blank
 * @param index whether this is {@link MemoryStore#INDEX}, which is not a memory but the list of
 *     them, and is therefore the one file a reader should be shown first
 */
public record MemoryEntry(
    String path,
    long size,
    Instant updatedAt,
    String name,
    String description,
    String type,
    boolean index) {}
