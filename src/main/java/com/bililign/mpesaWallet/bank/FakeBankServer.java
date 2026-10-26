package com.bililign.mpesaWallet.bank;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FakeBankServer {
    private final int port;
    private HttpServer server;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final ExecutorService handlerExecutor = Executors.newFixedThreadPool(50);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    // In-memory state
    private final ConcurrentMap<String, PayoutRecord> payouts = new ConcurrentHashMap<>();
    private final AtomicLong totalPaid = new AtomicLong(0);

    // Configurable rates & behaviors (Rule B2)
    public volatile double acceptRate = 0.80;
    public volatile double rejectRate = 0.08;
    public volatile double noResponseRate = 0.12;
    public volatile double acceptedFailRate = 0.05; // 5% of accepted turn to FAILED
    public volatile double duplicateCallbackRate = 0.30; // 30% duplicate 2 or 3 times
    public volatile int submitLatencyMinMs = 2000;
    public volatile int submitLatencyMaxMs = 6000;
    public volatile int getLatencyMinMs = 1000;
    public volatile int getLatencyMaxMs = 3000;
    public volatile int callbackDelayMinMs = 1000;
    public volatile int callbackDelayMaxMs = 10000;
    public volatile int noResponseHangMs = 60000;

    public static class PayoutRecord {
        public final String payoutId;
        public final String bankAccount;
        public final long amount;
        public final String bankRef;
        public volatile String initialResult; // ACCEPTED, REJECTED, NO_RESPONSE
        public volatile String finalStatus; // IN_PROGRESS, SUCCEEDED, FAILED, REJECTED
        public volatile boolean callbackSent = false;

        public PayoutRecord(String payoutId, String bankAccount, long amount, String bankRef) {
            this.payoutId = payoutId;
            this.bankAccount = bankAccount;
            this.amount = amount;
            this.bankRef = bankRef;
            this.finalStatus = "IN_PROGRESS";
        }
    }

    public FakeBankServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(handlerExecutor);

        // Core spec endpoints
        server.createContext("/bank/payouts", new PayoutsHandler());

        // Control & reconciliation endpoints (Rule B2 & B3)
        server.createContext("/bank/total-paid", exchange -> {
            String json = "{\"total_paid\": " + totalPaid.get() + "}";
            sendResponse(exchange, 200, json);
        });

        server.createContext("/bank/config", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                parseAndApplyConfig(body);
                sendResponse(exchange, 200, "{\"status\": \"CONFIG_UPDATED\"}");
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        server.createContext("/bank/reset", exchange -> {
            payouts.clear();
            totalPaid.set(0);
            resetDefaults();
            sendResponse(exchange, 200, "{\"status\": \"RESET_OK\"}");
        });

        server.start();
        System.out.println("Fake Bank Server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        scheduler.shutdownNow();
        handlerExecutor.shutdownNow();
    }

    public long getTotalPaid() {
        return totalPaid.get();
    }

    public void resetDefaults() {
        acceptRate = 0.80;
        rejectRate = 0.08;
        noResponseRate = 0.12;
        acceptedFailRate = 0.05;
        duplicateCallbackRate = 0.30;
        submitLatencyMinMs = 2000;
        submitLatencyMaxMs = 6000;
        getLatencyMinMs = 1000;
        getLatencyMaxMs = 3000;
        callbackDelayMinMs = 1000;
        callbackDelayMaxMs = 10000;
        noResponseHangMs = 60000;
    }

    private void parseAndApplyConfig(String json) {
        Double noResp = extractDouble(json, "no_response_rate");
        if (noResp != null)
            noResponseRate = noResp;
        Double rej = extractDouble(json, "reject_rate");
        if (rej != null)
            rejectRate = rej;
        Double acc = extractDouble(json, "accept_rate");
        if (acc != null)
            acceptRate = acc;
        Double dup = extractDouble(json, "duplicate_callback_rate");
        if (dup != null)
            duplicateCallbackRate = dup;
        Integer minLat = extractInt(json, "submit_latency_min_ms");
        if (minLat != null)
            submitLatencyMinMs = minLat;
        Integer maxLat = extractInt(json, "submit_latency_max_ms");
        if (maxLat != null)
            submitLatencyMaxMs = maxLat;
        Integer hang = extractInt(json, "no_response_hang_ms");
        if (hang != null)
            noResponseHangMs = hang;
    }

    private class PayoutsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("POST".equalsIgnoreCase(method) && "/bank/payouts".equals(path)) {
                handlePostPayout(exchange);
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/bank/payouts/")) {
                handleGetPayout(exchange, path.substring("/bank/payouts/".length()));
            } else {
                sendResponse(exchange, 404, "{\"error\": \"NOT_FOUND\"}");
            }
        }
    }

    private void handlePostPayout(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String payoutId = extractString(body, "payout_id");
        String bankAccount = extractString(body, "bank_account");
        Long amount = extractLong(body, "amount");

        if (payoutId == null || bankAccount == null || amount == null) {
            sendResponse(exchange, 400, "{\"error\": \"INVALID_REQUEST\"}");
            return;
        }

        // Idempotency check on payout_id
        PayoutRecord existing = payouts.get(payoutId);
        if (existing != null) {
            if (existing.amount != amount) {
                // Known id with different amount is rejected
                sendResponse(exchange, 200, "{\"result\": \"REJECTED\", \"reason\": \"AMOUNT_MISMATCH\"}");
                return;
            }
            // Known id returns original result and never pays twice
            if ("REJECTED".equals(existing.initialResult)) {
                sendResponse(exchange, 200, "{\"result\": \"REJECTED\", \"reason\": \"BENEFICIARY_ACCOUNT_INVALID\"}");
                return;
            } else if ("ACCEPTED".equals(existing.initialResult)) {
                sendResponse(exchange, 200, "{\"result\": \"ACCEPTED\", \"bank_ref\": \"" + existing.bankRef + "\"}");
                return;
            } else {
                // Previously hung/timed out
                sleepQuietly(2000);
                sendResponse(exchange, 504, "Gateway Timeout");
                return;
            }
        }

        // Latency simulation: 2 to 6 seconds
        int latency = randomBetween(submitLatencyMinMs, submitLatencyMaxMs);
        String bankRef = "BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        PayoutRecord record = new PayoutRecord(payoutId, bankAccount, amount, bankRef);

        double roll = ThreadLocalRandom.current().nextDouble();

        if (roll < noResponseRate) {
            // 12% get no response: stays open for 60 seconds (or configured hang), then 504
            record.initialResult = "NO_RESPONSE";
            boolean bankDidProcess = ThreadLocalRandom.current().nextBoolean(); // Half processed
            if (bankDidProcess) {
                record.finalStatus = "SUCCEEDED";
                totalPaid.addAndGet(amount);
                // Schedule callback
                scheduleCallback(record, randomBetween(callbackDelayMinMs, callbackDelayMaxMs));
            } else {
                record.finalStatus = "NOT_FOUND";
            }
            payouts.put(payoutId, record);
            sleepQuietly(noResponseHangMs);
            sendResponse(exchange, 504, "Gateway Timeout");
            return;
        }

        roll -= noResponseRate;
        if (roll < rejectRate) {
            // 8% rejected: final, no callback
            record.initialResult = "REJECTED";
            record.finalStatus = "REJECTED";
            payouts.put(payoutId, record);
            sleepQuietly(latency);
            sendResponse(exchange, 200, "{\"result\": \"REJECTED\", \"reason\": \"BENEFICIARY_ACCOUNT_INVALID\"}");
            return;
        }

        // Accepted: money will move, callback arrives later (or early)
        record.initialResult = "ACCEPTED";
        boolean willFail = ThreadLocalRandom.current().nextDouble() < acceptedFailRate;
        if (!willFail) {
            record.finalStatus = "SUCCEEDED";
            totalPaid.addAndGet(amount);
        } else {
            record.finalStatus = "FAILED";
        }
        payouts.put(payoutId, record);

        // Callback delay: 1 to 10 seconds. Notice it can fire BEFORE the submit call
        // finishes!
        int callbackDelay = randomBetween(callbackDelayMinMs, callbackDelayMaxMs);
        scheduleCallback(record, callbackDelay);

        sleepQuietly(latency);
        sendResponse(exchange, 200, "{\"result\": \"ACCEPTED\", \"bank_ref\": \"" + bankRef + "\"}");
    }

    private void handleGetPayout(HttpExchange exchange, String payoutId) throws IOException {
        int latency = randomBetween(getLatencyMinMs, getLatencyMaxMs);
        sleepQuietly(latency);

        PayoutRecord record = payouts.get(payoutId);
        if (record == null) {
            sendResponse(exchange, 200, "{\"status\": \"NOT_FOUND\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"status\": \"" + record.finalStatus + "\"}");
    }

    private void scheduleCallback(PayoutRecord record, int delayMs) {
        scheduler.schedule(() -> {
            String eventId = UUID.randomUUID().toString();
            int copies = 1;
            if (ThreadLocalRandom.current().nextDouble() < duplicateCallbackRate) {
                copies = ThreadLocalRandom.current().nextBoolean() ? 2 : 3;
            }

            for (int i = 0; i < copies; i++) {
                // Sent to either instance (8081 or 8082) at random
                int port = ThreadLocalRandom.current().nextBoolean() ? 8081 : 8082;
                sendCallbackWithRetry(port, eventId, record, 0);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendCallbackWithRetry(int port, String eventId, PayoutRecord record, int retryCount) {
        if (retryCount > 5)
            return;

        String callbackJson = String.format(
                "{\"event_id\": \"%s\", \"payout_id\": \"%s\", \"bank_ref\": \"%s\", \"status\": \"%s\"}",
                eventId, record.payoutId, record.bankRef, record.finalStatus);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mpesa/v1/callbacks/bank"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(callbackJson))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        scheduleRetry(port, eventId, record, retryCount + 1);
                    }
                })
                .exceptionally(ex -> {
                    scheduleRetry(port, eventId, record, retryCount + 1);
                    return null;
                });
    }

    private void scheduleRetry(int port, String eventId, PayoutRecord record, int nextRetry) {
        if (nextRetry <= 5) {
            scheduler.schedule(() -> {
                // Can retry to either port
                int targetPort = ThreadLocalRandom.current().nextBoolean() ? 8081 : 8082;
                sendCallbackWithRetry(targetPort, eventId, record, nextRetry);
            }, 3, TimeUnit.SECONDS);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int randomBetween(int min, int max) {
        if (min >= max)
            return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Long extractLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    private static Double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static Integer extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        FakeBankServer bank = new FakeBankServer(port);
        bank.start();
        Runtime.getRuntime().addShutdownHook(new Thread(bank::stop));
    }
}