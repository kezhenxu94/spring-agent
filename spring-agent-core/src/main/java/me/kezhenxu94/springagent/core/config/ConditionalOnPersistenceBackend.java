package me.kezhenxu94.springagent.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when the annotated backend is the one this deployment uses.
 *
 * <p>
 * Which one that is comes from the classpath first and
 * {@code app.persistence.type} second. A
 * consumer of this SDK adds exactly one of
 * {@code spring-agent-persistence-jdbc} or {@code
 * spring-agent-persistence-mongodb} and configures nothing; the module they
 * chose is the answer.
 * {@code app.persistence.type} exists for the deployment that carries both —
 * {@code
 * spring-agent-app} does, so that a single image can be pointed at either — and
 * it wins whenever it
 * is set, which is what makes this indistinguishable from the
 * {@code @ConditionalOnProperty} it
 * replaced.
 *
 * <p>
 * Note for native images: conditions are evaluated during AOT processing, so
 * the backend is
 * fixed when the image is built rather than when it runs. See
 * {@code -PnativeBackends} in {@code
 * springagent.native.gradle}. Classpath presence is the more stable half of
 * this — a property can
 * be changed at runtime and be quietly ignored, whereas a compiled-in binary's
 * classpath cannot.
 *
 * @see PersistenceBackendResolver
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
@Documented
@Conditional(PersistenceBackendCondition.class)
public @interface ConditionalOnPersistenceBackend {

  /** The backend the annotated configuration belongs to. */
  PersistenceProperties.Type value();
}
