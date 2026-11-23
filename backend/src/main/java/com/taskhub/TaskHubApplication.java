package com.taskhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TaskHub REST API entry point.
 *
 * <p>The service is fully stateless (JWT bearer auth, no HTTP session), so any number of
 * instances can run behind a load balancer.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TaskHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskHubApplication.class, args);
    }
}
