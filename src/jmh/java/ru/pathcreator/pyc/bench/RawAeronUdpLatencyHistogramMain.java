package ru.pathcreator.pyc.bench;

import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.ConcurrentPublication;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.BufferClaim;
import org.HdrHistogram.Histogram;
import org.agrona.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.CloseHelper;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.PrintStream;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone raw Aeron UDP RTT benchmark with the same rate-control shape as RpcLatencyHistogramMain.
 */
public final class RawAeronUdpLatencyHistogramMain {

    private static final int SEQUENCE_OFFSET = 0;
    private static final int WORKER_ID_OFFSET = Long.BYTES;
    private static final int PAYLOAD_OFFSET = Long.BYTES + Integer.BYTES;
    private static final int FRAGMENT_LIMIT = 128;

    private static volatile int sink;

    private RawAeronUdpLatencyHistogramMain() {
    }

    public static void main(final String[] args) throws Exception {
        final Options options = Options.parse(args);
        final byte[] payload = payload(options.payloadSize);

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
                runPhase(context, payload, options, options.warmupMessages, false);
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
                final PhaseResult phase = runPhase(context, payload, options, options.measurementMessages, true);
                result.add(phase.histogram());
                elapsedNs += phase.elapsedNs();
            }
            printReport(options, result, elapsedNs);
        }
    }

    private static void printConfiguration(final Options options, final PrintStream out) {
        out.println("raw Aeron UDP RTT latency benchmark");
        out.println();
        out.println("Mode: synchronous closed-loop raw Aeron UDP echo, one in-flight request per caller thread.");
        out.println("Server handler: raw echo loop; no RPC envelope, no codec, no pending map.");
        out.println("Latency source: System.nanoTime around publication send + response wait.");
        out.println("Histogram: HdrHistogram, values recorded in nanoseconds, printed in microseconds.");
        out.println();
        out.printf(Locale.ROOT, "Payload:              %,d bytes%n", options.payloadSize);
        out.printf(Locale.ROOT, "Target total rate:    %,d msg/s%n", options.rate);
        out.printf(Locale.ROOT, "Caller threads:       %,d%n", options.threads);
        out.printf(Locale.ROOT, "Target rate/thread:   %,.2f msg/s%n", options.rate / (double) options.threads);
        out.printf(Locale.ROOT, "Burst size/thread:    %,d%n", options.burstSize);
        out.printf(Locale.ROOT, "Media drivers:        %,d%n", options.drivers);
        out.printf(Locale.ROOT, "Driver threading:     %s%n", options.driverThreading);
        out.printf(Locale.ROOT, "Send mode:            %s%n", options.sendMode);
        out.printf(Locale.ROOT, "Wait idle strategy:   %s%n", options.idleStrategy);
        out.printf(Locale.ROOT, "Max tracked latency:  %,d us%n", TimeUnit.NANOSECONDS.toMicros(options.highestTrackableNs));
        out.printf(Locale.ROOT, "Significant digits:   %,d%n", options.significantDigits);
        out.println();
    }

    private static PhaseResult runPhase(
            final BenchmarkContext context,
            final byte[] payload,
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
                    workerIndex,
                    context.requestPublication,
                    context.pendingCalls[workerIndex],
                    payload,
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

    private static void printReport(final Options options, final Histogram histogram, final long elapsedNs) {
        final double achievedRate = histogram.getTotalCount() / (elapsedNs / 1_000_000_000.0);

        System.out.println("Histogram of raw Aeron UDP RTT latencies in MICROSECONDS.");
        System.out.println();
        System.out.printf(
                Locale.ROOT,
                "Histogram [raw-aeron-udp-loopback-%db-rate=%d-threads=%d-burst=%d-drivers=%d-threading=%s-send=%s-idle=%s]:%n",
                options.payloadSize,
                options.rate,
                options.threads,
                options.burstSize,
                options.drivers,
                options.driverThreading,
                options.sendMode,
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

    private static Histogram newHistogram(final Options options) {
        return new Histogram(options.highestTrackableNs, options.significantDigits);
    }

    private static byte[] payload(final int size) {
        final byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        return payload;
    }

    private static void waitUntil(final long deadlineNs) {
        while (System.nanoTime() < deadlineNs) {
            Thread.onSpinWait();
        }
    }

    private static void send(
            final ConcurrentPublication publication,
            final UnsafeBuffer buffer,
            final BufferClaim claim,
            final int length,
            final SendMode mode,
            final IdleStrategy idle
    ) {
        idle.reset();
        if (mode == SendMode.TRY_CLAIM && length <= publication.maxPayloadLength()) {
            while (true) {
                final long result = publication.tryClaim(length, claim);
                if (result > 0) {
                    final MutableDirectBuffer claimedBuffer = claim.buffer();
                    claimedBuffer.putBytes(claim.offset(), buffer, 0, length);
                    claim.commit();
                    return;
                }
                idle.idle(0);
            }
        }

        while (publication.offer(buffer, 0, length) < 0) {
            idle.idle(0);
        }
    }

    private record PhaseResult(Histogram histogram, long elapsedNs) {
    }

    private record WorkerResult(Histogram histogram, int sink) {
    }

    private static final class Worker implements Callable<WorkerResult> {
        private final int workerId;
        private final ConcurrentPublication publication;
        private final PendingCall pendingCall;
        private final byte[] payload;
        private final Options options;
        private final int messages;
        private final boolean record;
        private final CountDownLatch start;
        private final UnsafeBuffer requestBuffer;
        private final BufferClaim claim = new BufferClaim();

        private Worker(
                final int workerId,
                final ConcurrentPublication publication,
                final PendingCall pendingCall,
                final byte[] payload,
                final Options options,
                final int messages,
                final boolean record,
                final CountDownLatch start
        ) {
            this.workerId = workerId;
            this.publication = publication;
            this.pendingCall = pendingCall;
            this.payload = payload;
            this.options = options;
            this.messages = messages;
            this.record = record;
            this.start = start;
            this.requestBuffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(PAYLOAD_OFFSET + payload.length, 128));
        }

        @Override
        public WorkerResult call() throws Exception {
            final Histogram histogram = newHistogram(options);
            final IdleStrategy offerIdle = createIdleStrategy(options.idleStrategy);
            final IdleStrategy waitIdle = createIdleStrategy(options.idleStrategy);
            final long intervalNs = burstIntervalNs(options);
            long nextBurstNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1);
            long sequence = 0L;
            int localSink = 0;
            int sent = 0;

            requestBuffer.putInt(WORKER_ID_OFFSET, workerId);
            requestBuffer.putBytes(PAYLOAD_OFFSET, payload);

            start.await();

            while (sent < messages) {
                waitUntil(nextBurstNs);

                final int burstLimit = Math.min(options.burstSize, messages - sent);
                for (int i = 0; i < burstLimit; i++) {
                    sequence++;
                    pendingCall.sequence = sequence;
                    pendingCall.completed = false;
                    requestBuffer.putLong(SEQUENCE_OFFSET, sequence);

                    final long startedAt = System.nanoTime();
                    send(publication, requestBuffer, claim, PAYLOAD_OFFSET + payload.length, options.sendMode, offerIdle);

                    waitIdle.reset();
                    while (!pendingCall.completed) {
                        waitIdle.idle(0);
                    }
                    final long latencyNs = System.nanoTime() - startedAt;

                    validateResponse(payload, pendingCall.response);
                    localSink ^= pendingCall.response[0];
                    localSink ^= pendingCall.response[pendingCall.response.length - 1];

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

        private static void validateResponse(final byte[] expected, final byte[] actual) {
            if (actual.length != expected.length) {
                throw new IllegalStateException("unexpected response length: " + actual.length);
            }
            if (actual[0] != expected[0] || actual[actual.length - 1] != expected[expected.length - 1]) {
                throw new IllegalStateException("unexpected response payload");
            }
        }
    }

    private static final class PendingCall {
        private final byte[] response;
        private volatile long sequence;
        private volatile boolean completed;

        private PendingCall(final int payloadSize) {
            this.response = new byte[payloadSize];
        }
    }

    private static final class BenchmarkThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, "raw-aeron-udp-latency-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class BenchmarkContext implements AutoCloseable {
        private final MediaDriver clientDriver;
        private final MediaDriver serverDriver;
        private final Aeron clientAeron;
        private final Aeron serverAeron;
        private final Subscription clientSubscription;
        private final ConcurrentPublication requestPublication;
        private final Subscription serverSubscription;
        private final ConcurrentPublication responsePublication;
        private final PendingCall[] pendingCalls;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread clientRxThread;
        private final Thread serverThread;

        private BenchmarkContext(
                final MediaDriver clientDriver,
                final MediaDriver serverDriver,
                final Aeron clientAeron,
                final Aeron serverAeron,
                final Subscription clientSubscription,
                final ConcurrentPublication requestPublication,
                final Subscription serverSubscription,
                final ConcurrentPublication responsePublication,
                final PendingCall[] pendingCalls,
                final Options options
        ) {
            this.clientDriver = clientDriver;
            this.serverDriver = serverDriver;
            this.clientAeron = clientAeron;
            this.serverAeron = serverAeron;
            this.clientSubscription = clientSubscription;
            this.requestPublication = requestPublication;
            this.serverSubscription = serverSubscription;
            this.responsePublication = responsePublication;
            this.pendingCalls = pendingCalls;
            this.clientRxThread = new Thread(() -> clientRxLoop(options), "raw-aeron-udp-client-rx");
            this.serverThread = new Thread(() -> serverLoop(options), "raw-aeron-udp-server");
        }

        static BenchmarkContext start(final Options options) throws InterruptedException {
            final String root = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "raw-aeron-udp-latency-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000)
            ).toAbsolutePath().toString();

            final String clientDir = Path.of(root, "client").toString();
            final String serverDir = options.drivers == 1 ? clientDir : Path.of(root, "server").toString();

            final MediaDriver clientDriver = launchDriver(clientDir, options);
            final MediaDriver serverDriver = options.drivers == 1 ? null : launchDriver(serverDir, options);

            final Aeron clientAeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(clientDriver.aeronDirectoryName()));
            final Aeron serverAeron = options.drivers == 1 ?
                    clientAeron :
                    Aeron.connect(new Aeron.Context().aeronDirectoryName(serverDriver.aeronDirectoryName()));

            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(5_000);
            final int streamId = 5_001 + ThreadLocalRandom.current().nextInt(1_000);
            final String clientEndpoint = "localhost:" + basePort;
            final String serverEndpoint = "localhost:" + (basePort + 1);

            final Subscription clientSubscription = clientAeron.addSubscription(channel(clientEndpoint), streamId);
            final Subscription serverSubscription = serverAeron.addSubscription(channel(serverEndpoint), streamId);
            final ConcurrentPublication requestPublication = clientAeron.addPublication(channel(serverEndpoint), streamId);
            final ConcurrentPublication responsePublication = serverAeron.addPublication(channel(clientEndpoint), streamId);

            final PendingCall[] pendingCalls = new PendingCall[options.threads];
            for (int i = 0; i < pendingCalls.length; i++) {
                pendingCalls[i] = new PendingCall(options.payloadSize);
            }

            final BenchmarkContext context = new BenchmarkContext(
                    clientDriver,
                    serverDriver,
                    clientAeron,
                    serverAeron,
                    clientSubscription,
                    requestPublication,
                    serverSubscription,
                    responsePublication,
                    pendingCalls,
                    options);
            context.clientRxThread.setDaemon(true);
            context.serverThread.setDaemon(true);
            context.clientRxThread.start();
            context.serverThread.start();
            context.waitConnected();
            return context;
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            clientRxThread.join(TimeUnit.SECONDS.toMillis(5));
            serverThread.join(TimeUnit.SECONDS.toMillis(5));
            if (serverAeron != clientAeron) {
                CloseHelper.closeAll(responsePublication, serverSubscription, serverAeron, serverDriver);
            }
            CloseHelper.closeAll(requestPublication, clientSubscription, clientAeron, clientDriver);
        }

        private void clientRxLoop(final Options options) {
            final IdleStrategy idle = createIdleStrategy(options.idleStrategy);
            final FragmentAssembler assembler = new FragmentAssembler(this::onResponse);
            while (running.get()) {
                idle.idle(clientSubscription.poll(assembler, FRAGMENT_LIMIT));
            }
        }

        private void onResponse(final DirectBuffer buffer, final int offset, final int length, final io.aeron.logbuffer.Header header) {
            if (length < PAYLOAD_OFFSET) {
                return;
            }

            final int workerId = buffer.getInt(offset + WORKER_ID_OFFSET);
            if (workerId < 0 || workerId >= pendingCalls.length) {
                return;
            }

            final PendingCall pendingCall = pendingCalls[workerId];
            final long sequence = buffer.getLong(offset + SEQUENCE_OFFSET);
            if (sequence == pendingCall.sequence) {
                buffer.getBytes(offset + PAYLOAD_OFFSET, pendingCall.response, 0, length - PAYLOAD_OFFSET);
                pendingCall.completed = true;
            }
        }

        private void serverLoop(final Options options) {
            final IdleStrategy pollIdle = createIdleStrategy(options.idleStrategy);
            final IdleStrategy offerIdle = createIdleStrategy(options.idleStrategy);
            final BufferClaim claim = new BufferClaim();
            final FragmentAssembler assembler = new FragmentAssembler((buffer, offset, length, header) ->
                    echo(buffer, offset, length, claim, offerIdle, options.sendMode));

            while (running.get()) {
                pollIdle.idle(serverSubscription.poll(assembler, FRAGMENT_LIMIT));
            }
        }

        private void echo(
                final DirectBuffer buffer,
                final int offset,
                final int length,
                final BufferClaim claim,
                final IdleStrategy idle,
                final SendMode sendMode
        ) {
            idle.reset();
            if (sendMode == SendMode.TRY_CLAIM && length <= responsePublication.maxPayloadLength()) {
                while (running.get()) {
                    final long result = responsePublication.tryClaim(length, claim);
                    if (result > 0) {
                        claim.buffer().putBytes(claim.offset(), buffer, offset, length);
                        claim.commit();
                        return;
                    }
                    idle.idle(0);
                }
                return;
            }

            while (running.get() && responsePublication.offer(buffer, offset, length) < 0) {
                idle.idle(0);
            }
        }

        private void waitConnected() throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (clientSubscription.isConnected()
                    && serverSubscription.isConnected()
                    && requestPublication.isConnected()
                    && responsePublication.isConnected()) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("raw Aeron UDP benchmark publications/subscriptions did not connect in time");
        }

        private static MediaDriver launchDriver(final String aeronDir, final Options options) {
            final MediaDriver.Context context = new MediaDriver.Context()
                    .aeronDirectoryName(aeronDir)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(options.driverThreading)
                    .termBufferSparseFile(true)
                    .useWindowsHighResTimer(true)
                    .warnIfDirectoryExists(true);

            if (options.driverThreading == ThreadingMode.SHARED) {
                context.sharedIdleStrategy(new BackoffIdleStrategy(100, 10, 1L, 1_000_000L));
            }

            return MediaDriver.launchEmbedded(context);
        }

        private static String channel(final String endpoint) {
            return new ChannelUriStringBuilder()
                    .media("udp")
                    .endpoint(endpoint)
                    .reliable(true)
                    .build();
        }
    }

    private enum SendMode {
        TRY_CLAIM,
        OFFER
    }

    private enum WaitIdle {
        BUSY,
        YIELDING,
        BACKOFF
    }

    private static IdleStrategy createIdleStrategy(final WaitIdle idle) {
        return switch (idle) {
            case BUSY -> new BusySpinIdleStrategy();
            case YIELDING -> new YieldingIdleStrategy();
            case BACKOFF -> new BackoffIdleStrategy(100, 10, 1L, 1_000_000L);
        };
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
        private int drivers = 2;
        private ThreadingMode driverThreading = ThreadingMode.SHARED;
        private SendMode sendMode = SendMode.TRY_CLAIM;
        private WaitIdle idleStrategy = WaitIdle.YIELDING;
        private long highestTrackableNs = TimeUnit.SECONDS.toNanos(60);
        private int significantDigits = 3;

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
                    case "drivers" -> options.drivers = Integer.parseInt(value);
                    case "driver-threading" ->
                            options.driverThreading = ThreadingMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "send" -> options.sendMode = SendMode.valueOf(value.toUpperCase(Locale.ROOT));
                    case "idle" -> options.idleStrategy = WaitIdle.valueOf(value.toUpperCase(Locale.ROOT));
                    case "highest-trackable-us" ->
                            options.highestTrackableNs = TimeUnit.MICROSECONDS.toNanos(Long.parseLong(value));
                    case "significant-digits" -> options.significantDigits = Integer.parseInt(value);
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
            if (options.drivers != 1 && options.drivers != 2) {
                throw new IllegalArgumentException("drivers must be 1 or 2");
            }
            if (options.highestTrackableNs <= 0) {
                throw new IllegalArgumentException("highest-trackable-us must be positive");
            }
            if (options.significantDigits < 1 || options.significantDigits > 5) {
                throw new IllegalArgumentException("significant-digits must be between 1 and 5");
            }
        }
    }
}