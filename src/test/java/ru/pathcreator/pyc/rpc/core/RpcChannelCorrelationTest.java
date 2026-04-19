package ru.pathcreator.pyc.rpc.core;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;
import ru.pathcreator.pyc.rpc.core.envelope.Envelope;
import ru.pathcreator.pyc.rpc.core.exceptions.RemoteRpcException;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcApplicationException;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcStatus;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RpcChannelCorrelationTest {

    private static final int REQUEST_TYPE = 1;
    private static final int RESPONSE_TYPE = 2;
    private static final IntCodec INT_CODEC = new IntCodec();
    private static final ByteArrayCodec BYTE_ARRAY_CODEC = new ByteArrayCodec();

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void concurrentCallsReceiveMatchingResponses() throws Exception {
        final Path aeronDir = Files.createTempDirectory("rpc-core-correlation-");
        try (RpcNode node = RpcNode.start(NodeConfig.builder()
                .aeronDir(aeronDir.toString())
                .embeddedDriver(true)
                .build())) {

            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel client = node.channel(channelConfig(
                    "localhost:" + basePort,
                    "localhost:" + (basePort + 1),
                    streamId));
            final RpcChannel server = node.channel(channelConfig(
                    "localhost:" + (basePort + 1),
                    "localhost:" + basePort,
                    streamId));

            server.onRequest(REQUEST_TYPE, RESPONSE_TYPE, INT_CODEC, INT_CODEC, request -> request);
            server.start();
            client.start();
            waitConnected(client, server);

            final int threadCount = 8;
            final int callsPerThread = 200;
            final CountDownLatch start = new CountDownLatch(1);
            final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                final List<Future<Void>> futures = new ArrayList<>(threadCount);
                for (int thread = 0; thread < threadCount; thread++) {
                    final int threadIndex = thread;
                    futures.add(executor.submit(caller(client, start, threadIndex, callsPerThread)));
                }

                start.countDown();
                for (final Future<Void> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void offloadedIoLikeHandlersKeepResponsesMatchedAcrossChannels() throws Exception {
        final Path aeronDir = Files.createTempDirectory("rpc-core-io-correlation-");
        try (RpcNode node = RpcNode.start(NodeConfig.builder()
                .aeronDir(aeronDir.toString())
                .embeddedDriver(true)
                .sharedReceivePollerThreads(2)
                .build())) {

            final int channelCount = 4;
            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel[] clients = new RpcChannel[channelCount];
            final RpcChannel[] servers = new RpcChannel[channelCount];

            for (int i = 0; i < channelCount; i++) {
                final int channelBasePort = basePort + i * 2;
                clients[i] = node.channel(channelConfig(
                        "localhost:" + channelBasePort,
                        "localhost:" + (channelBasePort + 1),
                        streamId + i,
                        false));
                servers[i] = node.channel(channelConfig(
                        "localhost:" + (channelBasePort + 1),
                        "localhost:" + channelBasePort,
                        streamId + i,
                        false));
                servers[i].onRequest(REQUEST_TYPE, RESPONSE_TYPE, INT_CODEC, INT_CODEC, request -> {
                    LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(250));
                    return request;
                });
            }

            for (final RpcChannel server : servers) {
                server.start();
            }
            for (final RpcChannel client : clients) {
                client.start();
            }
            waitConnected(clients, servers);

            final int threadCount = 8;
            final int callsPerThread = 50;
            final CountDownLatch start = new CountDownLatch(1);
            final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                final List<Future<Void>> futures = new ArrayList<>(threadCount);
                for (int thread = 0; thread < threadCount; thread++) {
                    final int threadIndex = thread;
                    futures.add(executor.submit(caller(clients, start, threadIndex, callsPerThread)));
                }

                start.countDown();
                for (final Future<Void> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void waitForConnectionStrategyLetsCallStartDuringReconnect() throws Exception {
        final Path aeronDir = Files.createTempDirectory("rpc-core-reconnect-");
        try (RpcNode node = RpcNode.start(NodeConfig.builder()
                .aeronDir(aeronDir.toString())
                .embeddedDriver(true)
                .build())) {

            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel client = node.channel(channelConfig(
                    "localhost:" + basePort,
                    "localhost:" + (basePort + 1),
                    streamId,
                    true,
                    ReconnectStrategy.WAIT_FOR_CONNECTION));
            final RpcChannel server = node.channel(channelConfig(
                    "localhost:" + (basePort + 1),
                    "localhost:" + basePort,
                    streamId,
                    true,
                    ReconnectStrategy.FAIL_FAST));

            server.onRequest(REQUEST_TYPE, RESPONSE_TYPE, INT_CODEC, INT_CODEC, request -> request);
            client.start();

            final ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                final Future<Integer> response = executor.submit(() -> client.call(
                        REQUEST_TYPE,
                        RESPONSE_TYPE,
                        42,
                        INT_CODEC,
                        INT_CODEC,
                        5,
                        TimeUnit.SECONDS));
                Thread.sleep(200);
                server.start();
                waitConnected(client, server);
                assertEquals(42, response.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void maxSupportedPayloadRoundTripsWithoutCorruption() throws Exception {
        final Path aeronDir = Files.createTempDirectory("rpc-core-max-payload-");
        try (RpcNode node = RpcNode.start(NodeConfig.builder()
                .aeronDir(aeronDir.toString())
                .embeddedDriver(true)
                .build())) {

            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final int payloadSize = ChannelConfig.DEFAULT_MAX_MESSAGE_SIZE - Envelope.LENGTH;
            final byte[] payload = new byte[payloadSize];
            ThreadLocalRandom.current().nextBytes(payload);
            final int termLength = 128 * 1024 * 1024;

            final RpcChannel client = node.channel(ChannelConfig.builder()
                    .localEndpoint("localhost:" + basePort)
                    .remoteEndpoint("localhost:" + (basePort + 1))
                    .streamId(streamId)
                    .termLength(termLength)
                    .defaultTimeout(Duration.ofSeconds(30))
                    .offerTimeout(Duration.ofSeconds(30))
                    .heartbeatInterval(Duration.ofMillis(50))
                    .heartbeatMissedLimit(10)
                    .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
                    .reconnectStrategy(ReconnectStrategy.FAIL_FAST)
                    .build());
            final RpcChannel server = node.channel(ChannelConfig.builder()
                    .localEndpoint("localhost:" + (basePort + 1))
                    .remoteEndpoint("localhost:" + basePort)
                    .streamId(streamId)
                    .termLength(termLength)
                    .defaultTimeout(Duration.ofSeconds(30))
                    .offerTimeout(Duration.ofSeconds(30))
                    .heartbeatInterval(Duration.ofMillis(50))
                    .heartbeatMissedLimit(10)
                    .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
                    .reconnectStrategy(ReconnectStrategy.FAIL_FAST)
                    .build());

            server.onRequest(REQUEST_TYPE, RESPONSE_TYPE, BYTE_ARRAY_CODEC, BYTE_ARRAY_CODEC, request -> request);
            server.start();
            client.start();
            waitConnected(client, server);

            final byte[] response = client.call(
                    REQUEST_TYPE,
                    RESPONSE_TYPE,
                    payload,
                    BYTE_ARRAY_CODEC,
                    BYTE_ARRAY_CODEC,
                    30,
                    TimeUnit.SECONDS);
            assertEquals(payload.length, response.length);
            assertEquals(-1, java.util.Arrays.mismatch(payload, response));
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void remoteHandlerExceptionReturnsStructuredRemoteError() throws Exception {
        final Path aeronDir = Files.createTempDirectory("rpc-core-remote-error-");
        try (RpcNode node = RpcNode.start(NodeConfig.builder()
                .aeronDir(aeronDir.toString())
                .embeddedDriver(true)
                .build())) {

            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel client = node.channel(channelConfig(
                    "localhost:" + basePort,
                    "localhost:" + (basePort + 1),
                    streamId));
            final RpcChannel server = node.channel(channelConfig(
                    "localhost:" + (basePort + 1),
                    "localhost:" + basePort,
                    streamId));

            server.onRequest(REQUEST_TYPE, RESPONSE_TYPE, INT_CODEC, INT_CODEC, request -> {
                throw new IllegalStateException("boom");
            });
            server.start();
            client.start();
            waitConnected(client, server);

            final RemoteRpcException exception = assertInstanceOf(RemoteRpcException.class, org.junit.jupiter.api.Assertions.assertThrows(
                    RemoteRpcException.class,
                    () -> client.call(REQUEST_TYPE, RESPONSE_TYPE, 42, INT_CODEC, INT_CODEC, 5, TimeUnit.SECONDS)));
            assertEquals(RpcStatus.INTERNAL_SERVER_ERROR.code(), exception.statusCode());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void remoteApplicationExceptionPreservesStatusCodeAndMessage() throws Exception {
        final Path aeronDir = Files.createTempDirectory("rpc-core-remote-app-error-");
        try (RpcNode node = RpcNode.start(NodeConfig.builder()
                .aeronDir(aeronDir.toString())
                .embeddedDriver(true)
                .build())) {

            final int basePort = 35_000 + ThreadLocalRandom.current().nextInt(10_000);
            final int streamId = 2_001 + ThreadLocalRandom.current().nextInt(1_000);
            final RpcChannel client = node.channel(channelConfig(
                    "localhost:" + basePort,
                    "localhost:" + (basePort + 1),
                    streamId));
            final RpcChannel server = node.channel(channelConfig(
                    "localhost:" + (basePort + 1),
                    "localhost:" + basePort,
                    streamId));

            server.onRequest(REQUEST_TYPE, RESPONSE_TYPE, INT_CODEC, INT_CODEC, request -> {
                throw new RpcApplicationException(1001, "business rule failed");
            });
            server.start();
            client.start();
            waitConnected(client, server);

            final RemoteRpcException exception = assertInstanceOf(RemoteRpcException.class, org.junit.jupiter.api.Assertions.assertThrows(
                    RemoteRpcException.class,
                    () -> client.call(REQUEST_TYPE, RESPONSE_TYPE, 42, INT_CODEC, INT_CODEC, 5, TimeUnit.SECONDS)));
            assertEquals(1001, exception.statusCode());
            assertEquals("Remote RPC failed [1001]: business rule failed", exception.getMessage());
        }
    }

    private static Callable<Void> caller(
            final RpcChannel client,
            final CountDownLatch start,
            final int threadIndex,
            final int callsPerThread
    ) {
        return () -> {
            start.await();
            for (int call = 0; call < callsPerThread; call++) {
                final int request = threadIndex * 1_000_000 + call;
                final Integer response = client.call(
                        REQUEST_TYPE,
                        RESPONSE_TYPE,
                        request,
                        INT_CODEC,
                        INT_CODEC,
                        5,
                        TimeUnit.SECONDS);
                assertEquals(request, response);
            }
            return null;
        };
    }

    private static Callable<Void> caller(
            final RpcChannel[] clients,
            final CountDownLatch start,
            final int threadIndex,
            final int callsPerThread
    ) {
        return () -> {
            final RpcChannel client = clients[threadIndex % clients.length];
            start.await();
            for (int call = 0; call < callsPerThread; call++) {
                final int request = threadIndex * 1_000_000 + call;
                final Integer response = client.call(
                        REQUEST_TYPE,
                        RESPONSE_TYPE,
                        request,
                        INT_CODEC,
                        INT_CODEC,
                        5,
                        TimeUnit.SECONDS);
                assertEquals(request, response);
            }
            return null;
        };
    }

    private static ChannelConfig channelConfig(
            final String localEndpoint,
            final String remoteEndpoint,
            final int streamId
    ) {
        return ChannelConfig.builder()
                .localEndpoint(localEndpoint)
                .remoteEndpoint(remoteEndpoint)
                .streamId(streamId)
                .defaultTimeout(Duration.ofSeconds(5))
                .offerTimeout(Duration.ofSeconds(3))
                .heartbeatInterval(Duration.ofMillis(50))
                .heartbeatMissedLimit(10)
                .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
                .build();
    }

    private static ChannelConfig channelConfig(
            final String localEndpoint,
            final String remoteEndpoint,
            final int streamId,
            final boolean direct
    ) {
        final ChannelConfig.Builder builder = ChannelConfig.builder()
                .localEndpoint(localEndpoint)
                .remoteEndpoint(remoteEndpoint)
                .streamId(streamId)
                .defaultTimeout(Duration.ofSeconds(5))
                .offerTimeout(Duration.ofSeconds(3))
                .heartbeatInterval(Duration.ofMillis(50))
                .heartbeatMissedLimit(10);
        if (direct) {
            builder.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR);
        }
        return builder.build();
    }

    private static ChannelConfig channelConfig(
            final String localEndpoint,
            final String remoteEndpoint,
            final int streamId,
            final boolean direct,
            final ReconnectStrategy reconnectStrategy
    ) {
        final ChannelConfig.Builder builder = ChannelConfig.builder()
                .localEndpoint(localEndpoint)
                .remoteEndpoint(remoteEndpoint)
                .streamId(streamId)
                .defaultTimeout(Duration.ofSeconds(5))
                .offerTimeout(Duration.ofSeconds(3))
                .heartbeatInterval(Duration.ofMillis(50))
                .heartbeatMissedLimit(10)
                .reconnectStrategy(reconnectStrategy);
        if (direct) {
            builder.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR);
        }
        return builder.build();
    }

    private static void waitConnected(final RpcChannel client, final RpcChannel server) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (client.isConnected() && server.isConnected()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("RPC test channels did not connect in time");
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
        throw new IllegalStateException("RPC test channels did not connect in time");
    }

    private static final class IntCodec implements MessageCodec<Integer> {
        @Override
        public int encode(final Integer message, final MutableDirectBuffer buffer, final int offset) {
            buffer.putInt(offset, message, ByteOrder.LITTLE_ENDIAN);
            return Integer.BYTES;
        }

        @Override
        public Integer decode(final DirectBuffer buffer, final int offset, final int length) {
            assertEquals(Integer.BYTES, length);
            return buffer.getInt(offset, ByteOrder.LITTLE_ENDIAN);
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
            final byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            return bytes;
        }
    }
}
