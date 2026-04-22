package ru.pathcreator.pyc.rpc.core.bench;

import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.IdleStrategyKind;
import ru.pathcreator.pyc.rpc.core.NodeConfig;
import ru.pathcreator.pyc.rpc.core.RawRequestHandler;
import ru.pathcreator.pyc.rpc.core.RpcChannel;
import ru.pathcreator.pyc.rpc.core.RpcChannelListener;
import ru.pathcreator.pyc.rpc.core.RpcNode;
import ru.pathcreator.pyc.rpc.core.ReconnectStrategy;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone rate-controlled RTT benchmark for comparing rpc-core with raw Aeron echo benchmarks.
 */
public final class RpcLatencyHistogramMain {

    private static final int REQUEST_TYPE = 1;
    private static final int RESPONSE_TYPE = 2;
    private static final ByteArrayCodec CODEC = new ByteArrayCodec();
    private static final RpcChannelListener BENCHMARK_LISTENER = new RpcChannelListener() {
    };

    private static volatile int sink;

    private RpcLatencyHistogramMain() {
    }

    public static void main(final String[] args) throws Exception {
        Logger.getLogger("ru.pathcreator.pyc.rpc.core").setLevel(Level.WARNING);

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
                runPhase(context.clients, request, options, options.warmupMessages, false);
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
                final PhaseResult phase = runPhase(context.clients, request, options, options.measurementMessages, true);
                result.add(phase.histogram());
                elapsedNs += phase.elapsedNs();
            }
            printReport(options, result, elapsedNs);
        }
    }

    private static void printConfiguration(final Options options, final PrintStream out) {
        out.println("rpc-core RTT latency benchmark");
        out.println();
        out.println("Mode: synchronous closed-loop RPC echo, one in-flight request per caller thread.");
        out.println("Server handler: raw echo handler; client path uses MessageCodec<byte[]>.");
        out.println("Latency source: System.nanoTime around RpcChannel.call(...).");
        out.println("Histogram: HdrHistogram, values recorded in nanoseconds, printed in microseconds.");
        out.println();
        out.printf(Locale.ROOT, "Payload:              %,d bytes%n", options.payloadSize);
        out.printf(Locale.ROOT, "Target total rate:    %,d msg/s%n", options.rate);
        out.printf(Locale.ROOT, "Caller threads:       %,d%n", options.threads);
        out.printf(Locale.ROOT, "Client channels:      %,d%n", options.channels);
        out.printf(Locale.ROOT, "RX poller threads:    %,d%n", options.rxPollerThreads);
        out.printf(Locale.ROOT, "RX fragment limit:    %,d%n", options.rxPollerFragmentLimit);
        out.printf(Locale.ROOT, "Target rate/thread:   %,.2f msg/s%n", options.rate / (double) options.threads);
        out.printf(Locale.ROOT, "Burst size/thread:    %,d%n", options.burstSize);
        out.printf(Locale.ROOT, "Handler mode:         %s%n", options.handlerMode);
        out.printf(Locale.ROOT, "Offload executor:     %s%n", options.offloadExecutorMode);
        out.printf(Locale.ROOT, "Handler IO delay:     %,d ns%n", options.handlerIoNanos);
        out.printf(Locale.ROOT, "RX idle strategy:     %s%n", options.idleStrategy);
        out.printf(Locale.ROOT, "Protocol handshake:   %s%n", options.protocolHandshakeEnabled);
        out.printf(Locale.ROOT, "Listeners enabled:    %s%n", options.listenersEnabled);
        out.printf(Locale.ROOT, "Reconnect strategy:   %s%n", options.reconnectStrategy);
        out.printf(Locale.ROOT, "Max tracked latency:  %,d us%n", TimeUnit.NANOSECONDS.toMicros(options.highestTrackableNs));
        out.printf(Locale.ROOT, "Significant digits:   %,d%n", options.significantDigits);
        out.println();
    }

    private static PhaseResult runPhase(
            final RpcChannel[] clients,
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
            futures.add(executor.submit(new Worker(
                    clients[workerIndex % clients.length],
                    request,
                    options,
                    workerMessages,
                    record,
                    start)));
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

    private static void printReport(
            final Options options,
            final Histogram histogram,
            final long elapsedNs
    ) {
        final double achievedRate = histogram.getTotalCount() / (elapsedNs / 1_000_000_000.0);

        System.out.println("Histogram of rpc-core RTT latencies in MICROSECONDS.");
        System.out.println();
        System.out.printf(
                Locale.ROOT,
                "Histogram [rpc-core-udp-loopback-%db-rate=%d-threads=%d-channels=%d-rxPollers=%d-burst=%d-handler=%s-ioNs=%d-idle=%s-handshake=%s-listeners=%s-reconnect=%s]:%n",
                options.payloadSize,
                options.rate,
                options.threads,
                options.channels,
                options.rxPollerThreads,
                options.burstSize,
                options.handlerMode,
                options.handlerIoNanos,
                options.idleStrategy,
                options.protocolHandshakeEnabled,
                options.listenersEnabled,
                options.reconnectStrategy);
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
            final Thread thread = new Thread(task, "rpc-core-latency-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class BenchmarkOffloadThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, "rpc-core-bench-offload-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class ChannelAffineExecutor extends AbstractExecutorService {
        private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final Thread worker;
        private volatile boolean shutdown;

        private ChannelAffineExecutor(final String threadName) {
            this.worker = new Thread(this::runLoop, threadName);
            this.worker.setDaemon(true);
            this.worker.start();
        }

        @Override
        public void shutdown() {
            shutdown = true;
            worker.interrupt();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            worker.interrupt();
            final List<Runnable> drained = new ArrayList<>();
            queue.drainTo(drained);
            return drained;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && !worker.isAlive();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            worker.join(unit.toMillis(timeout));
            return !worker.isAlive();
        }

        @Override
        public void execute(final Runnable command) {
            if (shutdown) {
                throw new IllegalStateException("channel-affine executor is shut down");
            }
            queue.add(command);
        }

        private void runLoop() {
            while (!shutdown || !queue.isEmpty()) {
                final Runnable task;
                try {
                    task = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (final InterruptedException ie) {
                    if (shutdown) {
                        break;
                    }
                    continue;
                }
                if (task != null) {
                    task.run();
                }
            }
        }
    }

    private static final class BenchmarkOffloadExecutors implements AutoCloseable {
        private final ExecutorService shared;
        private final ChannelAffineExecutor[] affine;

        private BenchmarkOffloadExecutors(final ExecutorService shared, final ChannelAffineExecutor[] affine) {
            this.shared = shared;
            this.affine = affine;
        }

        private static BenchmarkOffloadExecutors create(final Options options) {
            if (options.handlerMode != HandlerMode.OFFLOAD) {
                return new BenchmarkOffloadExecutors(null, null);
            }
            return switch (options.offloadExecutorMode) {
                case FIXED -> new BenchmarkOffloadExecutors(
                        Executors.newFixedThreadPool(
                                Math.max(1, Math.min(options.channels, Runtime.getRuntime().availableProcessors())),
                                new BenchmarkOffloadThreadFactory()),
                        null);
                case CHANNEL_AFFINE -> {
                    final int workers = Math.max(1, Math.min(options.channels, Runtime.getRuntime().availableProcessors()));
                    final ChannelAffineExecutor[] executors = new ChannelAffineExecutor[workers];
                    for (int i = 0; i < workers; i++) {
                        executors[i] = new ChannelAffineExecutor("rpc-core-bench-affine-" + (i + 1));
                    }
                    yield new BenchmarkOffloadExecutors(null, executors);
                }
                case VIRTUAL -> new BenchmarkOffloadExecutors(null, null);
            };
        }

        private ExecutorService forChannel(final int channelIndex) {
            if (shared != null) {
                return shared;
            }
            if (affine != null) {
                return affine[Math.floorMod(channelIndex, affine.length)];
            }
            return null;
        }

        @Override
        public void close() {
            if (shared != null) {
                shared.shutdown();
                try {
                    shared.awaitTermination(10, TimeUnit.SECONDS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (affine != null) {
                for (final ChannelAffineExecutor executor : affine) {
                    executor.shutdown();
                }
                for (final ChannelAffineExecutor executor : affine) {
                    try {
                        executor.awaitTermination(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private static final class BenchmarkContext implements AutoCloseable {
        private final RpcNode node;
        private final RpcChannel[] clients;
        private final BenchmarkOffloadExecutors benchmarkOffloadExecutors;

        private BenchmarkContext(
                final RpcNode node,
                final RpcChannel[] clients,
                final BenchmarkOffloadExecutors benchmarkOffloadExecutors
        ) {
            this.node = node;
            this.clients = clients;
            this.benchmarkOffloadExecutors = benchmarkOffloadExecutors;
        }

        static BenchmarkContext start(final Options options) throws InterruptedException {
            final String aeronDir = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "rpc-core-latency-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000)
            ).toAbsolutePath().toString();

            final RpcNode node = RpcNode.start(NodeConfig.builder()
                    .aeronDir(aeronDir)
                    .embeddedDriver(true)
                    .sharedReceivePollerThreads(options.rxPollerThreads)
                    .sharedReceivePollerFragmentLimit(options.rxPollerFragmentLimit)
                    .build());
            final BenchmarkOffloadExecutors benchmarkOffloadExecutors = BenchmarkOffloadExecutors.create(options);

            final int basePort = 30_000 + ThreadLocalRandom.current().nextInt(5_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel[] clients = new RpcChannel[options.channels];
            final RpcChannel[] servers = new RpcChannel[options.channels];

            for (int i = 0; i < options.channels; i++) {
                final int channelBasePort = basePort + i * 2;
                clients[i] = node.channel(config(
                        "localhost:" + channelBasePort,
                        "localhost:" + (channelBasePort + 1),
                        streamId + i,
                        options,
                        benchmarkOffloadExecutors.forChannel(i)));
                servers[i] = node.channel(config(
                        "localhost:" + (channelBasePort + 1),
                        "localhost:" + channelBasePort,
                        streamId + i,
                        options,
                        benchmarkOffloadExecutors.forChannel(i)));
                servers[i].onRaw(REQUEST_TYPE, RESPONSE_TYPE, echoHandler(options));
            }

            for (final RpcChannel server : servers) {
                server.start();
            }
            for (final RpcChannel client : clients) {
                client.start();
            }
            waitConnected(clients, servers);
            primeOptionalFeatures(clients, options);

            return new BenchmarkContext(node, clients, benchmarkOffloadExecutors);
        }

        @Override
        public void close() {
            node.close();
            benchmarkOffloadExecutors.close();
        }

        private static ChannelConfig config(
                final String localEndpoint,
                final String remoteEndpoint,
                final int streamId,
                final Options options,
                final ExecutorService benchmarkOffloadExecutor
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
                    .reconnectStrategy(options.reconnectStrategy)
                    .protocolHandshakeEnabled(options.protocolHandshakeEnabled)
                    .protocolVersion(1)
                    .pendingPoolCapacity(65_536)
                    .registryInitialCapacity(65_536)
                    .offloadTaskPoolSize(65_536)
                    .offloadCopyPoolSize(8_192)
                    .offloadCopyBufferSize(Math.max(8 * 1024, options.payloadSize))
                    .offloadExecutionStatePoolingEnabled(options.offloadExecutionStatePoolingEnabled)
                    .offloadExecutionStatePoolSize(options.offloadExecutionStatePoolSize)
                    .offloadExecutionStatePoolGrowthChunk(options.offloadExecutionStatePoolGrowthChunk);

            if (options.handlerMode == HandlerMode.DIRECT) {
                builder.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR);
            } else if (benchmarkOffloadExecutor != null) {
                builder.offloadExecutor(benchmarkOffloadExecutor);
            }
            if (options.listenersEnabled) {
                builder.listener(BENCHMARK_LISTENER);
            }
            return builder.build();
        }

        private static RawRequestHandler echoHandler(final Options options) {
            return (requestBuffer, requestOffset, requestLength, responseBuffer, responseOffset, responseCapacity) -> {
                if (options.handlerIoNanos > 0) {
                    LockSupport.parkNanos(options.handlerIoNanos);
                }
                responseBuffer.putBytes(responseOffset, requestBuffer, requestOffset, requestLength);
                return requestLength;
            };
        }

        private static void waitConnected(final RpcChannel[] clients, final RpcChannel[] servers) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                boolean connected = true;
                for (final RpcChannel client : clients) {
                    connected &= client.isConnected();
                }
                for (final RpcChannel server : servers) {
                    connected &= server.isConnected();
                }
                if (connected) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("RPC benchmark channels did not connect in time");
        }

        private static void primeOptionalFeatures(final RpcChannel[] clients, final Options options) {
            if (!options.protocolHandshakeEnabled) {
                return;
            }
            final byte[] request = payload(options.payloadSize);
            for (final RpcChannel client : clients) {
                final byte[] response = client.call(
                        REQUEST_TYPE,
                        RESPONSE_TYPE,
                        request,
                        CODEC,
                        CODEC,
                        5,
                        TimeUnit.SECONDS);
                Worker.validateResponse(request, response);
                sink ^= response[0];
                sink ^= response[response.length - 1];
            }
        }
    }

    private enum HandlerMode {
        DIRECT,
        OFFLOAD
    }

    private enum OffloadExecutorMode {
        VIRTUAL,
        FIXED,
        CHANNEL_AFFINE
    }

    private static final class Options {
        private int payloadSize = 32;
        private int rate = 100_000;
        private int threads = 1;
        private int channels = 1;
        private int rxPollerThreads = 4;
        private int rxPollerFragmentLimit = 16;
        private int burstSize = 1;
        private long handlerIoNanos = 0;
        private int warmupIterations = 5;
        private int warmupMessages = 25_000;
        private int measurementIterations = 10;
        private int measurementMessages = 100_000;
        private long highestTrackableNs = TimeUnit.SECONDS.toNanos(60);
        private int significantDigits = 3;
        private HandlerMode handlerMode = HandlerMode.OFFLOAD;
        private OffloadExecutorMode offloadExecutorMode = OffloadExecutorMode.VIRTUAL;
        private boolean offloadExecutionStatePoolingEnabled = true;
        private int offloadExecutionStatePoolSize = 1024;
        private int offloadExecutionStatePoolGrowthChunk = 128;
        private IdleStrategyKind idleStrategy = IdleStrategyKind.YIELDING;
        private boolean protocolHandshakeEnabled = false;
        private boolean listenersEnabled = false;
        private ReconnectStrategy reconnectStrategy = ReconnectStrategy.FAIL_FAST;

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
                    case "channels" -> options.channels = Integer.parseInt(value);
                    case "rx-poller-threads" -> options.rxPollerThreads = Integer.parseInt(value);
                    case "rx-poller-fragment-limit" -> options.rxPollerFragmentLimit = Integer.parseInt(value);
                    case "burst-size" -> options.burstSize = Integer.parseInt(value);
                    case "handler-io-nanos" -> options.handlerIoNanos = Long.parseLong(value);
                    case "handler-io-micros" ->
                            options.handlerIoNanos = TimeUnit.MICROSECONDS.toNanos(Long.parseLong(value));
                    case "warmup-iterations" -> options.warmupIterations = Integer.parseInt(value);
                    case "warmup-messages" -> options.warmupMessages = Integer.parseInt(value);
                    case "measurement-iterations" -> options.measurementIterations = Integer.parseInt(value);
                    case "measurement-messages" -> options.measurementMessages = Integer.parseInt(value);
                    case "highest-trackable-us" ->
                            options.highestTrackableNs = TimeUnit.MICROSECONDS.toNanos(Long.parseLong(value));
                    case "significant-digits" -> options.significantDigits = Integer.parseInt(value);
                    case "handler" -> options.handlerMode = HandlerMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "offload-executor" ->
                            options.offloadExecutorMode = OffloadExecutorMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "offload-state-pool-enabled" ->
                            options.offloadExecutionStatePoolingEnabled = Boolean.parseBoolean(value);
                    case "offload-state-pool-size" -> options.offloadExecutionStatePoolSize = Integer.parseInt(value);
                    case "offload-state-pool-growth-chunk" ->
                            options.offloadExecutionStatePoolGrowthChunk = Integer.parseInt(value);
                    case "idle" -> options.idleStrategy = IdleStrategyKind.valueOf(value.toUpperCase(Locale.ROOT));
                    case "protocol-handshake" -> options.protocolHandshakeEnabled = Boolean.parseBoolean(value);
                    case "listeners" -> options.listenersEnabled = Boolean.parseBoolean(value);
                    case "reconnect" ->
                            options.reconnectStrategy = ReconnectStrategy.valueOf(value.toUpperCase(Locale.ROOT));
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
            if (options.channels <= 0) {
                throw new IllegalArgumentException("channels must be positive");
            }
            if (options.rxPollerThreads <= 0) {
                throw new IllegalArgumentException("rx-poller-threads must be positive");
            }
            if (options.rxPollerFragmentLimit <= 0) {
                throw new IllegalArgumentException("rx-poller-fragment-limit must be positive");
            }
            if (options.burstSize <= 0) {
                throw new IllegalArgumentException("burst-size must be positive");
            }
            if (options.handlerIoNanos < 0) {
                throw new IllegalArgumentException("handler-io-nanos must be zero or positive");
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
            if (options.offloadExecutionStatePoolSize < 0) {
                throw new IllegalArgumentException("offload-state-pool-size must be zero or positive");
            }
            if (options.offloadExecutionStatePoolGrowthChunk <= 0) {
                throw new IllegalArgumentException("offload-state-pool-growth-chunk must be positive");
            }
            if (!options.offloadExecutionStatePoolingEnabled && options.offloadExecutionStatePoolSize > 0) {
                throw new IllegalArgumentException("offload-state-pool-size requires offload-state-pool-enabled=true");
            }
        }
    }

    private static final class ByteArrayCodec implements MessageCodec<byte[]> {
        private final ThreadLocal<byte[]> decodeBuffer = ThreadLocal.withInitial(() -> new byte[0]);

        @Override
        public int encode(final byte[] message, final MutableDirectBuffer buffer, final int offset) {
            buffer.putBytes(offset, message);
            return message.length;
        }

        @Override
        public byte[] decode(final DirectBuffer buffer, final int offset, final int length) {
            byte[] decoded = decodeBuffer.get();
            if (decoded.length < length) {
                decoded = new byte[length];
                decodeBuffer.set(decoded);
            }
            buffer.getBytes(offset, decoded);
            return decoded;
        }
    }
}