package me.kezhenxu94.springagent.tools;

import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.bot.storage.StorageProperties;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserWorkspaceFactory {
  private final StorageProperties storageProperties;

  public UserHome forOwner(String ownerId) {
    return new UserHome(Path.of(storageProperties.getLocation(), ownerId));
  }
}
