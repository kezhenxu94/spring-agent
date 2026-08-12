package me.kezhenxu94.springagent.bot.storage;

public interface StorageProperties {
  String getLocation();

  String getBaseUrl();

  String getCdnUrl();

  boolean isAutoUnzip();
}
