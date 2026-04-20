package ru.pathcreator.pyc.rpc.metrics;

import org.junit.jupiter.api.Test;
import ru.pathcreator.pyc.rpc.core.ReconnectStrategy;
import ru.pathcreator.pyc.rpc.core.exceptions.RemoteRpcException;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcStatus;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RpcMetricsListenerTest {

    @Test
    void collectsCountersFromListenerCallbacks() {
        final RpcMetricsListener listener = new RpcMetricsListener();

        listener.onCallStarted(null, 1, 10L);
        listener.onCallSucceeded(null, 1, 10L);
        listener.onCallTimedOut(null, 1, 11L, 123L);
        listener.onCallFailed(null, 1, 12L, new RpcTimeoutException(12L, 1_000_000L));
        listener.onRemoteError(null, 2, 13L, new RemoteRpcException(RpcStatus.BAD_REQUEST.code(), "bad"));
        listener.onChannelUp(null);
        listener.onChannelDown(null);
        listener.onDrainStarted(null);
        listener.onDrainFinished(null);
        listener.onReconnectAttempt(null, ReconnectStrategy.RECREATE_ON_DISCONNECT);
        listener.onReconnectSucceeded(null, ReconnectStrategy.RECREATE_ON_DISCONNECT);
        listener.onReconnectFailed(null, ReconnectStrategy.RECREATE_ON_DISCONNECT, new IllegalStateException("boom"));
        listener.onProtocolHandshakeStarted(null);
        listener.onProtocolHandshakeSucceeded(null, 1, 3L);
        listener.onProtocolHandshakeFailed(null, new RemoteRpcException(RpcStatus.BAD_REQUEST.code(), "bad"));

        final RpcMetricsSnapshot snapshot = listener.snapshot();
        assertEquals(1, snapshot.callsStarted());
        assertEquals(1, snapshot.callsSucceeded());
        assertEquals(1, snapshot.callsTimedOut());
        assertEquals(1, snapshot.callsFailed());
        assertEquals(3, listener.completedCalls());
        assertEquals(1, snapshot.remoteErrors());
        assertEquals(1, snapshot.channelUps());
        assertEquals(1, snapshot.channelDowns());
        assertEquals(1, snapshot.drainStarts());
        assertEquals(1, snapshot.drainFinishes());
        assertEquals(1, snapshot.reconnectAttempts());
        assertEquals(1, snapshot.reconnectSuccesses());
        assertEquals(1, snapshot.reconnectFailures());
        assertEquals(1, snapshot.protocolHandshakeStarts());
        assertEquals(1, snapshot.protocolHandshakeSuccesses());
        assertEquals(1, snapshot.protocolHandshakeFailures());
    }

    @Test
    void resetClearsCounters() {
        final RpcMetricsListener listener = new RpcMetricsListener();
        listener.onCallStarted(null, 1, 1L);

        listener.reset();

        assertEquals(0, listener.snapshot().callsStarted());
        assertEquals(0, listener.completedCalls());
    }
}