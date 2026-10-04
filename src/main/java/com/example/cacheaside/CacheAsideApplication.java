package com.example.cacheaside;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CacheAsideApplication {
    public static void main(String[] args) {
        SpringApplication.run(CacheAsideApplication.class, args);
    }
}
