package me.kezhenxu94.springagent.core.storage;

public interface StorageProperties {
  String getLocation();

  String getBaseUrl();

  String getCdnUrl();

  boolean isAutoUnzip();
}
