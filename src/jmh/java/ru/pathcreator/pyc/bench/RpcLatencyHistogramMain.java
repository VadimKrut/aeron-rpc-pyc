package ru.pathcreator.pyc.bench;

import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import ru.pathcreator.pyc.ChannelConfig;
import ru.pathcreator.pyc.IdleStrategyKind;
import ru.pathcreator.pyc.NodeConfig;
import ru.pathcreator.pyc.RawRequestHandler;
import ru.pathcreator.pyc.RpcChannel;
import ru.pathcreator.pyc.RpcNode;
import ru.pathcreator.pyc.codec.MessageCodec;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone rate-controlled RTT benchmark for comparing aeron-rpc with raw Aeron echo benchmarks.
 */
public final class RpcLatencyHistogramMain {

    private static final int REQUEST_TYPE = 1;
    private static final int RESPONSE_TYPE = 2;
    private static final ByteArrayCodec CODEC = new ByteArrayCodec();

    private static volatile int sink;

    private RpcLatencyHistogramMain() {
    }

    public static void main(final String[] args) throws Exception {
        Logger.getLogger("ru.pathcreator.pyc").setLevel(Level.WARNING);

        final Options options = Options.parse(args);
        final byte[] request = payload(options.payloadSize);

        printConfiguration(options, System.out);

        try (BenchmarkContext context = BenchmarkContext.start(options)) {
            System.out.printf(
                    Locale.ROOT,
                    "Running warmup for %d iterations of %,d messages each, with %d bytes payload, %,d total msg/s, %d caller thread(s), and burst size %d...%n%n",
                    options.warmupIterations,
                    options.warmupMessages,
                    options.payloadSize,
                    options.rate,
                    options.threads,
                    options.burstSize);

            for (int i = 0; i < options.warmupIterations; i++) {
                runPhase(context.client, request, options, options.warmupMessages, false);
            }

            System.out.printf(
                    Locale.ROOT,
                    "Running measurement for %d iterations of %,d messages each, with %d bytes payload, %,d total msg/s, %d caller thread(s), and burst size %d...%n%n",
                    options.measurementIterations,
                    options.measurementMessages,
                    options.payloadSize,
                    options.rate,
                    options.threads,
                    options.burstSize);

            final Histogram result = newHistogram(options);
            long elapsedNs = 0L;
            for (int i = 0; i < options.measurementIterations; i++) {
                final PhaseResult phase = runPhase(context.client, request, options, options.measurementMessages, true);
                result.add(phase.histogram());
                elapsedNs += phase.elapsedNs();
            }
            printReport(options, result, elapsedNs);
        }
    }

    private static void printConfiguration(final Options options, final PrintStream out) {
        out.println("aeron-rpc RTT latency benchmark");
        out.println();
        out.println("Mode: synchronous closed-loop RPC echo, one in-flight request per caller thread.");
        out.println("Server handler: raw echo handler; client path uses MessageCodec<byte[]>.");
        out.println("Latency source: System.nanoTime around RpcChannel.call(...).");
        out.println("Histogram: HdrHistogram, values recorded in nanoseconds, printed in microseconds.");
        out.println();
        out.printf(Locale.ROOT, "Payload:              %,d bytes%n", options.payloadSize);
        out.printf(Locale.ROOT, "Target total rate:    %,d msg/s%n", options.rate);
        out.printf(Locale.ROOT, "Caller threads:       %,d%n", options.threads);
        out.printf(Locale.ROOT, "Target rate/thread:   %,.2f msg/s%n", options.rate / (double) options.threads);
        out.printf(Locale.ROOT, "Burst size/thread:    %,d%n", options.burstSize);
        out.printf(Locale.ROOT, "Handler mode:         %s%n", options.handlerMode);
        out.printf(Locale.ROOT, "RX idle strategy:     %s%n", options.idleStrategy);
        out.printf(Locale.ROOT, "Max tracked latency:  %,d us%n", TimeUnit.NANOSECONDS.toMicros(options.highestTrackableNs));
        out.printf(Locale.ROOT, "Significant digits:   %,d%n", options.significantDigits);
        out.println();
    }

