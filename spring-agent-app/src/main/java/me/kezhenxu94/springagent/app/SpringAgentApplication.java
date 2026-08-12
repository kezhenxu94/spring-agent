package me.kezhenxu94.springagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Assembles the deployable. Everything it runs on — the agent runtime and the Feishu integration —
 * is contributed by auto-configuration, so this package holds only the entry point and the
 * deployment's own security policy.
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class SpringAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(SpringAgentApplication.class, args);
  }
}
