package ru.pathcreator.pyc.rpc.core.bench;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.ThreadParams;
import ru.pathcreator.pyc.rpc.core.*;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
)
public class RpcChannelBenchmark {

    private static final int REQUEST_TYPE = 1;
    private static final int RESPONSE_TYPE = 2;
    private static final ByteArrayCodec CODEC = new ByteArrayCodec();

    @State(Scope.Benchmark)
    public static class OneChannelState extends RpcState {
        @Setup
        public void setup() throws Exception {
            setupChannels(1);
        }
    }

    @State(Scope.Benchmark)
    public static class FourChannelState extends RpcState {
        @Setup
        public void setup() throws Exception {
            setupChannels(4);
        }
    }

    @State(Scope.Benchmark)
    public abstract static class RpcState {
        @Param({"16", "256", "1024"})
        int payloadSize;

        @Param({"DIRECT", "OFFLOAD"})
        String handlerMode;

        @Param({"YIELDING", "BUSY_SPIN", "BACKOFF"})
        IdleStrategyKind idleStrategy;

        RpcNode node;
        RpcChannel[] clients;
        RpcChannel[] servers;
        byte[] request;

        void setupChannels(final int channelCount) throws Exception {
            request = new byte[payloadSize];
            for (int i = 0; i < request.length; i++) {
                request[i] = (byte) i;
            }

            final String aeronDir = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "rpc-core-jmh-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextInt(1_000_000)
            ).toAbsolutePath().toString();

            node = RpcNode.start(NodeConfig.builder()
                    .aeronDir(aeronDir)
                    .embeddedDriver(true)
                    .build());

            clients = new RpcChannel[channelCount];
            servers = new RpcChannel[channelCount];

            final int basePort = 30_000 + ThreadLocalRandom.current().nextInt(10_000);
            for (int i = 0; i < channelCount; i++) {
                final int clientPort = basePort + i * 2;
                final int serverPort = clientPort + 1;
                final int streamId = 1_001 + i;

                clients[i] = node.channel(config("localhost:" + clientPort, "localhost:" + serverPort, streamId));
                servers[i] = node.channel(config("localhost:" + serverPort, "localhost:" + clientPort, streamId));
                servers[i].onRaw(REQUEST_TYPE, RESPONSE_TYPE, echoHandler());
            }

            for (int i = 0; i < channelCount; i++) {
                servers[i].start();
                clients[i].start();
            }
            waitConnected();
        }

        @TearDown
        public void tearDown() {
            if (node != null) {
                node.close();
            }
        }

        byte[] call(final int channelIndex) {
            return clients[channelIndex].call(
                    REQUEST_TYPE,
                    RESPONSE_TYPE,
                    request,
                    CODEC,
                    CODEC,
                    5,
                    TimeUnit.SECONDS
            );
        }

        private ChannelConfig config(final String localEndpoint, final String remoteEndpoint, final int streamId) {
            final ChannelConfig.Builder builder = ChannelConfig.builder()
                    .localEndpoint(localEndpoint)
                    .remoteEndpoint(remoteEndpoint)
                    .streamId(streamId)
                    .defaultTimeout(Duration.ofSeconds(5))
                    .offerTimeout(Duration.ofSeconds(3))
                    .heartbeatInterval(Duration.ofMillis(50))
                    .heartbeatMissedLimit(10)
                    .rxIdleStrategy(idleStrategy)
                    .pendingPoolCapacity(16_384)
                    .registryInitialCapacity(16_384)
                    .offloadTaskPoolSize(16_384)
                    .offloadCopyPoolSize(4_096)
                    .offloadCopyBufferSize(Math.max(8 * 1024, payloadSize));

            if ("DIRECT".equals(handlerMode)) {
                builder.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR);
            }
            return builder.build();
        }

        private RawRequestHandler echoHandler() {
            return (requestBuffer, requestOffset, requestLength, responseBuffer, responseOffset, responseCapacity) -> {
                responseBuffer.putBytes(responseOffset, requestBuffer, requestOffset, requestLength);
                return requestLength;
            };
        }

        private void waitConnected() throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                boolean connected = true;
                for (int i = 0; i < clients.length; i++) {
                    connected &= clients[i].isConnected();
                    connected &= servers[i].isConnected();
                }
                if (connected) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("RPC benchmark channels did not connect in time");
        }
    }

    @Benchmark
    @Threads(1)
    public byte[] oneChannelOneThread(final OneChannelState state) {
        return state.call(0);
    }

    @Benchmark
    @Threads(8)
    public byte[] oneChannelEightThreads(final OneChannelState state) {
        return state.call(0);
    }

    @Benchmark
    @Threads(32)
    public byte[] fourChannelsEightThreadsEach(final FourChannelState state, final ThreadParams threadParams) {
        return state.call(threadParams.getThreadIndex() & 3);
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