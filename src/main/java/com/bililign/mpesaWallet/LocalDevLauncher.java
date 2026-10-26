package com.bililign.mpesaWallet;

import com.bililign.mpesaWallet.bank.FakeBankServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Convenience launcher for local testing and Postman verification.
 * Starts:
 * 1. Embedded PostgreSQL on port 5432 with mpesa_db and schema.sql
 * 2. Fake Bank Server on port 9090
 * 3. Wallet Service Instance 1 on port 8081
 * 4. Wallet Service Instance 2 on port 8082
 */
public class LocalDevLauncher {

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("Starting M-PESA Wallet Service Local Dev Cluster");
        System.out.println("=================================================");

        // 1. Embedded PostgreSQL
        System.out.println(">>> 1/4: Starting PostgreSQL on port 5432...");
        EmbeddedPostgres embeddedPostgres = EmbeddedPostgres.builder().setPort(5432).start();

        // Create mpesa_db
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE mpesa_db");
        }

        // Apply schema
        try (Connection conn = embeddedPostgres.getDatabase("postgres", "mpesa_db").getConnection();
             InputStream in = LocalDevLauncher.class.getResourceAsStream("/schema.sql")) {
            if (in != null) {
                String schemaSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                try (Statement st = conn.createStatement()) {
                    st.execute(schemaSql);
                }
            }
        }

        // 2. Fake Bank on port 9090
        System.out.println(">>> 2/4: Starting Fake Bank Server on port 9090...");
        FakeBankServer fakeBank = new FakeBankServer(9090);
        fakeBank.start();

        // 3. Wallet Instance 1 on port 8081
        System.out.println(">>> 3/4: Starting Instance 1 on port 8081...");
        ConfigurableApplicationContext app1 = new SpringApplicationBuilder(MpesaWalletApplication.class)
                .run(
                        "--server.port=8081",
                        "--spring.datasource.url=jdbc:postgresql://localhost:5432/mpesa_db",
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres",
                        "--server.tomcat.threads.max=50",
                        "--spring.datasource.hikari.maximum-pool-size=10",
                        "--spring.datasource.hikari.connection-timeout=2000"
                );

        // 4. Wallet Instance 2 on port 8082
        System.out.println(">>> 4/4: Starting Instance 2 on port 8082...");
        ConfigurableApplicationContext app2 = new SpringApplicationBuilder(MpesaWalletApplication.class)
                .run(
                        "--server.port=8082",
                        "--spring.datasource.url=jdbc:postgresql://localhost:5432/mpesa_db",
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres",
                        "--server.tomcat.threads.max=50",
                        "--spring.datasource.hikari.maximum-pool-size=10",
                        "--spring.datasource.hikari.connection-timeout=2000"
                );

        // Initialize seed data
        System.out.println(">>> Initializing seed data...");
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest resetReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:8081/mpesa/internal/reset"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(resetReq, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}

        System.out.println("=================================================");
        System.out.println("ALL SERVICES READY FOR POSTMAN TESTING:");
        System.out.println(" - Instance 1: http://localhost:8081");
        System.out.println(" - Instance 2: http://localhost:8082");
        System.out.println(" - Fake Bank:  http://localhost:9090");
        System.out.println(" - PostgreSQL: localhost:5432 (database: mpesa_db)");
        System.out.println("=================================================");

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down cluster...");
            try {
                if (app1 != null) app1.close();
                if (app2 != null) app2.close();
                if (fakeBank != null) fakeBank.stop();
                if (embeddedPostgres != null) embeddedPostgres.close();
            } catch (Exception ignored) {}
        }));

        // Keep process running
        Thread.currentThread().join();
    }
}
