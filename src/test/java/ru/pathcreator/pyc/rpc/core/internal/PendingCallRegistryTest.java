package ru.pathcreator.pyc.rpc.core.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PendingCallRegistryTest {

    @Test
    void registersAndRemovesPendingCall() {
        final PendingCallRegistry registry = new PendingCallRegistry(4);
        final PendingCall call = new PendingCall();

        registry.register(10L, call);

        assertEquals(1, registry.size());
        assertSame(call, registry.remove(10L));
        assertEquals(0, registry.size());
        assertNull(registry.remove(10L));
    }

    @Test
    void rejectsDuplicateCorrelationId() {
        final PendingCallRegistry registry = new PendingCallRegistry(4);

        registry.register(10L, new PendingCall());

        assertThrows(IllegalStateException.class, () -> registry.register(10L, new PendingCall()));
    }

    @Test
    void forEachAndClearVisitsAllCallsAndClearsRegistry() {
        final PendingCallRegistry registry = new PendingCallRegistry(4);
        final PendingCall first = new PendingCall();
        final PendingCall second = new PendingCall();
        final List<PendingCall> visited = new ArrayList<>();

        registry.register(1L, first);
        registry.register(2L, second);
        registry.forEachAndClear(visited::add);

        assertEquals(2, visited.size());
        assertEquals(0, registry.size());
    }
}