    private static PhaseResult runPhase(
            final RpcChannel client,
            final byte[] request,
            final Options options,
            final int messages,
            final boolean record
    ) throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(options.threads, new BenchmarkThreadFactory());
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<WorkerResult>> futures = new ArrayList<>(options.threads);
        final int baseMessages = messages / options.threads;
        final int remainder = messages % options.threads;

        for (int workerIndex = 0; workerIndex < options.threads; workerIndex++) {
            final int workerMessages = baseMessages + (workerIndex < remainder ? 1 : 0);
            futures.add(executor.submit(new Worker(client, request, options, workerMessages, record, start)));
        }

        final long startedAt = System.nanoTime();
        start.countDown();

        final Histogram aggregate = newHistogram(options);
        int phaseSink = 0;
        try {
            for (final Future<WorkerResult> future : futures) {
                final WorkerResult workerResult = future.get();
                aggregate.add(workerResult.histogram());
                phaseSink ^= workerResult.sink();
            }
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("benchmark worker pool did not stop in time");
            }
        }

        sink ^= phaseSink;
        return new PhaseResult(aggregate, System.nanoTime() - startedAt);
    }

    private static Histogram newHistogram(final Options options) {
        return new Histogram(options.highestTrackableNs, options.significantDigits);
    }

    private static void waitUntil(final long deadlineNs) {
        while (System.nanoTime() < deadlineNs) {
            Thread.onSpinWait();
        }
    }

    private static void printReport(final Options options, final Histogram histogram, final long elapsedNs) {
        final double achievedRate = histogram.getTotalCount() / (elapsedNs / 1_000_000_000.0);

        System.out.println("Histogram of aeron-rpc RTT latencies in MICROSECONDS.");
        System.out.println();
        System.out.printf(
                Locale.ROOT,
                "Histogram [aeron-rpc-udp-loopback-%db-rate=%d-threads=%d-burst=%d-handler=%s-idle=%s]:%n",
                options.payloadSize,
                options.rate,
                options.threads,
                options.burstSize,
                options.handlerMode,
                options.idleStrategy);
        histogram.outputPercentileDistribution(System.out, 1_000.0);
        System.out.println();
        System.out.printf(Locale.ROOT, "#[Mean    = %12.3f, StdDeviation   = %12.3f]%n", histogram.getMean() / 1_000.0, histogram.getStdDeviation() / 1_000.0);
        System.out.printf(Locale.ROOT, "#[Min     = %12.3f, Max            = %12.3f]%n", histogram.getMinValue() / 1_000.0, histogram.getMaxValue() / 1_000.0);
        System.out.printf(Locale.ROOT, "#[p50     = %12.3f, p90            = %12.3f]%n", histogram.getValueAtPercentile(50.0) / 1_000.0, histogram.getValueAtPercentile(90.0) / 1_000.0);
        System.out.printf(Locale.ROOT, "#[p99     = %12.3f, p99.9          = %12.3f]%n", histogram.getValueAtPercentile(99.0) / 1_000.0, histogram.getValueAtPercentile(99.9) / 1_000.0);
        System.out.printf(Locale.ROOT, "#[p99.99  = %12.3f, p99.999        = %12.3f]%n", histogram.getValueAtPercentile(99.99) / 1_000.0, histogram.getValueAtPercentile(99.999) / 1_000.0);
        System.out.printf(Locale.ROOT, "#[Count   = %12d, Achieved rate   = %12.0f ops/s]%n", histogram.getTotalCount(), achievedRate);
        System.out.printf(Locale.ROOT, "#[Sink    = %12d]%n", sink);
    }

    private static byte[] payload(final int size) {
        final byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        return payload;
    }

    private record PhaseResult(Histogram histogram, long elapsedNs) {
    }

    private record WorkerResult(Histogram histogram, int sink) {
    }

    private static final class Worker implements Callable<WorkerResult> {
        private final RpcChannel client;
        private final byte[] request;
        private final Options options;
        private final int messages;
        private final boolean record;
        private final CountDownLatch start;

        private Worker(
                final RpcChannel client,
                final byte[] request,
                final Options options,
                final int messages,
                final boolean record,
                final CountDownLatch start
        ) {
            this.client = client;
            this.request = request;
            this.options = options;
            this.messages = messages;
            this.record = record;
            this.start = start;
        }

        @Override
        public WorkerResult call() throws Exception {
            final Histogram histogram = newHistogram(options);
            final long intervalNs = burstIntervalNs(options);
            long nextBurstNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1);
            int localSink = 0;
            int sent = 0;

            start.await();

            while (sent < messages) {
                waitUntil(nextBurstNs);

                final int burstLimit = Math.min(options.burstSize, messages - sent);
                for (int i = 0; i < burstLimit; i++) {
                    final long startedAt = System.nanoTime();
                    final byte[] response = client.call(
                            REQUEST_TYPE,
                            RESPONSE_TYPE,
                            request,
                            CODEC,
                            CODEC,
                            5,
                            TimeUnit.SECONDS);
                    final long latencyNs = System.nanoTime() - startedAt;

                    validateResponse(request, response);
                    localSink ^= response[0];
                    localSink ^= response[response.length - 1];

                    if (record) {
                        histogram.recordValue(latencyNs);
                    }
                    sent++;
                }

                nextBurstNs += intervalNs;
            }

            return new WorkerResult(histogram, localSink);
        }

        private static long burstIntervalNs(final Options options) {
            final double interval = 1_000_000_000.0 * options.threads * options.burstSize / options.rate;
            return Math.max(1L, Math.round(interval));
        }

        private static void validateResponse(final byte[] request, final byte[] response) {
            if (response.length != request.length) {
                throw new IllegalStateException("unexpected response length: " + response.length);
            }
            if (response[0] != request[0] || response[response.length - 1] != request[request.length - 1]) {
                throw new IllegalStateException("unexpected response payload");
            }
        }
    }

    private static final class BenchmarkThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, "aeron-rpc-latency-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class BenchmarkContext implements AutoCloseable {
        private final RpcNode node;
        private final RpcChannel client;

        private BenchmarkContext(final RpcNode node, final RpcChannel client) {
            this.node = node;
            this.client = client;
        }

        static BenchmarkContext start(final Options options) throws InterruptedException {
            final String aeronDir = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "aeron-rpc-latency-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000)
            ).toAbsolutePath().toString();

            final RpcNode node = RpcNode.start(NodeConfig.builder()
                    .aeronDir(aeronDir)
                    .embeddedDriver(true)
                    .build());

            final int basePort = 30_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel client = node.channel(config(
                    "localhost:" + basePort,
                    "localhost:" + (basePort + 1),
                    streamId,
                    options));
            final RpcChannel server = node.channel(config(
                    "localhost:" + (basePort + 1),
                    "localhost:" + basePort,
                    streamId,
                    options));

            server.onRaw(REQUEST_TYPE, RESPONSE_TYPE, echoHandler());
            server.start();
            client.start();
            waitConnected(client, server);

            return new BenchmarkContext(node, client);
        }

        @Override
        public void close() {
            node.close();
        }

        private static ChannelConfig config(
                final String localEndpoint,
                final String remoteEndpoint,
                final int streamId,
                final Options options
        ) {
            final ChannelConfig.Builder builder = ChannelConfig.builder()
                    .localEndpoint(localEndpoint)
                    .remoteEndpoint(remoteEndpoint)
                    .streamId(streamId)
                    .defaultTimeout(Duration.ofSeconds(5))
                    .offerTimeout(Duration.ofSeconds(3))
                    .heartbeatInterval(Duration.ofMillis(50))
                    .heartbeatMissedLimit(10)
                    .rxIdleStrategy(options.idleStrategy)
                    .pendingPoolCapacity(65_536)
                    .registryInitialCapacity(65_536)
                    .offloadTaskPoolSize(65_536)
                    .offloadCopyPoolSize(8_192)
                    .offloadCopyBufferSize(Math.max(8 * 1024, options.payloadSize));

            if (options.handlerMode == HandlerMode.DIRECT) {
                builder.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR);
            }
            return builder.build();
        }

        private static RawRequestHandler echoHandler() {
            return (requestBuffer, requestOffset, requestLength, responseBuffer, responseOffset, responseCapacity) -> {
                responseBuffer.putBytes(responseOffset, requestBuffer, requestOffset, requestLength);
                return requestLength;
            };
        }

        private static void waitConnected(final RpcChannel client, final RpcChannel server) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (client.isConnected() && server.isConnected()) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("RPC benchmark channels did not connect in time");
        }
    }

    private enum HandlerMode {
        DIRECT,
        OFFLOAD
    }

    private static final class Options {
        private int payloadSize = 32;
        private int rate = 100_000;
        private int threads = 1;
        private int burstSize = 1;
        private int warmupIterations = 5;
        private int warmupMessages = 25_000;
        private int measurementIterations = 10;
        private int measurementMessages = 100_000;
        private long highestTrackableNs = TimeUnit.SECONDS.toNanos(60);
        private int significantDigits = 3;
        private HandlerMode handlerMode = HandlerMode.DIRECT;
        private IdleStrategyKind idleStrategy = IdleStrategyKind.YIELDING;

        static Options parse(final String[] args) {
            final Options options = new Options();
            for (final String arg : args) {
                final int split = arg.indexOf('=');
                if (!arg.startsWith("--") || split < 0) {
                    throw new IllegalArgumentException("expected --key=value argument, got: " + arg);
                }
                final String key = arg.substring(2, split);
                final String value = arg.substring(split + 1);
                switch (key) {
                    case "payload" -> options.payloadSize = Integer.parseInt(value);
                    case "rate" -> options.rate = Integer.parseInt(value);
                    case "threads" -> options.threads = Integer.parseInt(value);
                    case "burst-size" -> options.burstSize = Integer.parseInt(value);
                    case "warmup-iterations" -> options.warmupIterations = Integer.parseInt(value);
                    case "warmup-messages" -> options.warmupMessages = Integer.parseInt(value);
                    case "measurement-iterations" -> options.measurementIterations = Integer.parseInt(value);
                    case "measurement-messages" -> options.measurementMessages = Integer.parseInt(value);
                    case "highest-trackable-us" ->
                            options.highestTrackableNs = TimeUnit.MICROSECONDS.toNanos(Long.parseLong(value));
                    case "significant-digits" -> options.significantDigits = Integer.parseInt(value);
                    case "handler" -> options.handlerMode = HandlerMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "idle" -> options.idleStrategy = IdleStrategyKind.valueOf(value.toUpperCase(Locale.ROOT));
                    default -> throw new IllegalArgumentException("unknown argument: " + key);
                }
            }
            validate(options);
            return options;
        }

        private static void validate(final Options options) {
            if (options.payloadSize <= 0) {
                throw new IllegalArgumentException("payload must be positive");
            }
            if (options.rate <= 0) {
                throw new IllegalArgumentException("rate must be positive");
            }
            if (options.threads <= 0) {
                throw new IllegalArgumentException("threads must be positive");
            }
            if (options.burstSize <= 0) {
                throw new IllegalArgumentException("burst-size must be positive");
            }
            if (options.warmupIterations < 0) {
                throw new IllegalArgumentException("warmup-iterations must be zero or positive");
            }
            if (options.warmupMessages <= 0) {
                throw new IllegalArgumentException("warmup-messages must be positive");
            }
            if (options.measurementIterations <= 0) {
                throw new IllegalArgumentException("measurement-iterations must be positive");
            }
            if (options.measurementMessages <= 0) {
                throw new IllegalArgumentException("measurement-messages must be positive");
            }
            if (options.highestTrackableNs <= 0) {
                throw new IllegalArgumentException("highest-trackable-us must be positive");
            }
            if (options.significantDigits < 1 || options.significantDigits > 5) {
                throw new IllegalArgumentException("significant-digits must be between 1 and 5");
            }
        }
    }

    private static final class ByteArrayCodec implements MessageCodec<byte[]> {
        @Override
        public int encode(final byte[] message, final MutableDirectBuffer buffer, final int offset) {
            buffer.putBytes(offset, message);
            return message.length;
        }

        @Override
        public byte[] decode(final DirectBuffer buffer, final int offset, final int length) {
            final byte[] decoded = new byte[length];
            buffer.getBytes(offset, decoded);
            return decoded;
        }
    }
}