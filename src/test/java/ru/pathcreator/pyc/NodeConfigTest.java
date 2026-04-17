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
    void requiresAeronDir() {
        assertThrows(IllegalArgumentException.class, () -> NodeConfig.builder().build());
    }
}