package me.kezhenxu94.springagent.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs work on a thread whose context class loader can find nothing, which is what a run is
 * assembled on more often than it looks.
 *
 * <p>A thread inherits its context class loader from whoever created it, and the JDK hands a common
 * ForkJoinPool worker the system loader rather than the application's. A run submitted from such a
 * thread — or from anything that inherited from one — carries that loader into tool composition on
 * the bounded-elastic scheduler. Inside a Spring Boot fat jar the system loader sees the outer jar
 * and nothing under {@code BOOT-INF/lib}, so every resource these modules ship reads as missing.
 *
 * <p>A loader with no path and no parent stands in for that here: it is what such a thread's loader
 * amounts to as far as this repository's own resources are concerned, and it does not depend on how
 * the tests themselves happen to be launched.
 */
public final class ContextClassLoaders {

  private ContextClassLoaders() {}

  /**
   * Calls {@code work} on a thread that can see nothing on its own, and returns what it returned.
   */
  public static <T> T seeingNothing(final Callable<T> work) throws Exception {
    final var value = new AtomicReference<T>();
    final var failure = new AtomicReference<Throwable>();
    final var thread =
        new Thread(
            () -> {
              try {
                value.set(work.call());
              } catch (Throwable t) {
                failure.set(t);
              }
            });
    thread.setContextClassLoader(new URLClassLoader(new URL[0], null));
    thread.start();
    thread.join();
    if (failure.get() instanceof Exception e) {
      throw e;
    }
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }
}
