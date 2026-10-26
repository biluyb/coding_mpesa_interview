package com.bililign.mpesaWallet.e2e;

import com.bililign.mpesaWallet.MpesaWalletApplication;
import com.bililign.mpesaWallet.bank.FakeBankServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class EndToEndTestSuite {

    private static EmbeddedPostgres embeddedPostgres;
    private static FakeBankServer fakeBank;
    private static ConfigurableApplicationContext app1;
    private static ConfigurableApplicationContext app2;

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String PORT1 = "8081";
    private static final String PORT2 = "8082";

    @BeforeAll
    public static void startInfrastructure() throws Exception {
        System.out.println(">>> Starting Embedded PostgreSQL on port 5432...");
        embeddedPostgres = EmbeddedPostgres.builder().setPort(5432).start();

        // Initialize mpesa_db and schema
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE mpesa_db");
        }

        try (Connection conn = embeddedPostgres.getDatabase("postgres", "mpesa_db").getConnection();
             InputStream in = EndToEndTestSuite.class.getResourceAsStream("/schema.sql")) {
            String schemaSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement st = conn.createStatement()) {
                st.execute(schemaSql);
            }
        }

        System.out.println(">>> Starting Fake Bank on port 9090...");
        fakeBank = new FakeBankServer(9090);
        fakeBank.start();

        System.out.println(">>> Starting Instance 1 on port 8081...");
        app1 = new SpringApplicationBuilder(MpesaWalletApplication.class)
                .run(
                        "--server.port=" + PORT1,
                        "--spring.datasource.url=jdbc:postgresql://localhost:5432/mpesa_db",
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres",
                        "--server.tomcat.threads.max=50",
                        "--spring.datasource.hikari.maximum-pool-size=10",
                        "--spring.datasource.hikari.connection-timeout=2000"
                );

        System.out.println(">>> Starting Instance 2 on port 8082...");
        app2 = new SpringApplicationBuilder(MpesaWalletApplication.class)
                .run(
                        "--server.port=" + PORT2,
                        "--spring.datasource.url=jdbc:postgresql://localhost:5432/mpesa_db",
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres",
                        "--server.tomcat.threads.max=50",
                        "--spring.datasource.hikari.maximum-pool-size=10",
                        "--spring.datasource.hikari.connection-timeout=2000"
                );

        System.out.println(">>> Infrastructure ready.");
    }

    @AfterAll
    public static void stopInfrastructure() throws Exception {
        if (app1 != null) app1.close();
        if (app2 != null) app2.close();
        if (fakeBank != null) fakeBank.stop();
        if (embeddedPostgres != null) embeddedPostgres.close();
    }

    @BeforeEach
    public void resetScenario() throws Exception {
        // Reset DB seed data
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/internal/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        // Reset Fake Bank
        HttpRequest bankReset = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:9090/bank/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        client.send(bankReset, HttpResponse.BodyHandlers.ofString());
    }

    private String randomPort() {
        return ThreadLocalRandom.current().nextBoolean() ? PORT1 : PORT2;
    }

    private long getTotalSystemBalance() throws Exception {
        try (Connection conn = embeddedPostgres.getDatabase("postgres", "mpesa_db").getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUM(balance) FROM wallets")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private JsonNode getWallet(String id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/wallets/" + id))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return mapper.readTree(resp.body());
    }

    // ==========================================
    // S1.1: 200 concurrent transfers of 10,000 from W001
    // ==========================================
    @Test
    public void testS1_1_ConcurrentTransfersOverdraft() throws Exception {
        long initialTotal = getTotalSystemBalance();
        int totalRequests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> statusCodes = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalRequests; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    String targetWallet = String.format("W%03d", (idx % 90) + 2);
                    String body = String.format("{\"from_wallet\":\"W001\",\"to_wallet\":\"%s\",\"amount\":10000,\"reference\":\"s1-1-%d\"}", targetWallet, idx);
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + (idx % 2 == 0 ? PORT1 : PORT2) + "/mpesa/v1/p2p/transfers"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "key-s11-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    statusCodes.add(resp.statusCode());
                } catch (Exception e) {
                    statusCodes.add(500);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        int count201 = 0;
        int count422 = 0;
        for (int code : statusCodes) {
            if (code == 201) count201++;
            else if (code == 422) count422++;
        }

        assertEquals(100, count201, "Exactly 100 transfers must succeed");
        assertEquals(100, count422, "Exactly 100 transfers must fail with 422 INSUFFICIENT_FUNDS");

        JsonNode w001 = getWallet("W001");
        assertEquals(0, w001.path("balance").asLong());
        assertEquals(0, w001.path("available").asLong());
        assertEquals(initialTotal, getTotalSystemBalance(), "Sum of all balances must remain unchanged");
    }

    // ==========================================
    // S1.2: 20 concurrent identical requests with one Idempotency-Key
    // ==========================================
    @Test
    public void testS1_2_ConcurrentIdempotentRequests() throws Exception {
        String key = "shared-idemp-s12-" + UUID.randomUUID();
        String body = "{\"from_wallet\":\"W001\",\"to_wallet\":\"W002\",\"amount\":10000,\"reference\":\"idemp-test\"}";

        int total = 20;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> transferIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> statusCodes = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + (idx % 2 == 0 ? PORT1 : PORT2) + "/mpesa/v1/p2p/transfers"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", key)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    statusCodes.add(resp.statusCode());
                    if (resp.statusCode() == 201 || resp.statusCode() == 200) {
                        JsonNode resJson = mapper.readTree(resp.body());
                        transferIds.add(resJson.path("transfer_id").asText());
                    }
                } catch (Exception ignored) {}
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // All successful requests must carry the same transfer_id
        assertFalse(transferIds.isEmpty());
        String expectedTransferId = transferIds.peek();
        for (String tid : transferIds) {
            assertEquals(expectedTransferId, tid);
        }

        // Send one more request after all finish
        HttpRequest finalReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> finalResp = client.send(finalReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, finalResp.statusCode());
        JsonNode finalJson = mapper.readTree(finalResp.body());
        assertEquals(expectedTransferId, finalJson.path("transfer_id").asText());

        // W001 debited exactly once
        JsonNode w001 = getWallet("W001");
        assertEquals(990000, w001.path("balance").asLong());
    }

    // ==========================================
    // S1.3: 50 transfers W001->W002 and 50 W002->W001 concurrent (Deadlock test)
    // ==========================================
    @Test
    public void testS1_3_BidirectionalConcurrentTransfersNoDeadlock() throws Exception {
        int pairs = 50;
        ExecutorService pool = Executors.newFixedThreadPool(40);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> statusCodes = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        long start = System.currentTimeMillis();

        for (int i = 0; i < pairs; i++) {
            final int idx = i;
            // 50 W001 -> W002
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    String body = "{\"from_wallet\":\"W001\",\"to_wallet\":\"W002\",\"amount\":1000,\"reference\":\"fwd-" + idx + "\"}";
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/p2p/transfers"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "fwd-key-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    statusCodes.add(resp.statusCode());
                } catch (Exception e) {
                    statusCodes.add(500);
                }
            }));
            // 50 W002 -> W001
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    String body = "{\"from_wallet\":\"W002\",\"to_wallet\":\"W001\",\"amount\":1000,\"reference\":\"rev-" + idx + "\"}";
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/p2p/transfers"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "rev-key-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    statusCodes.add(resp.statusCode());
                } catch (Exception e) {
                    statusCodes.add(500);
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        long duration = System.currentTimeMillis() - start;
        assertTrue(duration < 10000, "All 100 transfers must complete within 10 seconds");

        int count201 = 0;
        for (int code : statusCodes) {
            if (code == 201) count201++;
        }
        assertEquals(100, count201);

        JsonNode w001 = getWallet("W001");
        JsonNode w002 = getWallet("W002");
        assertEquals(1000000, w001.path("balance").asLong());
        assertEquals(1000000, w002.path("balance").asLong());
    }

    // ==========================================
    // S2.1: 30 concurrent reversals of one transfer
    // ==========================================
    @Test
    public void testS2_1_ConcurrentReversal() throws Exception {
        // Create initial transfer
        String body = "{\"from_wallet\":\"W001\",\"to_wallet\":\"W002\",\"amount\":10000,\"reference\":\"to-reverse\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "init-transfer-s21")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode());
        String transferId = mapper.readTree(resp.body()).path("transfer_id").asText();

        // 30 concurrent reversals
        int total = 30;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> statusCodes = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    HttpRequest revReq = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/p2p/transfers/" + transferId + "/reversal"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"test\"}"))
                            .build();
                    HttpResponse<String> revResp = client.send(revReq, HttpResponse.BodyHandlers.ofString());
                    statusCodes.add(revResp.statusCode());
                } catch (Exception ignored) {}
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        int count200 = 0;
        int count409 = 0;
        for (int c : statusCodes) {
            if (c == 200) count200++;
            if (c == 409) count409++;
        }

        assertEquals(1, count200, "Exactly one reversal must return 200");
        assertEquals(29, count409, "29 reversals must return 409 ALREADY_REVERSED");

        JsonNode w001 = getWallet("W001");
        JsonNode w002 = getWallet("W002");
        assertEquals(1000000, w001.path("balance").asLong());
        assertEquals(1000000, w002.path("balance").asLong());
    }

    // ==========================================
    // S2.3: Reversal succeeds into FROZEN wallet
    // ==========================================
    @Test
    public void testS2_3_ReversalToFrozenWallet() throws Exception {
        // W030 sends 5,000 to W031
        String body = "{\"from_wallet\":\"W030\",\"to_wallet\":\"W031\",\"amount\":5000,\"reference\":\"test\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "s23-key")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode());
        String transferId = mapper.readTree(resp.body()).path("transfer_id").asText();

        // Freeze W030
        HttpRequest freeze = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082/mpesa/v1/wallets/W030/status"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"FROZEN\",\"reason\":\"test\"}"))
                .build();
        HttpResponse<String> freezeResp = client.send(freeze, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, freezeResp.statusCode());

        // Reversal must succeed and credit W030
        HttpRequest revReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers/" + transferId + "/reversal"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"reversal\"}"))
                .build();
        HttpResponse<String> revResp = client.send(revReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, revResp.statusCode());

        JsonNode w030 = getWallet("W030");
        assertEquals("FROZEN", w030.path("status").asText());
        assertEquals(1000000, w030.path("balance").asLong());
    }

    // ==========================================
    // S3.1: Freeze under heavy traffic
    // ==========================================
    @Test
    public void testS3_1_FreezeUnderHeavyTraffic() throws Exception {
        int total = 300;
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger count201 = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    String b = String.format("{\"from_wallet\":\"W030\",\"to_wallet\":\"W031\",\"amount\":1000,\"reference\":\"t-%d\"}", idx);
                    HttpRequest r = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/p2p/transfers"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "s31-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString(b))
                            .build();
                    HttpResponse<String> res = client.send(r, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 201) {
                        count201.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }));
        }

        startLatch.countDown();
        // Wait ~100ms into the load
        Thread.sleep(100);

        long freezeStart = System.currentTimeMillis();
        HttpRequest freezeReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082/mpesa/v1/wallets/W030/status"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"FROZEN\",\"reason\":\"freeze\"}"))
                .build();
        HttpResponse<String> freezeResp = client.send(freezeReq, HttpResponse.BodyHandlers.ofString());
        long freezeDuration = System.currentTimeMillis() - freezeStart;

        assertEquals(200, freezeResp.statusCode());
        assertTrue(freezeDuration < 2000, "Freeze must return under 2 seconds (took: " + freezeDuration + "ms)");

        // Read balance B immediately as freeze returns
        JsonNode w030AfterFreeze = getWallet("W030");
        long balanceB = w030AfterFreeze.path("balance").asLong();

        // Await remaining in-flight requests
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // Verify balance B invariant
        JsonNode w030Final = getWallet("W030");
        assertEquals(balanceB, w030Final.path("balance").asLong(), "W030 balance must remain exactly B after all requests finish");
        assertEquals((1000000 - balanceB) / 1000, count201.get(), "201 count must equal (1,000,000 - B) / 1,000");
    }

    // ==========================================
    // S4.1: 60 concurrent payouts of 5,000 from M001
    // ==========================================
    @Test
    public void testS4_1_ConcurrentPayouts() throws Exception {
        int total = 60;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> payoutIds = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    long t0 = System.currentTimeMillis();
                    String body = "{\"wallet_id\":\"M001\",\"bank_account\":\"ACC-001\",\"amount\":5000}";
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/payouts"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "payout-s41-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    long latency = System.currentTimeMillis() - t0;
                    latencies.add(latency);
                    if (resp.statusCode() == 202) {
                        JsonNode json = mapper.readTree(resp.body());
                        payoutIds.add(json.path("payout_id").asText());
                    }
                } catch (Exception ignored) {}
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(60, payoutIds.size(), "All 60 payouts must return 202 ACCEPTED");

        // Calculate p95 latency
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);
        long p95 = sortedLatencies.get((int) (sortedLatencies.size() * 0.95));
        assertTrue(p95 < 500, "p95 must be under 500 ms (was: " + p95 + " ms)");

        // Wait up to 30 seconds for all payouts to reach terminal status
        long deadline = System.currentTimeMillis() + 30000;
        for (String pid : payoutIds) {
            while (System.currentTimeMillis() < deadline) {
                HttpRequest check = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8081/mpesa/v1/payouts/" + pid))
                        .GET().build();
                HttpResponse<String> r = client.send(check, HttpResponse.BodyHandlers.ofString());
                String status = mapper.readTree(r.body()).path("status").asText();
                if (!"PENDING".equals(status)) {
                    break;
                }
                Thread.sleep(200);
            }
        }
    }

    // ==========================================
    // S5.1: Duplicate callbacks (2 or 3 times concurrently)
    // ==========================================
    @Test
    public void testS5_1_DuplicateCallbacks() throws Exception {
        // Configure fake bank for 100% duplicates
        fakeBank.duplicateCallbackRate = 1.0;
        fakeBank.submitLatencyMinMs = 50;
        fakeBank.submitLatencyMaxMs = 150;
        fakeBank.callbackDelayMinMs = 100;
        fakeBank.callbackDelayMaxMs = 300;

        int count = 40;
        List<String> payoutIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String body = "{\"wallet_id\":\"M001\",\"bank_account\":\"ACC-001\",\"amount\":5000}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/payouts"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "payout-s51-" + i)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, resp.statusCode());
            payoutIds.add(mapper.readTree(resp.body()).path("payout_id").asText());
        }

        // Wait until all 40 payouts are no longer PENDING
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = embeddedPostgres.getDatabase("postgres", "mpesa_db").getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payouts WHERE status = 'PENDING' AND wallet_id = 'M001'")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    break;
                }
            }
            Thread.sleep(200);
        }

        // Count SUCCEEDED payouts from database
        long succeededCount = 0;
        try (Connection conn = embeddedPostgres.getDatabase("postgres", "mpesa_db").getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payouts WHERE status = 'SUCCEEDED' AND wallet_id = 'M001'")) {
            rs.next();
            succeededCount = rs.getLong(1);
        }

        JsonNode m001 = getWallet("M001");
        long expectedDrop = succeededCount * 5000;
        assertEquals(50000000 - expectedDrop, m001.path("balance").asLong(), "M001 balance drops by exactly the SUCCEEDED total");
        assertEquals(0, m001.path("reserved").asLong(), "No reservation released twice or left unreleased");
    }

    // ==========================================
    // S2.2: Concurrent reversal and transfer race
    // ==========================================
    @Test
    public void testS2_2_ConcurrentReversalAndTransferRace() throws Exception {
        // W020 sends 300,000 to W021
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "s22-transfer-1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"from_wallet\":\"W020\",\"to_wallet\":\"W021\",\"amount\":300000,\"reference\":\"t1\"}"))
                .build();
        HttpResponse<String> resp1 = client.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp1.statusCode());
        String t1Id = mapper.readTree(resp1.body()).path("transfer_id").asText();

        // W021 sends 1,000,000 to W022, leaving exactly 300,000 in W021
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082/mpesa/v1/p2p/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "s22-transfer-2")
                .POST(HttpRequest.BodyPublishers.ofString("{\"from_wallet\":\"W021\",\"to_wallet\":\"W022\",\"amount\":1000000,\"reference\":\"t2\"}"))
                .build();
        HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp2.statusCode());

        // Concurrently: reverse the first transfer (debits W021 300,000) and send 300,000 from W021 to W023
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        Future<?> f1 = pool.submit(() -> {
            try {
                latch.await();
                HttpRequest rev = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers/" + t1Id + "/reversal"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"race\"}"))
                        .build();
                HttpResponse<String> r = client.send(rev, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) successCount.incrementAndGet();
            } catch (Exception ignored) {}
        });

        Future<?> f2 = pool.submit(() -> {
            try {
                latch.await();
                HttpRequest xfer = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8082/mpesa/v1/p2p/transfers"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "s22-race-transfer")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"from_wallet\":\"W021\",\"to_wallet\":\"W023\",\"amount\":300000,\"reference\":\"race\"}"))
                        .build();
                HttpResponse<String> r = client.send(xfer, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 201) successCount.incrementAndGet();
            } catch (Exception ignored) {}
        });

        latch.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        assertEquals(1, successCount.get(), "Exactly one of the concurrent debits must succeed");
        JsonNode w021 = getWallet("W021");
        assertEquals(0, w021.path("balance").asLong(), "W021 balance must be 0");
    }

    // ==========================================
    // S3.2: Repeated GET of M001 while payouts in flight
    // ==========================================
    @Test
    public void testS3_2_RepeatedGetDuringPayouts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(15);
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8081/mpesa/v1/payouts"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "s32-payout-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"wallet_id\":\"M001\",\"bank_account\":\"ACC-1\",\"amount\":5000}"))
                            .build();
                    client.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {}
            });
        }

        for (int i = 0; i < 30; i++) {
            JsonNode m001 = getWallet("M001");
            long balance = m001.path("balance").asLong();
            long reserved = m001.path("reserved").asLong();
            long available = m001.path("available").asLong();
            assertEquals(balance - reserved, available, "available must equal balance - reserved");
            assertTrue(available >= 0, "available must never be negative");
            assertTrue(balance >= 0, "balance must never be negative");
            Thread.sleep(20);
        }
        pool.shutdown();
    }

    // ==========================================
    // S5.2: Default fake bank behavior matching
    // ==========================================
    @Test
    public void testS5_2_DefaultBankBehaviorMatches() throws Exception {
        fakeBank.resetDefaults();
        fakeBank.submitLatencyMinMs = 50;
        fakeBank.submitLatencyMaxMs = 150;
        fakeBank.callbackDelayMinMs = 100;
        fakeBank.callbackDelayMaxMs = 300;
        fakeBank.noResponseHangMs = 500;

        int total = 30;
        List<String> payoutIds = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + randomPort() + "/mpesa/v1/payouts"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "s52-payout-" + i)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"wallet_id\":\"M001\",\"bank_account\":\"ACC-1\",\"amount\":1000}"))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 202) {
                payoutIds.add(mapper.readTree(resp.body()).path("payout_id").asText());
            }
        }

        // Wait for all to finalize
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = embeddedPostgres.getDatabase("postgres", "mpesa_db").getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payouts WHERE status = 'PENDING' AND wallet_id = 'M001'")) {
                rs.next();
                if (rs.getInt(1) == 0) break;
            }
            Thread.sleep(200);
        }

        // Verify conflict: false
        for (String pid : payoutIds) {
            HttpRequest getPayout = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8081/mpesa/v1/payouts/" + pid))
                    .GET().build();
            HttpResponse<String> pResp = client.send(getPayout, HttpResponse.BodyHandlers.ofString());
            JsonNode pJson = mapper.readTree(pResp.body());
            assertFalse(pJson.path("conflict").asBoolean(), "No payout must have conflict: true");
        }
    }

    // ==========================================
    // S7.1: Statement pagination with concurrent transfers
    // ==========================================
    @Test
    public void testS7_1_StatementCursorConsistency() throws Exception {
        // Seed 100 transfers on W070 first
        for (int i = 0; i < 50; i++) {
            String body = "{\"from_wallet\":\"W070\",\"to_wallet\":\"W071\",\"amount\":1000,\"reference\":\"init-" + i + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8081/mpesa/v1/p2p/transfers"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "init-s71-" + i)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        }

        // Now walk statement pages with limit=25 while concurrent transfers are running
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 50; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    String b = "{\"from_wallet\":\"W070\",\"to_wallet\":\"W072\",\"amount\":500,\"reference\":\"bg-" + idx + "\"}";
                    HttpRequest r = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8082/mpesa/v1/p2p/transfers"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "bg-s71-" + idx)
                            .POST(HttpRequest.BodyPublishers.ofString(b))
                            .build();
                    client.send(r, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {}
            });
        }

        List<JsonNode> allEntries = new ArrayList<>();
        String cursor = null;
        do {
            String url = "http://localhost:8081/mpesa/v1/wallets/W070/statement?limit=25" +
                    (cursor != null ? "&cursor=" + cursor : "");
            HttpRequest getStmt = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> stmtResp = client.send(getStmt, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stmtResp.statusCode());

            JsonNode stmtJson = mapper.readTree(stmtResp.body());
            JsonNode entries = stmtJson.path("entries");
            for (JsonNode entry : entries) {
                allEntries.add(entry);
            }
            cursor = stmtJson.path("next_cursor").asText(null);
            if ("null".equals(cursor) || "".equals(cursor)) {
                cursor = null;
            }
        } while (cursor != null);

        pool.shutdown();

        // Invariants: No entry twice, balance chain holds across pages
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < allEntries.size(); i++) {
            JsonNode e = allEntries.get(i);
            String id = e.path("entry_id").asText();
            assertTrue(seenIds.add(id), "Duplicate entry found in statement: " + id);

            if (i > 0) {
                // Consecutive entries: newer e and older f: f.balance_after + e.amount = e.balance_after
                JsonNode olderF = allEntries.get(i);
                JsonNode newerE = allEntries.get(i - 1);
                long fBalanceAfter = olderF.path("balance_after").asLong();
                long eAmount = newerE.path("amount").asLong();
                long eBalanceAfter = newerE.path("balance_after").asLong();
                assertEquals(eBalanceAfter, fBalanceAfter + eAmount, "Balance chain invariant f.balance_after + e.amount = e.balance_after violated");
            }
        }
    }
}
