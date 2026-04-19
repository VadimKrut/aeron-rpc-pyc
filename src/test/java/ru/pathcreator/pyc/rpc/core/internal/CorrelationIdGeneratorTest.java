package ru.pathcreator.pyc.rpc.core.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationIdGeneratorTest {

    @Test
    void returnsMonotonicallyIncreasingIds() {
        final CorrelationIdGenerator generator = new CorrelationIdGenerator();

        final long first = generator.next();
        final long second = generator.next();
        final long third = generator.next();

        assertTrue(second > first);
        assertTrue(third > second);
    }
}