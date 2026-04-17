package ru.pathcreator.pyc;

/**
 * Настройки корневого RpcNode (одного MediaDriver на процесс).
 * <p>
 * aeronDir — локальная директория для shared memory. Обязательна.
 * embeddedDriver — если true (default), поднимаем свой MediaDriver.
 * false означает "подключаемся к внешнему driver-у по aeronDir"
 * (external media driver, запущенный отдельным процессом).
 */
public final class NodeConfig {

    private final String aeronDir;
    private final boolean embeddedDriver;

    private NodeConfig(final Builder b) {
        this.aeronDir = b.aeronDir;
        this.embeddedDriver = b.embeddedDriver;
    }

    public String aeronDir() {
        return aeronDir;
    }

    public boolean embeddedDriver() {
        return embeddedDriver;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String aeronDir;
        private boolean embeddedDriver = true;

        public Builder aeronDir(final String v) {
            this.aeronDir = v;
            return this;
        }

        public Builder embeddedDriver(final boolean v) {
            this.embeddedDriver = v;
            return this;
        }

        public NodeConfig build() {
            if (aeronDir == null || aeronDir.isEmpty()) {
                throw new IllegalArgumentException("aeronDir is required");
            }
            return new NodeConfig(this);
        }
    }
}