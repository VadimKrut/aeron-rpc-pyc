package ru.pathcreator.pyc.rpc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RpcChannelOffloadExecutionStatePoolTest {

    @Test
    void retainsOnlyBaseCapacityAfterTemporaryOverflow() {
        final RpcChannel.OffloadExecutionStatePool pool = new RpcChannel.OffloadExecutionStatePool(1024, 128, 4096);

        final List<Object> acquired = new ArrayList<>(1152);
        for (int i = 0; i < 1152; i++) {
            final Object state = pool.acquire();
            assertNotNull(state);
            acquired.add(state);
        }

        assertTrue(pool.approximateRetainedSize() <= pool.retainedCapacity());

        for (final Object state : acquired) {
            pool.release((RpcChannel.OffloadExecutionState) state);
        }

        assertEquals(1024, pool.retainedCapacity());
        assertEquals(1024, pool.approximateRetainedSize());
    }
}