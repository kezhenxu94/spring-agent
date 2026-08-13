package me.kezhenxu94.springagent.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when the annotated shell is the one this deployment asked for.
 *
 * <p>Which one that is comes from {@code app.ai.tools.shell.type} and nowhere else, unlike {@link
 * ConditionalOnPersistenceBackend}: depending on a shell module makes that shell available, setting
 * the property is what turns it on.
 *
 * <p>Note for native images: conditions are evaluated during AOT processing, so the shell is fixed
 * when the image is built rather than when it runs. See {@code -PnativeBackends} in {@code
 * springagent.native.gradle}.
 *
 * @see ShellBackendResolver
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ShellBackendCondition.class)
public @interface ConditionalOnShellBackend {

  /** The shell the annotated configuration belongs to. */
  ShellToolsProperties.Type value();
}
