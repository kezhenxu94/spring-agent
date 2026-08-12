package me.kezhenxu94.springagent.core.storage;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Opted out of the project-wide fluent accessors: {@code @ConfigurationProperties} binds this class
 * through Spring Boot's JavaBean binder, which only finds {@code setX}/{@code getX}.
 */
@Data
@Builder
@Accessors(fluent = false)
public class FileSystemStorageProperties implements StorageProperties {
  @NotBlank private String location;
  @NotBlank private String cdnUrl;
  @NotBlank private String baseUrl;
  private boolean autoUnzip;
}
