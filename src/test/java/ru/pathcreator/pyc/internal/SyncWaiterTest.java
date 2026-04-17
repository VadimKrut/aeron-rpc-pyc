package ru.pathcreator.pyc.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncWaiterTest {

    @Test
    void returnsTrueWhenCallAlreadyCompleted() {
        final SyncWaiter waiter = new SyncWaiter(10, 0, 1_000L);
        final PendingCall call = new PendingCall();
        call.prepare(Thread.currentThread(), 1L);
        call.completeFail("done");

        assertTrue(waiter.await(call, 1_000_000L));
    }

    @Test
    void returnsFalseWhenTimeoutExpires() {
        final SyncWaiter waiter = new SyncWaiter(0, 0, 1_000L);
        final PendingCall call = new PendingCall();
        call.prepare(Thread.currentThread(), 1L);

        assertFalse(waiter.await(call, 1_000L));
    }
}