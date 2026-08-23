package me.kezhenxu94.springagent.core.storage;

public interface StorageProperties {
  String getLocation();

  /**
   * Where each scope's workspace folder lives, when it must sit on a different volume than the rest
   * of the home (e.g. a fast NAS mount separate from the general-purpose storage backing
   * memories/skills/artifacts). Blank/null means no override: workspace nests under {@link
   * #getLocation()} like every other folder.
   */
  String getWorkspaceLocation();

  String getBaseUrl();

  String getCdnUrl();

  boolean isAutoUnzip();
}
