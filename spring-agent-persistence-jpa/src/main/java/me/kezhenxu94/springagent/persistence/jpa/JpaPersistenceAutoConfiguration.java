package me.kezhenxu94.springagent.persistence.jpa;

import me.kezhenxu94.springagent.core.config.ConditionalOnPersistenceBackend;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.persistence.jpa.aot.PersistenceRuntimeHints;
import me.kezhenxu94.springagent.persistence.jpa.repo.JpaScheduledTaskRepo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the JPA implementations of the repository contracts {@code spring-agent-core} declares.
 *
 * <p>This class doubles as the marker that tells {@code spring-agent-core} the JPA backend is
 * available at all — see {@code PersistenceBackendResolver}. Renaming or moving it changes the
 * classpath-based selection, so do both together.
 *
 * <p>The two backends are mutually exclusive by construction: each contract has exactly one
 * implementation per module, so the application's injection points resolve without qualifiers.
 * Having both active would give every contract two candidate beans, which is what {@link
 * ConditionalOnPersistenceBackend} exists to prevent in an application that carries both modules.
 *
 * <p>{@code basePackageClasses} deliberately points into this module's {@code repo} package and not
 * at the contracts in core: the contracts are plain interfaces, and Spring Data would try to build
 * implementations for them too.
 *
 * <p>{@code @EntityScan} is needed because the models live in another module, and without the Boot
 * plugin's auto-detection a library module has no persistence unit root to scan.
 */
@AutoConfiguration
@ConditionalOnPersistenceBackend(Type.JPA)
@ImportRuntimeHints(PersistenceRuntimeHints.class)
@EnableJpaRepositories(basePackageClasses = JpaScheduledTaskRepo.class)
@EntityScan(basePackageClasses = McpServerConfig.class)
public class JpaPersistenceAutoConfiguration {}
