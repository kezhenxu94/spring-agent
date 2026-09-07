package me.kezhenxu94.springagent.provider.dashscope.aot;

import me.kezhenxu94.springagent.provider.dashscope.DashScopeImageModel;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What this module needs at runtime that a native image would otherwise discard.
 *
 * <p>One thing, and it is the response records: {@code RestTemplate} deserializes into them by
 * reflection, so nothing in the bytecode says their constructors or accessors are ever called. A
 * JVM build passing says nothing about it, and the failure in an image is not an error but an empty
 * list — {@code GenerateImage} reporting that it generated no images.
 *
 * <p>The request side needs nothing: it is built as {@code Map}s and {@code List}s, which Jackson
 * serializes without reflecting over a type of ours.
 */
public class DashScopeRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    for (final var type :
        new Class<?>[] {
          DashScopeImageModel.DashScopeImageResponse.class,
          DashScopeImageModel.DashScopeImageResponse.Output.class,
          DashScopeImageModel.DashScopeImageResponse.Choice.class,
          DashScopeImageModel.DashScopeImageResponse.Message.class,
          DashScopeImageModel.DashScopeImageResponse.Content.class
        }) {
      hints
          .reflection()
          .registerType(
              type,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS);
    }
  }
}
