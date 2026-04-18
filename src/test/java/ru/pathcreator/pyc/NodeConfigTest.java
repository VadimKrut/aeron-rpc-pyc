package ru.pathcreator.pyc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeConfigTest {

    @Test
    void buildsConfigWithDefaults() {
        final NodeConfig config = NodeConfig.builder()
                .aeronDir("target/aeron-test")
                .build();

        assertEquals("target/aeron-test", config.aeronDir());
        assertTrue(config.embeddedDriver());
        assertTrue(config.sharedReceivePoller());
        assertEquals(4, config.sharedReceivePollerThreads());
        assertEquals(16, config.sharedReceivePollerFragmentLimit());
    }

    @Test
    void canDisableEmbeddedDriver() {
        final NodeConfig config = NodeConfig.builder()
                .aeronDir("target/aeron-test")
                .embeddedDriver(false)
                .build();

        assertFalse(config.embeddedDriver());
    }

    @Test
    void canConfigureSharedReceivePoller() {
        final NodeConfig config = NodeConfig.builder()
                .aeronDir("target/aeron-test")
                .sharedReceivePoller(false)
                .sharedReceivePollerThreads(2)
                .sharedReceivePollerFragmentLimit(32)
                .build();

        assertFalse(config.sharedReceivePoller());
        assertEquals(2, config.sharedReceivePollerThreads());
        assertEquals(32, config.sharedReceivePollerFragmentLimit());
    }

    @Test
    void requiresAeronDir() {
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.builder().build());
    }

    @Test
    void requiresPositiveSharedReceivePollerThreads() {
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.builder()
                .aeronDir("target/aeron-test")
                .sharedReceivePollerThreads(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.builder()
                .aeronDir("target/aeron-test")
                .sharedReceivePollerFragmentLimit(0)
                .build());
    }
}