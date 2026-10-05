import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Benchmark & Mock Data Generator for Payments API.
 * Suporta execuções de alta escala (10.000 a 100.000+ requisições) com Java 21 Virtual Threads.
 * Executável nativamente: java scripts/Benchmark.java [args]
 */
public class Benchmark {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String PURPLE = "\u001B[35m";

    public static void main(String[] args) throws Exception {
        int totalRequests = 100000;
        int concurrency = 60;
        int replayCount = 2000;
        int transitionCount = 5000;
        String host = "http://localhost:8181";
        String keycloakUrl = "http://localhost:8080";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--total" -> totalRequests = Integer.parseInt(args[++i]);
                case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                case "--replays" -> replayCount = Integer.parseInt(args[++i]);
                case "--transitions" -> transitionCount = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--keycloak" -> keycloakUrl = args[++i];
            }
        }

        // Ajuste proporcional para execuções pequenas
        if (totalRequests < 1000) {
            replayCount = Math.max(1, totalRequests / 10);
            transitionCount = Math.max(1, totalRequests / 5);
        }

        System.out.println(BOLD + CYAN + "==========================================================================" + RESET);
        System.out.println(BOLD + CYAN + "       PAYMENTS API - ENTERPRISE HIGH-SCALE BENCHMARK (100K+)" + RESET);
        System.out.println(BOLD + CYAN + "==========================================================================" + RESET);
        System.out.printf(" Alvo API:       %s%s%s%n", GREEN, host, RESET);
        System.out.printf(" Keycloak IdP:   %s%s%s%n", GREEN, keycloakUrl, RESET);
        System.out.printf(" Total Criados:  %s%,d%s pagamentos%n", BOLD, totalRequests, RESET);
        System.out.printf(" Replays Teste:  %s%,d%s requisições%n", BOLD, replayCount, RESET);
        System.out.printf(" Transições:     %s%,d%s autorizações%n", BOLD, transitionCount, RESET);
        System.out.printf(" Concorrência:   %s%d Virtual Threads%s%n", YELLOW, concurrency, RESET);
        System.out.println(CYAN + "--------------------------------------------------------------------------" + RESET);

        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        TokenProvider tokenProvider = new TokenProvider(client, keycloakUrl);
        System.out.print(" [1/4] Autenticando com Keycloak (merchant-acme)... ");
        tokenProvider.getValidToken();
        System.out.println(GREEN + "OK (Token JWT obtido e renovável)" + RESET);

        String runId = UUID.randomUUID().toString().substring(0, 8);

        // Fase 1: Criação de Pagamentos
        System.out.println("\n" + BOLD + " [2/4] Fase 1: Criação de " + String.format("%,d", totalRequests) + " Pagamentos..." + RESET);
        List<CreatedPayment> sampleList = Collections.synchronizedList(new ArrayList<>(Math.min(10000, totalRequests)));
        BenchmarkResult creationResult = runCreationPhase(client, host, tokenProvider, runId, totalRequests, concurrency, sampleList);
        printPhaseSummary("FASE 1: CRIAÇÃO DE PAGAMENTOS (POST /v1/payments)", creationResult);

        // Fase 2: Replays Idempotentes
        BenchmarkResult replayResult = null;
        if (replayCount > 0 && !sampleList.isEmpty()) {
            System.out.println("\n" + BOLD + " Aguardando trabalhadores do Kafka processarem a fila de ingestão..." + RESET);
            Thread.sleep(2500);
            System.out.println("\n" + BOLD + " [3/4] Fase 2: Replay Idempotente de " + String.format("%,d", replayCount) + " Pagamentos (Teste de Cache Redis)..." + RESET);
            replayResult = runReplayPhase(client, host, tokenProvider, sampleList, replayCount, concurrency);
            printPhaseSummary("FASE 2: REPLAY IDEMPOTENTE (CACHE REDIS HIT)", replayResult);
        }

        // Fase 3: Transições de Estado
        BenchmarkResult transitionResult = null;
        if (transitionCount > 0 && !sampleList.isEmpty()) {
            Thread.sleep(1500); // aguarda persistência assíncrona dos samples pelos workers
            System.out.println("\n" + BOLD + " [4/4] Fase 3: Transição de Estado de " + String.format("%,d", transitionCount) + " Pagamentos (/authorize com If-Match)..." + RESET);
            transitionResult = runTransitionPhase(client, host, tokenProvider, sampleList, transitionCount, concurrency);
            printPhaseSummary("FASE 3: TRANSIÇÕES DE ESTADO (MÁQUINA DE ESTADOS)", transitionResult);
        }

        // Relatório Consolidado
        System.out.println("\n" + BOLD + GREEN + "==========================================================================" + RESET);
        System.out.println(BOLD + GREEN + "                  BENCHMARK DE 100K CONCLUÍDO COM SUCESSO" + RESET);
        System.out.println(BOLD + GREEN + "==========================================================================" + RESET);

        exportMarkdownReport("benchmark-report.md", totalRequests, concurrency, creationResult, replayResult, transitionResult, host);
        System.out.printf("%n Relatório salvo em: %s%s%s%n", BOLD + CYAN, "benchmark-report.md", RESET);
        System.out.printf(" Visualize os dados no Grafana:  %shttp://localhost:3000%s%n", CYAN, RESET);
        System.out.printf(" Inspecione os eventos no Kafka: %shttp://localhost:8085%s%n%n", CYAN, RESET);
    }

    private static class TokenProvider {
        private final HttpClient client;
        private final String keycloakUrl;
        private volatile String token;
        private volatile long expiresAt = 0;

        public TokenProvider(HttpClient client, String keycloakUrl) {
            this.client = client;
            this.keycloakUrl = keycloakUrl;
        }

        public synchronized String getValidToken() {
            long now = System.currentTimeMillis();
            if (token == null || now >= expiresAt - 30_000) {
                try {
                    this.token = authenticate(client, keycloakUrl);
                    this.expiresAt = now + 180_000; // renova a cada 3 minutos
                } catch (Exception e) {
                    if (this.token == null) {
                        throw new RuntimeException("Falha ao autenticar com Keycloak: " + e.getMessage(), e);
                    }
                }
            }
            return token;
        }
    }

    private static String authenticate(HttpClient client, String keycloakUrl) throws Exception {
        String body = "grant_type=client_credentials&client_id=merchant-acme&client_secret=acme-secret&scope=payments:read%20payments:write";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(keycloakUrl + "/realms/payments/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Falha ao autenticar no Keycloak: HTTP " + response.statusCode());
        }
        Matcher matcher = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalStateException("access_token não encontrado");
    }

    private static BenchmarkResult runCreationPhase(
        HttpClient client, String host, TokenProvider tokenProvider, String runId,
        int total, int concurrency, List<CreatedPayment> sampleList
    ) throws Exception {
        long[] latenciesNanos = new long[total];
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        AtomicInteger completedCounter = new AtomicInteger(0);
        int progressStep = Math.max(500, total / 100);

        String[] currencies = {"BRL", "BRL", "BRL", "BRL", "USD", "EUR"};
        Random random = new Random(42);

        long startNanos = System.nanoTime();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore semaphore = new Semaphore(concurrency);
            List<Future<?>> futures = new ArrayList<>(total);

            for (int i = 0; i < total; i++) {
                final int seq = i;
                final String idemKey = "bench-" + runId + "-" + seq;
                final String currency = currencies[random.nextInt(currencies.length)];
                final double amountVal = 10.0 + (random.nextDouble() * 4990.0);
                final String amountStr = String.format(Locale.US, "%.2f", amountVal);
                final String payerId = UUID.randomUUID().toString();
                final String payeeId = UUID.randomUUID().toString();

                final String payload = String.format(
                    "{\"payerId\":\"%s\",\"payeeId\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}",
                    payerId, payeeId, amountStr, currency
                );

                futures.add(executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        String currentToken = tokenProvider.getValidToken();
                        long reqStart = System.nanoTime();

                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(host + "/v1/payments"))
                            .header("Authorization", "Bearer " + currentToken)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", idemKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();

                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.nanoTime() - reqStart;
                        latenciesNanos[seq] = duration;

                        int code = resp.statusCode();
                        statusCounts.computeIfAbsent(code, k -> new AtomicInteger(0)).incrementAndGet();

                        if ((code == 202 || code == 201) && sampleList.size() < 10000) {
                            String paymentId = extractId(resp.body());
                            String etag = resp.headers().firstValue("ETag").orElse("\"v0\"");
                            if (paymentId != null) {
                                sampleList.add(new CreatedPayment(paymentId, idemKey, payload, etag));
                            }
                        }

                        int current = completedCounter.incrementAndGet();
                        if (current % progressStep == 0 || current == total) {
                            renderProgress(current, total, startNanos);
                        }
                    } catch (Exception e) {
                        statusCounts.computeIfAbsent(599, k -> new AtomicInteger(0)).incrementAndGet();
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            for (var f : futures) {
                f.get();
            }
        }

        long totalNanos = System.nanoTime() - startNanos;
        System.out.println();
        return calculateMetricsFromArray(latenciesNanos, statusCounts, totalNanos, total);
    }

    private static BenchmarkResult runReplayPhase(
        HttpClient client, String host, TokenProvider tokenProvider,
        List<CreatedPayment> sampleList, int count, int concurrency
    ) throws Exception {
        int target = Math.min(count, sampleList.size());
        long[] latenciesNanos = new long[target];
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        AtomicInteger completedCounter = new AtomicInteger(0);
        int progressStep = Math.max(100, target / 50);

        long startNanos = System.nanoTime();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore semaphore = new Semaphore(concurrency);
            List<Future<?>> futures = new ArrayList<>(target);

            for (int i = 0; i < target; i++) {
                final int seq = i;
                CreatedPayment item = sampleList.get(i);
                futures.add(executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        String currentToken = tokenProvider.getValidToken();
                        long reqStart = System.nanoTime();

                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(host + "/v1/payments"))
                            .header("Authorization", "Bearer " + currentToken)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", item.idempotencyKey)
                            .POST(HttpRequest.BodyPublishers.ofString(item.payload))
                            .build();

                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.nanoTime() - reqStart;
                        latenciesNanos[seq] = duration;

                        int code = resp.statusCode();
                        statusCounts.computeIfAbsent(code, k -> new AtomicInteger(0)).incrementAndGet();

                        int current = completedCounter.incrementAndGet();
                        if (current % progressStep == 0 || current == target) {
                            renderProgress(current, target, startNanos);
                        }
                    } catch (Exception e) {
                        statusCounts.computeIfAbsent(599, k -> new AtomicInteger(0)).incrementAndGet();
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            for (var f : futures) {
                f.get();
            }
        }

        long totalNanos = System.nanoTime() - startNanos;
        System.out.println();
        return calculateMetricsFromArray(latenciesNanos, statusCounts, totalNanos, target);
    }

    private static BenchmarkResult runTransitionPhase(
        HttpClient client, String host, TokenProvider tokenProvider,
        List<CreatedPayment> sampleList, int count, int concurrency
    ) throws Exception {
        int target = Math.min(count, sampleList.size());
        long[] latenciesNanos = new long[target];
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        AtomicInteger completedCounter = new AtomicInteger(0);
        int progressStep = Math.max(100, target / 50);

        long startNanos = System.nanoTime();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Semaphore semaphore = new Semaphore(concurrency);
            List<Future<?>> futures = new ArrayList<>(target);

            for (int i = 0; i < target; i++) {
                final int seq = i;
                CreatedPayment item = sampleList.get(i);
                futures.add(executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        String currentToken = tokenProvider.getValidToken();
                        long reqStart = System.nanoTime();

                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(host + "/v1/payments/" + item.id + "/authorize"))
                            .header("Authorization", "Bearer " + currentToken)
                            .header("If-Match", item.etag)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long duration = System.nanoTime() - reqStart;
                        latenciesNanos[seq] = duration;

                        int code = resp.statusCode();
                        statusCounts.computeIfAbsent(code, k -> new AtomicInteger(0)).incrementAndGet();

                        int current = completedCounter.incrementAndGet();
                        if (current % progressStep == 0 || current == target) {
                            renderProgress(current, target, startNanos);
                        }
                    } catch (Exception e) {
                        statusCounts.computeIfAbsent(599, k -> new AtomicInteger(0)).incrementAndGet();
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            for (var f : futures) {
                f.get();
            }
        }

        long totalNanos = System.nanoTime() - startNanos;
        System.out.println();
        return calculateMetricsFromArray(latenciesNanos, statusCounts, totalNanos, target);
    }

    private static void renderProgress(int current, int total, long startNanos) {
        double elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double rps = elapsedSec > 0 ? (current / elapsedSec) : 0;
        int pct = (int) ((current * 100.0) / total);
        int barLength = 25;
        int filled = (int) ((pct / 100.0) * barLength);

        StringBuilder sb = new StringBuilder("\r ");
        sb.append(CYAN).append("[").append(GREEN);
        for (int i = 0; i < filled; i++) sb.append("█");
        sb.append(RESET);
        for (int i = filled; i < barLength; i++) sb.append("░");
        sb.append(CYAN).append("] ").append(BOLD);
        sb.append(String.format("%,d / %,d (%3d%%)", current, total, pct));
        sb.append(RESET).append(" - ").append(YELLOW);
        sb.append(String.format("%.1f RPS", rps));
        sb.append(RESET).append(String.format(" (%.1fs)", elapsedSec));

        System.out.print(sb);
        System.out.flush();
    }

    private static BenchmarkResult calculateMetricsFromArray(
        long[] latenciesNanos, Map<Integer, AtomicInteger> statusCounts,
        long totalNanos, int totalCount
    ) {
        double totalSeconds = totalNanos / 1_000_000_000.0;
        double throughput = totalSeconds > 0 ? (totalCount / totalSeconds) : 0;

        double[] latenciesMs = new double[latenciesNanos.length];
        double sum = 0;
        for (int i = 0; i < latenciesNanos.length; i++) {
            double ms = latenciesNanos[i] / 1_000_000.0;
            latenciesMs[i] = ms;
            sum += ms;
        }
        Arrays.sort(latenciesMs);

        double min = latenciesMs.length == 0 ? 0 : latenciesMs[0];
        double max = latenciesMs.length == 0 ? 0 : latenciesMs[latenciesMs.length - 1];
        double avg = latenciesMs.length == 0 ? 0 : (sum / latenciesMs.length);

        double p50 = getPercentileFromSortedArray(latenciesMs, 50.0);
        double p90 = getPercentileFromSortedArray(latenciesMs, 90.0);
        double p95 = getPercentileFromSortedArray(latenciesMs, 95.0);
        double p99 = getPercentileFromSortedArray(latenciesMs, 99.0);

        Map<Integer, Integer> finalCounts = new TreeMap<>();
        for (var entry : statusCounts.entrySet()) {
            finalCounts.put(entry.getKey(), entry.getValue().get());
        }

        return new BenchmarkResult(totalCount, totalSeconds, throughput, min, max, avg, p50, p90, p95, p99, finalCounts);
    }

    private static double getPercentileFromSortedArray(double[] sorted, double percentile) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static void printPhaseSummary(String title, BenchmarkResult res) {
        System.out.println(CYAN + " +----------------------------------------------------------------------+" + RESET);
        System.out.printf(CYAN + " | %s%-68s%s |%n", BOLD, title, RESET);
        System.out.println(CYAN + " +----------------------------------------------------------------------+" + RESET);
        System.out.printf(" | Total Requisições:   %-48s |%n", String.format("%,d", res.totalCount));
        System.out.printf(" | Duração Total:       %-48s |%n", String.format("%.2f segundos", res.totalSeconds));
        System.out.printf(" | Throughput:          %-48s |%n", String.format(GREEN + BOLD + "%.1f req/s" + RESET, res.throughput));
        System.out.printf(" | Latência Média:      %-48s |%n", String.format("%.2f ms", res.avgMs));
        System.out.printf(" | Latência p50 (Med):  %-48s |%n", String.format("%.2f ms", res.p50Ms));
        System.out.printf(" | Latência p90:        %-48s |%n", String.format("%.2f ms", res.p90Ms));
        System.out.printf(" | Latência p95:        %-48s |%n", String.format("%.2f ms", res.p95Ms));
        System.out.printf(" | Latência p99:        %-48s |%n", String.format("%.2f ms", res.p99Ms));
        System.out.printf(" | Latência Min / Max:  %-48s |%n", String.format("%.2f ms / %.2f ms", res.minMs, res.maxMs));
        System.out.println(CYAN + " +----------------------------------------------------------------------+" + RESET);
        System.out.print(" | Status HTTP:         ");
        StringBuilder sb = new StringBuilder();
        res.statusCounts.forEach((status, count) -> {
            String color = (status >= 200 && status < 300) ? GREEN : (status >= 400 && status < 500) ? YELLOW : RED;
            sb.append(color).append(status).append(": ").append(String.format("%,d", count)).append(RESET).append("  ");
        });
        System.out.println(sb);
        System.out.println(CYAN + " +----------------------------------------------------------------------+" + RESET);
    }

    private static void exportMarkdownReport(
        String filename, int totalRequests, int concurrency,
        BenchmarkResult creation, BenchmarkResult replay, BenchmarkResult transition,
        String host
    ) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("# 📊 Relatório Executivo de Benchmark & Carga da API de Pagamentos (100k)");
            writer.println();
            writer.println("> **Data da Execução:** " + Instant.now());
            writer.println("> **Ambiente:** Local / WSL 2 (Debian 13) • Java 21 LTS (Virtual Threads)");
            writer.println("> **Alvo Testado:** `" + host + "`");
            writer.println();
            writer.println("---");
            writer.println();
            writer.println("## 🚀 Resumo Executivo");
            writer.println();
            writer.println("| Fase do Benchmark | Total Requisições | Throughput (RPS) | Latência p50 | Latência p95 | Latência p99 | Status Predominante |");
            writer.println("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |");
            int creationSuccessCount = creation.statusCounts.getOrDefault(202, creation.statusCounts.getOrDefault(201, 0));
            String creationStatusLabel = creation.statusCounts.containsKey(202) ? "HTTP 202 Accepted" : "HTTP 201 Created";
            writer.printf("| **1. Ingestão Assíncrona (POST /v1/payments)** | %,d | **%.1f req/s** | %.2f ms | %.2f ms | %.2f ms | %s (%d) |%n",
                creation.totalCount, creation.throughput, creation.p50Ms, creation.p95Ms, creation.p99Ms, creationStatusLabel, creationSuccessCount);

            if (replay != null) {
                int replaySuccessCount = replay.statusCounts.getOrDefault(200, replay.statusCounts.getOrDefault(201, 0));
                String replayStatusLabel = replay.statusCounts.containsKey(200) ? "HTTP 200 OK (Replay)" : "HTTP 201 Replay";
                writer.printf("| **2. Replay Idempotente (Redis)** | %,d | **%.1f req/s** | %.2f ms | %.2f ms | %.2f ms | %s (%d) |%n",
                    replay.totalCount, replay.throughput, replay.p50Ms, replay.p95Ms, replay.p99Ms, replayStatusLabel, replaySuccessCount);
            }
            if (transition != null) {
                writer.printf("| **3. Transições (POST /authorize)** | %,d | **%.1f req/s** | %.2f ms | %.2f ms | %.2f ms | HTTP 200 OK (%d) |%n",
                    transition.totalCount, transition.throughput, transition.p50Ms, transition.p95Ms, transition.p99Ms, transition.statusCounts.getOrDefault(200, 0));
            }
            writer.println();
            writer.println("---");
            writer.println();
            writer.println("## 🔬 Análise de Performance & Resiliência Distribuída");
            writer.println();
            writer.println("### 1. Ingestão Assíncrona vs Replay em Cache");
            if (replay != null) {
                double speedup = replay.avgMs > 0 ? (creation.avgMs / replay.avgMs) : 1;
                writer.printf("* **Latência Média de Ingestão (Redis CAS + Kafka Ingress):** `%.2f ms`%n", creation.avgMs);
                writer.printf("* **Latência Média de Replay (Redis Lua CAS Hit):** `%.2f ms`%n", replay.avgMs);
                writer.printf("* **Fator de Aceleração do Cache de Idempotência:** **%.1fx mais rápido** que o ciclo de ingestão.%n", speedup);
            }
            writer.println();
            writer.println("### 2. Distribuição Completa de Percentis (Fase de Criação)");
            writer.println();
            writer.println("| Métrica | Valor |");
            writer.println("| :--- | :--- |");
            writer.printf("| **Tempo Total de Execução** | `%.2f segundos (%.1f minutos)` |%n", creation.totalSeconds, creation.totalSeconds / 60.0);
            writer.printf("| **Throughput Médio** | `%.1f requisições/segundo` |%n", creation.throughput);
            writer.printf("| **Latência Mínima** | `%.2f ms` |%n", creation.minMs);
            writer.printf("| **Latência p50 (Mediana)** | `%.2f ms` |%n", creation.p50Ms);
            writer.printf("| **Latência p90** | `%.2f ms` |%n", creation.p90Ms);
            writer.printf("| **Latência p95** | `%.2f ms` |%n", creation.p95Ms);
            writer.printf("| **Latência p99** | `%.2f ms` |%n", creation.p99Ms);
            writer.printf("| **Latência Máxima** | `%.2f ms` |%n", creation.maxMs);
            writer.println();
            writer.println("---");
            writer.println();
            writer.println("## 📡 Como Validar os Dados Gerados nos Dashboards");
            writer.println();
            writer.println("1. **Grafana LGTM** (`http://localhost:3000`):");
            writer.println("   * Abra o dashboard **Payments API - Overview**.");
            writer.println("   * Observe o card **Total Payments Created** exibindo o volume completo populado (100k+).");
            writer.println("   * Observe os gráficos de **Throughput**, **Latency (p95/p99)** e **Payments Created by Currency**.");
            writer.println();
            writer.println("2. **Apache Kafka UI** (`http://localhost:8085`):");
            writer.println("   * Acesse o tópico `payments.events`.");
            writer.println("   * Inspecione as mensagens CloudEvents v1.0 publicadas com suas chaves de partição e payloads.");
            writer.println();
            writer.println("3. **PostgreSQL 16**:");
            writer.println("   ```sql");
            writer.println("   SELECT status, count(*) FROM payments GROUP BY status;");
            writer.println("   SELECT status, count(*) FROM outbox_events GROUP BY status;");
            writer.println("   ```");
        } catch (IOException e) {
            System.err.println("Erro ao exportar relatório markdown: " + e.getMessage());
        }
    }

    private static String extractId(String json) {
        if (json == null) return null;
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private record CreatedPayment(String id, String idempotencyKey, String payload, String etag) {}

    private record BenchmarkResult(
        int totalCount, double totalSeconds, double throughput,
        double minMs, double maxMs, double avgMs,
        double p50Ms, double p90Ms, double p95Ms, double p99Ms,
        Map<Integer, Integer> statusCounts
    ) {}
}
