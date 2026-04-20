package ru.pathcreator.pyc.rpc.schema;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.Test;
import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcMethod;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;
import ru.pathcreator.pyc.rpc.core.envelope.Envelope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        System.out.println(report);
        assertTrue(report.contains("Channel: orders"));
        assertTrue(report.contains("Stream: 1001"));
        assertTrue(report.contains("ping [req=1, resp=101"));
        assertTrue(report.contains("risk-check [req=2, resp=102"));
        assertTrue(report.contains("version=v1"));
        assertTrue(report.contains("Warnings: 0"));

        final String json = registry.renderJsonReport();
        assertTrue(json.contains("\"channelCount\":1"));
        assertTrue(json.contains("\"methodCount\":2"));
        assertTrue(json.contains("\"name\":\"orders\""));
        assertTrue(json.contains("\"warnings\":[]"));
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

    @Test
    void rejectsReservedTransportMessageTypeIds() {
        final RpcMethod<Integer, Integer> method = RpcMethod.of(Envelope.RESERVED_PROTOCOL_HANDSHAKE, 101, INT_CODEC, INT_CODEC);

        final IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RpcServiceRegistry.builder()
                        .channel("orders", channelConfig("localhost:40101", "localhost:40102", 1001))
                        .method("ping", method, Integer.class, Integer.class));

        assertEquals("requestMessageTypeId must be >= 1 for method 'ping'", exception.getMessage());
    }

    @Test
    void analyzesSuspiciousConfigAndWritesReports() throws Exception {
        final RpcMethod<Integer, Integer> ping = RpcMethod.of(1, 101, INT_CODEC, INT_CODEC);
        final RpcServiceRegistry.Builder builder = RpcServiceRegistry.builder();
        builder.channel("orders", ChannelConfig.builder()
                        .localEndpoint("localhost:40101")
                        .remoteEndpoint("localhost:40101")
                        .streamId(1001)
                        .offerTimeout(java.time.Duration.ofSeconds(3))
                        .defaultTimeout(java.time.Duration.ofSeconds(1))
                        .heartbeatInterval(java.time.Duration.ofSeconds(1))
                        .protocolVersion(2)
                        .build())
                .method("ping", ping, Integer.class, Integer.class);
        final RpcServiceRegistry registry = builder.build();

        final List<RpcValidationIssue> issues = registry.analyze();
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("same-endpoint")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("handshake-disabled-custom-protocol")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("offer-timeout-longer-than-call-timeout")));

        final Path tempDir = Files.createTempDirectory("rpc-registry-report");
        final Path textPath = tempDir.resolve("schema.txt");
        final Path jsonPath = tempDir.resolve("schema.json");
        registry.writeTextReport(textPath);
        registry.writeJsonReport(jsonPath);

        assertTrue(Files.readString(textPath).contains("Validation warnings"));
        assertTrue(Files.readString(jsonPath).contains("\"warnings\":["));
    }

    private static ChannelConfig channelConfig(final String localEndpoint, final String remoteEndpoint, final int streamId) {
        return ChannelConfig.builder()
                .localEndpoint(localEndpoint)
                .remoteEndpoint(remoteEndpoint)
                .streamId(streamId)
                .build();
    }
}