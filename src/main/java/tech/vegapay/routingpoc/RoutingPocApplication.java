package tech.vegapay.routingpoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class RoutingPocApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoutingPocApplication.class, args);
    }
}
