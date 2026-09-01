package me.kezhenxu94.springagent.persistence.mongodb.aot;

import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoChatMemoryRepo;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * The one mapped type here that no repository leads to.
 *
 * <p>Spring Data MongoDB's AOT processing registers the entities it can reach from a repository's
 * generics, and every other document in this module arrives that way. The chat memory entry does
 * not: it is read and written through {@code MongoTemplate} by {@code MongoChatMemoryRepo}, whose
 * signatures mention Spring AI's {@code Message} rather than the document. So a native image would
 * build clean and then fail to map a conversation at runtime, which is the failure mode this whole
 * {@code aot} package exists to prevent.
 */
public class MongoPersistenceRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // Records, so Spring Data maps them through the canonical constructor and the accessors.
    for (final var type :
        new Class<?>[] {MongoChatMemoryRepo.Entry.class, MongoChatMemoryRepo.Entry.Body.class}) {
      hints
          .reflection()
          .registerType(
              type,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS,
              MemberCategory.ACCESS_DECLARED_FIELDS);
    }
  }
}
