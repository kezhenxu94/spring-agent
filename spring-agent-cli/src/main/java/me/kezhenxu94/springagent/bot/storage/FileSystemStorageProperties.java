package me.kezhenxu94.springagent.bot.storage;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileSystemStorageProperties implements StorageProperties {
  @NotBlank private String location;
  @NotBlank private String cdnUrl;
  @NotBlank private String baseUrl;
  private boolean autoUnzip;
}
