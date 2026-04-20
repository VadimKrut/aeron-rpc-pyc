package ru.pathcreator.pyc.rpc.schema;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;
import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcMethod;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;

import static org.junit.jupiter.api.Assertions.*;

class RpcServiceRegistryTest {
    private static final MessageCodec<Integer> INT_CODEC = new MessageCodec<>() {
        @Override
        public int encode(final Integer message, final MutableDirectBuffer buffer, final int offset) {
            buffer.putInt(offset, message);
            return Integer.BYTES;
        }

        @Override
        public Integer decode(final DirectBuffer buffer, final int offset, final int length) {
            return buffer.getInt(offset);
        }
    };

    @Test
    void buildsRegistryAndRendersReadableReport() {
        final RpcMethod<Integer, Integer> ping = RpcMethod.of(1, 101, INT_CODEC, INT_CODEC);
        final RpcMethod<Integer, Integer> risk = RpcMethod.of(2, 102, INT_CODEC, INT_CODEC);

        final RpcServiceRegistry.Builder builder = RpcServiceRegistry.builder();
        builder.channel("orders", channelConfig("localhost:40101", "localhost:40102", 1001))
                .method("ping", ping, Integer.class, Integer.class, "v1", "simple reachability check")
                .method("risk-check", risk, Integer.class, Integer.class);
        final RpcServiceRegistry registry = builder.build();

        assertEquals(1, registry.channelCount());
        assertEquals(2, registry.methodCount());

        final String report = registry.renderTextReport();
        assertTrue(report.contains("Channel: orders"));
        assertTrue(report.contains("Stream: 1001"));
        assertTrue(report.contains("ping [req=1, resp=101"));
        assertTrue(report.contains("risk-check [req=2, resp=102"));
        assertTrue(report.contains("version=v1"));
    }

    @Test
    void rejectsDuplicateChannelNames() {
        final RpcServiceRegistry.Builder builder = RpcServiceRegistry.builder();
        builder.channel("orders", channelConfig("localhost:40101", "localhost:40102", 1001));

        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> builder.channel("orders", channelConfig("localhost:40103", "localhost:40104", 1002)));

        assertEquals("duplicate channel name: orders", exception.getMessage());
    }

    @Test
    void rejectsDuplicateMethodNamesInsideChannel() {
        final RpcMethod<Integer, Integer> method = RpcMethod.of(1, 101, INT_CODEC, INT_CODEC);

        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RpcServiceRegistry.builder()
                        .channel("orders", channelConfig("localhost:40101", "localhost:40102", 1001))
                        .method("ping", method, Integer.class, Integer.class)
                        .method("ping", method, Integer.class, Integer.class));

        assertEquals("duplicate method name in channel 'orders': ping", exception.getMessage());
    }

    @Test
    void rejectsDuplicateRequestMessageTypeIdsInsideChannel() {
        final RpcMethod<Integer, Integer> ping = RpcMethod.of(1, 101, INT_CODEC, INT_CODEC);
        final RpcMethod<Integer, Integer> echo = RpcMethod.of(1, 102, INT_CODEC, INT_CODEC);

        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RpcServiceRegistry.builder()
                        .channel("orders", channelConfig("localhost:40101", "localhost:40102", 1001))
                        .method("ping", ping, Integer.class, Integer.class)
                        .method("echo", echo, Integer.class, Integer.class));

        assertEquals(
                "duplicate requestMessageTypeId 1 in channel 'orders' for methods 'ping' and 'echo'",
                exception.getMessage());
    }

    private static ChannelConfig channelConfig(final String localEndpoint, final String remoteEndpoint, final int streamId) {
        return ChannelConfig.builder()
                .localEndpoint(localEndpoint)
                .remoteEndpoint(remoteEndpoint)
                .streamId(streamId)
                .build();
    }
}