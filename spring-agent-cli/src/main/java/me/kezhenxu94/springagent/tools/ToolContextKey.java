package me.kezhenxu94.springagent.tools;

/** A typed key for a value stored in a Spring AI {@code ToolContext}. */
public interface ToolContextKey<T> {

  String key();

  Class<T> clazz();
}
