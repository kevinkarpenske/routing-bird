package com.jivesoftware.os.routing.bird.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.shared.HostPort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;

/**
 *
 * @author jonathan.colt
 */
public class HttpDeliveryClientHealthProvider implements ClientHealthProvider, Runnable {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final HttpRequestHelper httpRequestHelper;
    private final String path;
    private final long interval;
    private final int sampleWindow;
    private final Map<HostPort, Health> healths = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    public HttpDeliveryClientHealthProvider(HttpRequestHelper httpRequestHelper, String path, long interval, int sampleWindow) {
        this.httpRequestHelper = httpRequestHelper;
        this.path = path;
        this.interval = interval;
        this.sampleWindow = sampleWindow;
    }

    public void start() {
        scheduledExecutorService.scheduleAtFixedRate(this, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void stop() throws InterruptedException {
        if (httpRequestHelper != null) {
            scheduledExecutorService.shutdownNow();
            scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Override
    public ClientHealth get(HostPort hostPort) {
        return healths.computeIfAbsent(hostPort, (t) -> {
            return new Health(t, sampleWindow);
        });
    }

    @Override
    public void run() {
        try {
            List<DeliverableHealth> deliverableHealth = new ArrayList<>();

            for (Health h : healths.values()) {
                for (Map.Entry<String, FamilyStats> familyStats : h.familyStats.entrySet()) {

                    FamilyStats fs = familyStats.getValue();

                    DeliverableLatencyStats latencyStats = new DeliverableLatencyStats(fs.ds.getMean(),
                        fs.ds.getMin(),
                        fs.ds.getMax(),
                        fs.ds.getPercentile(50d),
                        fs.ds.getPercentile(75d),
                        fs.ds.getPercentile(90d),
                        fs.ds.getPercentile(95d),
                        fs.ds.getPercentile(99d));

                    deliverableHealth.add(new DeliverableHealth(h.hostPort,
                        h.timestamp,
                        h.connectivityErrors,
                        h.firstMarkedAsDeadTimestamp,
                        h.lastMarkedAsDeadTimestamp,
                        h.fatalError.get() == null ? "" : ExceptionUtils.getStackTrace(h.fatalError.get()),
                        familyStats.getKey(),
                        fs.attempts.get(),
                        fs.success.get(),
                        fs.attemptsPerSecond,
                        fs.successPerSecond,
                        latencyStats));
                }
            }

            httpRequestHelper.executeRequest(deliverableHealth, path, String.class, null);
        } catch (Exception x) {
            LOG.warn("Failed tp delivery client health.", x);
        }

    }

    static public class DeliverableHealth {

        public HostPort hostPort;
        public long timestamp;
        public long connectivityErrors;
        public long firstMarkedAsDeadTimestamp;
        public long lastMarkedAsDeadTimestamp;
        public String fatalError;

        public String family;
        public long attempt;
        public long success;
        public long attemptPerSecond;
        public long successPerSecond;
        public DeliverableLatencyStats deliverableLatencyStats;

        public DeliverableHealth() {
        }

        public DeliverableHealth(HostPort hostPort, long timestamp, long connectivityErrors, long firstMarkedAsDeadTimestamp, long lastMarkedAsDeadTimestamp,
            String fatalError, String family, long attempt, long success, long attemptPerSecond, long successPerSecond,
            DeliverableLatencyStats deliverableLatencyStats) {
            this.hostPort = hostPort;
            this.timestamp = timestamp;
            this.connectivityErrors = connectivityErrors;
            this.firstMarkedAsDeadTimestamp = firstMarkedAsDeadTimestamp;
            this.lastMarkedAsDeadTimestamp = lastMarkedAsDeadTimestamp;
            this.fatalError = fatalError;
            this.family = family;
            this.attempt = attempt;
            this.success = success;
            this.attemptPerSecond = attemptPerSecond;
            this.successPerSecond = successPerSecond;
            this.deliverableLatencyStats = deliverableLatencyStats;
        }

    }

    static public class DeliverableLatencyStats {

        public double latencyMean;
        public double latencyMin;
        public double latencyMax;
        public double latency50th;
        public double latency75th;
        public double latency95th;
        public double latency99th;
        public double latency999th;

        public DeliverableLatencyStats() {
        }

        public DeliverableLatencyStats(double latencyMean, double latencyMin, double latencyMax, double latency50th, double latency75th, double latency95th,
            double latency99th, double latency999th) {
            this.latencyMean = latencyMean;
            this.latencyMin = latencyMin;
            this.latencyMax = latencyMax;
            this.latency50th = latency50th;
            this.latency75th = latency75th;
            this.latency95th = latency95th;
            this.latency99th = latency99th;
            this.latency999th = latency999th;
        }
    }

    static class Health implements ClientHealth {

        HostPort hostPort;
        long lastDeliveryTimestamp = System.currentTimeMillis();
        long timestamp;
        long connectivityErrors = 0;
        long firstMarkedAsDeadTimestamp = -1;
        long lastMarkedAsDeadTimestamp = -1;
        AtomicReference<Exception> fatalError = new AtomicReference<>();
        Map<String, FamilyStats> familyStats = new ConcurrentHashMap<>();
        int sampleWindow;

        public Health(HostPort hostPort, int sampleWindow) {
            this.hostPort = hostPort;
            this.sampleWindow = sampleWindow;
        }

        @Override
        public void attempt(String family) {
            long currentTimeMillis = System.currentTimeMillis();
            FamilyStats stats = familyStats.computeIfAbsent(family, (String t) -> {
                return new FamilyStats(sampleWindow);
            });
            stats.attempt(currentTimeMillis);
        }

        @Override
        public void success(String family, long latency) {
            long currentTimeMillis = System.currentTimeMillis();
            firstMarkedAsDeadTimestamp = -1;
            lastMarkedAsDeadTimestamp = -1;
            connectivityErrors = 0;
            fatalError.set(null);
            FamilyStats stats = familyStats.computeIfAbsent(family, (String t) -> {
                return new FamilyStats(sampleWindow);
            });
            stats.success(currentTimeMillis, latency);
            timestamp = currentTimeMillis;
        }

        @Override
        public void markedDead() {
            long currentTimeMillis = System.currentTimeMillis();
            firstMarkedAsDeadTimestamp = currentTimeMillis;
            timestamp = currentTimeMillis;
        }

        @Override
        public void connectivityError() {
            connectivityErrors++;
            timestamp = System.currentTimeMillis();
        }

        @Override
        public void fatalError(Exception x) {
            fatalError.set(x);
            timestamp = System.currentTimeMillis();
        }

        @Override
        public void stillDead() {
            long currentTimeMillis = System.currentTimeMillis();
            lastMarkedAsDeadTimestamp = currentTimeMillis;
            timestamp = currentTimeMillis;
        }

    }

    static class FamilyStats {

        final AtomicLong attempts = new AtomicLong(0);
        final AtomicLong success = new AtomicLong(0);
        final DescriptiveStatistics ds;
        long lastSecond;
        long attemptsPerSecond;
        long successPerSecond;

        FamilyStats(int window) {
            ds = new DescriptiveStatistics(window);
        }

        public void attempt(long timestamp) {
            long second = timestamp / 1000;
            if (lastSecond != second) {
                lastSecond = second;
                attemptsPerSecond = 0;
            }
            attemptsPerSecond++;

            attempts.incrementAndGet();
        }

        public void success(long timestamp, long latency) {
            long second = timestamp / 1000;
            if (lastSecond != second) {
                lastSecond = second;
                successPerSecond = 0;
            }
            successPerSecond++;

            success.incrementAndGet();
            ds.addValue(latency);
        }

    }

    static HttpRequestHelper buildRequestHelper(String host, int port) {
        HttpClientConfig httpClientConfig = HttpClientConfig.newBuilder().build();
        HttpClientFactory httpClientFactory = new HttpClientFactoryProvider().createHttpClientFactory(Arrays.<HttpClientConfiguration>asList(httpClientConfig));
        HttpClient httpClient = httpClientFactory.createClient(host, port);
        HttpRequestHelper requestHelper = new HttpRequestHelper(httpClient, new ObjectMapper());
        return requestHelper;
    }
}