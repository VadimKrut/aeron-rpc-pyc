package ru.pathcreator.pyc;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.pathcreator.pyc.codec.MessageCodec;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RpcChannelCorrelationTest {

    private static final int REQUEST_TYPE = 1;
    private static final int RESPONSE_TYPE = 2;
    private static final IntCodec INT_CODEC = new IntCodec();

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void concurrentCallsReceiveMatchingResponses() throws Exception {
        final Path aeronDir = Files.createTempDirectory("aeron-rpc-correlation-");
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
}