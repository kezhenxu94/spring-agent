package me.kezhenxu94.springagent.core.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

  void init();

  Path store(MultipartFile file);

  Path store(InputStream inputStream, String filename);

  Stream<Path> loadAll();

  Path load(String filename);

  Resource loadAsResource(String filename);

  void deleteAll();

  Path resolve(String filename);
}
