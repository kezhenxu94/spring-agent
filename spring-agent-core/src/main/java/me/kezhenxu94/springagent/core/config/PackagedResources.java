package me.kezhenxu94.springagent.core.config;

import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * A file one of these modules ships in its own jar, found without trusting the calling thread.
 *
 * <p>This exists because {@code new ClassPathResource(path)} resolves through {@code
 * ClassUtils.getDefaultClassLoader()}, which is the <em>calling thread's</em> context class loader
 * — and by the time a prompt or a tool description is read, that thread is whichever one a run was
 * handed, not the one that started the application.
 *
 * <p>The chain that breaks it: a thread inherits its context class loader from whoever created it,
 * and the JDK gives a common ForkJoinPool worker the system loader rather than the application's.
 * Submit a run from such a thread — a {@code CompletableFuture} with no executor of its own, a
 * parallel stream, anything that inherited from one — and Reactor's bounded-elastic scheduler
 * creates its worker with that loader, which is then what tool composition reads resources through.
 * Inside a Spring Boot fat jar the system loader sees the outer jar and nothing under {@code
 * BOOT-INF/lib}, so every file here reads as absent: the run fails on a prompt that is sitting in
 * the same jar as this class, and tool descriptions silently stop being translated.
 *
 * <p>So the loader that read this class is asked first: these files are packaged beside it, so it
 * can always find them, whatever thread is asking. The thread's own loader is still tried after
 * that, and only for what the first one did not find — an application's resources may live in a
 * child loader that this one cannot see, which is exactly the arrangement Spring Boot DevTools
 * creates by loading a project's classes in a restart loader over the jars.
 */
public final class PackagedResources {

  /** The loader that read this class, which is therefore the one holding these modules' jars. */
  private static final ClassLoader OWN = PackagedResources.class.getClassLoader();

  private PackagedResources() {}

  /** The resource at {@code path}, or empty where no loader in reach has it. */
  public static Optional<Resource> find(final String path) {
    final var packaged = new ClassPathResource(path, OWN);
    if (packaged.exists()) {
      return Optional.of(packaged);
    }
    final var contextual = new ClassPathResource(path);
    return contextual.exists() ? Optional.of(contextual) : Optional.empty();
  }
}
