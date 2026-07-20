package com.sales.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.File;
import java.util.Scanner;

@SpringBootApplication
@ConfigurationPropertiesScan("com.sales.order.auth.security")
public class OrderServiceApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    private static void loadDotEnv() {
        File[] possibleFiles = new File[]{
                new File(".env"),
                new File("../.env"),
                new File("../../.env")
        };
        for (File envFile : possibleFiles) {
            if (envFile.exists() && envFile.isFile()) {
                try (Scanner scanner = new Scanner(envFile)) {
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine().trim();
                        if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                            String[] parts = line.split("=", 2);
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            if (System.getProperty(key) == null && System.getenv(key) == null) {
                                System.setProperty(key, value);
                            }
                        }
                    }
                } catch (Exception ignored) {}
                break;
            }
        }
    }
}
