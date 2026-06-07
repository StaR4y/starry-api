package xyz.star4y.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StarryApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(StarryApiApplication.class, args);
    }
}
