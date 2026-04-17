package ru.pathcreator.pyc.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class PendingCallPoolTest {

    @Test
    void reusesReleasedCallAndResetsIt() {
        final PendingCallPool pool = new PendingCallPool(2);
        final PendingCall call = pool.acquire();
        final PendingCall other = pool.acquire();

        call.prepare(Thread.currentThread(), 55L);
        call.completeFail("failed");
        pool.release(call);

        final PendingCall acquiredAgain = pool.acquire();

        assertSame(call, acquiredAgain);
        assertFalse(acquiredAgain.isCompleted());
        assertFalse(acquiredAgain.isFailed());
        pool.release(other);
    }
}