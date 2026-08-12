package me.kezhenxu94.springagent.core.aot;

import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Binding hints for {@link FileSystemStorageProperties}.
 *
 * <p>Spring Boot's own AOT processing registers binding hints for the type a
 * {@code @ConfigurationProperties} bean is <em>declared</em> as, and {@code
 * SpringAgentCoreAutoConfiguration#storageProperties} declares {@link StorageProperties} — an
 * interface with getters only. The instance it actually returns is bound through the setters Lombok
 * generates on the concrete class, and those never get reflection metadata.
 *
 * <p>Without this the binding fails silently rather than loudly: every property stays null and the
 * first symptom is a {@code NullPointerException} from {@code Path.of(null)} when {@code
 * FileSystemStorageService} initialises.
 */
public class StoragePropertiesRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints
        .reflection()
        .registerType(
            FileSystemStorageProperties.class,
            // Methods for the generated setters the binder calls and the getters validation reads;
            // constructors because the bean method builds the instance through the Lombok builder.
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);
  }
}
