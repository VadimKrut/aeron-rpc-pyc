package ru.pathcreator.pyc;

/**
 * Настройки корневого RpcNode (одного MediaDriver на процесс).
 * <p>
 * aeronDir — локальная директория для shared memory. Обязательна.
 * embeddedDriver — если true (default), поднимаем свой MediaDriver.
 * false означает "подключаемся к внешнему driver-у по aeronDir"
 * (external media driver, запущенный отдельным процессом).
 *
 * <p>Configuration of the root {@link RpcNode}. It defines the Aeron directory
 * and whether the node should start an embedded MediaDriver.</p>
 */
public final class NodeConfig {

    private final String aeronDir;
    private final boolean embeddedDriver;

    private NodeConfig(final Builder b) {
        this.aeronDir = b.aeronDir;
        this.embeddedDriver = b.embeddedDriver;
    }

    /**
     * Возвращает директорию Aeron.
     *
     * <p>Returns the Aeron directory.</p>
     *
     * @return директория Aeron / Aeron directory
     */
    public String aeronDir() {
        return aeronDir;
    }

    /**
     * Проверяет, должен ли узел запускать embedded MediaDriver.
     *
     * <p>Checks whether the node should start an embedded MediaDriver.</p>
     *
     * @return {@code true}, если используется embedded driver /
     * {@code true} when an embedded driver is used
     */
    public boolean embeddedDriver() {
        return embeddedDriver;
    }

    /**
     * Создает builder конфигурации узла.
     *
     * <p>Creates a node configuration builder.</p>
     *
     * @return новый builder / new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder для создания {@link NodeConfig}.
     *
     * <p>Builder used to create {@link NodeConfig} instances.</p>
     */
    public static final class Builder {
        private String aeronDir;
        private boolean embeddedDriver = true;

        /**
         * Создает builder с настройками по умолчанию.
         *
         * <p>Creates a builder with default settings.</p>
         */
        public Builder() {
        }

        /**
         * Задает директорию Aeron.
         *
         * <p>Sets the Aeron directory.</p>
         *
         * @param v директория Aeron / Aeron directory
         * @return этот builder / this builder
         */
        public Builder aeronDir(final String v) {
            this.aeronDir = v;
            return this;
        }

        /**
         * Задает режим embedded MediaDriver.
         *
         * <p>Sets whether an embedded MediaDriver should be used.</p>
         *
         * @param v {@code true} для embedded driver / {@code true} to use an embedded driver
         * @return этот builder / this builder
         */
        public Builder embeddedDriver(final boolean v) {
            this.embeddedDriver = v;
            return this;
        }

        /**
         * Создает неизменяемую конфигурацию узла.
         *
         * <p>Builds an immutable node configuration.</p>
         *
         * @return конфигурация узла / node configuration
         */
        public NodeConfig build() {
            if (aeronDir == null || aeronDir.isEmpty()) {
                throw new IllegalArgumentException("aeronDir is required");
            }
            return new NodeConfig(this);
        }
    }
}