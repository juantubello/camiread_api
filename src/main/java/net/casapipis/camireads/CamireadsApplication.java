package net.casapipis.camireads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class CamireadsApplication {

    public static void main(String[] args) {
        // Forzamos una timezone válida para Postgres
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CamireadsApplication.class, args);
    }
}
