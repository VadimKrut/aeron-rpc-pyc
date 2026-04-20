package ru.pathcreator.pyc.rpc.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ChannelConfigTest {

    @Test
    void buildsConfigWithDefaults() {
        final ChannelConfig config = ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .build();

        assertEquals("localhost:40101", config.localEndpoint());
        assertEquals("localhost:40102", config.remoteEndpoint());
        assertEquals(1001, config.streamId());
        assertEquals(0, config.sessionId());
        assertEquals(1408, config.mtuLength());
        assertEquals(16 * 1024 * 1024, config.termLength());
        assertEquals(Duration.ofSeconds(5), config.defaultTimeout());
        assertEquals(Duration.ofSeconds(3), config.offerTimeout());
        assertEquals(Duration.ofSeconds(1), config.heartbeatInterval());
        assertEquals(3, config.heartbeatMissedLimit());
        assertEquals(ChannelConfig.DEFAULT_MAX_MESSAGE_SIZE, config.maxMessageSize());
        assertEquals(BackpressurePolicy.BLOCK, config.backpressurePolicy());
        assertEquals(ReconnectStrategy.FAIL_FAST, config.reconnectStrategy());
        assertFalse(config.protocolHandshakeEnabled());
        assertEquals(1, config.protocolVersion());
        assertEquals(0L, config.protocolCapabilities());
        assertEquals(0L, config.requiredRemoteCapabilities());
        assertEquals(Duration.ofSeconds(1), config.protocolHandshakeTimeout());
        assertEquals(IdleStrategyKind.YIELDING, config.rxIdleStrategy());
        assertFalse(config.isDirectExecutor());
        assertEquals(0, config.listeners().length);
    }

    @Test
    void detectsDirectExecutorMarker() {
        final ChannelConfig config = ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
                .build();

        assertTrue(config.isDirectExecutor());
        assertNotNull(config.offloadExecutor());
    }

    @Test
    void canConfigureReconnectStrategy() {
        final ChannelConfig config = ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
                .build();

        assertEquals(ReconnectStrategy.WAIT_FOR_CONNECTION, config.reconnectStrategy());
    }

    @Test
    void canConfigureProtocolHandshakeAndListeners() {
        final RpcChannelListener listener = new RpcChannelListener() {
        };
        final ChannelConfig config = ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .reconnectStrategy(ReconnectStrategy.RECREATE_ON_DISCONNECT)
                .protocolHandshakeEnabled(true)
                .protocolVersion(7)
                .protocolCapabilities(0b101L)
                .requiredRemoteCapabilities(0b001L)
                .protocolHandshakeTimeout(Duration.ofMillis(250))
                .listener(listener)
                .build();

        assertEquals(ReconnectStrategy.RECREATE_ON_DISCONNECT, config.reconnectStrategy());
        assertTrue(config.protocolHandshakeEnabled());
        assertEquals(7, config.protocolVersion());
        assertEquals(0b101L, config.protocolCapabilities());
        assertEquals(0b001L, config.requiredRemoteCapabilities());
        assertEquals(Duration.ofMillis(250), config.protocolHandshakeTimeout());
        assertEquals(1, config.listeners().length);
        assertSame(listener, config.listeners()[0]);
    }

    @Test
    void requiresLocalEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> ChannelConfig.builder()
                .remoteEndpoint("localhost:40102")
                .build());
    }

    @Test
    void requiresRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .build());
    }

    @Test
    void requiresPowerOfTwoTermLength() {
        assertThrows(IllegalArgumentException.class, () -> ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .termLength(1000)
                .build());
    }

    @Test
    void rejectsTooLargeMessageSize() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .maxMessageSize(ChannelConfig.DEFAULT_MAX_MESSAGE_SIZE + 1)
                .build());

        assertEquals("maxMessageSize > 16 MiB not supported by RpcChannel.", exception.getMessage());
    }

    @Test
    void rejectsInvalidProtocolHandshakeConfig() {
        assertThrows(IllegalArgumentException.class, () -> ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .protocolVersion(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> ChannelConfig.builder()
                .localEndpoint("localhost:40101")
                .remoteEndpoint("localhost:40102")
                .protocolHandshakeTimeout(Duration.ZERO)
                .build());
    }
}