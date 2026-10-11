package com.unimarket.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de Uni Market API: monolito modular en capas (SDD §3).
 */
@SpringBootApplication
public class UniMarketApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniMarketApiApplication.class, args);
    }
}
