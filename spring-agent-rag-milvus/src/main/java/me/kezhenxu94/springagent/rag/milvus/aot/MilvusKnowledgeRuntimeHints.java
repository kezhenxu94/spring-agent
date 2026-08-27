package me.kezhenxu94.springagent.rag.milvus.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What this module needs at runtime that a native image would otherwise discard.
 *
 * <p>Both halves of it are invisible to the image builder for the same reason: they are found by
 * name at runtime, not referenced in bytecode. Tika discovers its parsers through service files
 * listing implementation classes, and Milvus' generated protobuf types are instantiated
 * reflectively by gRPC. A JVM build passing says nothing about either.
 */
public class MilvusKnowledgeRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // Tika's parser discovery: the service files themselves, plus the mime type tables it reads
    // through the same mechanism.
    hints.resources().registerPattern("META-INF/services/org.apache.tika.parser.Parser");
    hints.resources().registerPattern("META-INF/services/org.apache.tika.detect.Detector");
    hints.resources().registerPattern("org/apache/tika/mime/tika-mimetypes.xml");
    hints.resources().registerPattern("org/apache/tika/parser/external/tika-external-parsers.xml");

    // The reader itself is constructed reflectively by nothing, but its parser is: registering the
    // façade keeps the whole chain reachable from a hint the image builder can see.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "org.apache.tika.parser.DefaultParser",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS);
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "org.apache.tika.parser.AutoDetectParser",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS);
  }
}
