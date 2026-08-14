package me.kezhenxu94.springagent.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The agent as a command-line tool. Unlike {@code spring-agent-app} there is no web server, no
 * actuator and no security: the only thing this process talks to is the terminal it was started
 * from, and its whole state lives under {@code ~/.spring-agent}.
 *
 * <p>Standard output belongs to the user, so nothing may log to it — see {@code logback.xml}, whose
 * appender defaults to {@code FILE} here rather than {@code CONSOLE}.
 */
@SpringBootApplication
public class SpringAgentCliApplication {

  public static void main(final String[] args) {
    // NONE rather than left to inference: spring-agent-core pulls in a web stack through Spring
    // AI's RestClient, and Boot would start a servlet container on the strength of that alone.
    new SpringApplicationBuilder(SpringAgentCliApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);
  }
}
