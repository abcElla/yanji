package cn.yanji.agent;

import cn.yanji.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class AgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(AgentApplication.class, args);
  }
}
