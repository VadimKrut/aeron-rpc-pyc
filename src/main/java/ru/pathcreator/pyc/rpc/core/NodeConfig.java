package ru.pathcreator.pyc.rpc.core;

/**
 * Конфигурация корневого {@link RpcNode}.
 *
 * <p>Configuration of the root {@link RpcNode}.</p>
 *
 * <p>Узел владеет Aeron client-ом, optional embedded MediaDriver и optional
 * shared receive poller-ом, который могут использовать каналы, созданные через
 * этот узел.</p>
 *
 * <p>The node owns the Aeron client, the optional embedded MediaDriver, and
 * the optional shared receive poller used by channels created through that
 * node.</p>
 *
 * <p>Основные группы настроек / Main groups of settings:</p>
 * <ul>
 *   <li><b>Aeron attachment</b>: {@code aeronDir}, {@code embeddedDriver}</li>
 *   <li><b>Receive architecture</b>: shared receive poller enabled or
 *       dedicated RX thread per channel</li>
 *   <li><b>Shared poller tuning</b>: lane count and fragment limit</li>
 * </ul>
 */
public final class NodeConfig {

    private final String aeronDir;
    private final boolean embeddedDriver;
    private final boolean sharedReceivePoller;
    private final int sharedReceivePollerThreads;
    private final int sharedReceivePollerFragmentLimit;

    private NodeConfig(final Builder b) {
        this.aeronDir = b.aeronDir;
        this.embeddedDriver = b.embeddedDriver;
        this.sharedReceivePoller = b.sharedReceivePoller;
        this.sharedReceivePollerThreads = b.sharedReceivePollerThreads;
        this.sharedReceivePollerFragmentLimit = b.sharedReceivePollerFragmentLimit;
    }

    /**
     * Возвращает Aeron directory.
     *
     * <p>Returns the Aeron directory.</p>
     *
     * @return путь к Aeron directory / Aeron directory path
     */
    public String aeronDir() {
        return aeronDir;
    }

    /**
     * Возвращает, должен ли узел поднять собственный embedded MediaDriver.
     *
     * <p>Returns whether this node should start its own embedded MediaDriver.</p>
     *
     * <p>Когда значение равно {@code false}, узел подключается к уже
     * запущенному external MediaDriver через {@link #aeronDir()}.</p>
     *
     * <p>When {@code false}, the node connects to an already running external
     * MediaDriver through {@link #aeronDir()}.</p>
     *
     * @return {@code true}, если нужно поднять embedded MediaDriver /
     * {@code true} if an embedded MediaDriver should be started
     */
    public boolean embeddedDriver() {
        return embeddedDriver;
    }

    /**
     * Возвращает, должны ли каналы этого узла использовать shared receive
     * poller вместо отдельного RX thread на каждый канал.
     *
     * <p>Returns whether channels created by this node should use the shared
     * receive poller instead of one dedicated RX thread per channel.</p>
     *
     * <p>Когда режим включен, каналы остаются логически изолированными, но их
     * receive polling multiplex-ится через node-owned poller lanes.</p>
     *
     * <p>When enabled, channels stay logically isolated, but their receive
     * polling is multiplexed through node-owned poller lanes.</p>
     *
     * @return {@code true}, если включен shared receive polling /
     * {@code true} if shared receive polling is enabled
     */
    public boolean sharedReceivePoller() {
        return sharedReceivePoller;
    }

    /**
     * Возвращает число shared receive-poller lanes, создаваемых на каждый
     * {@link IdleStrategyKind}.
     *
     * <p>Returns the number of shared receive-poller lanes created per
     * {@link IdleStrategyKind}.</p>
     *
     * <p>Это число polling thread-ов, доступных для каналов с одной и той же
     * idle strategy. Большее число lanes может снизить fan-in на загруженном
     * узле, но слишком большое число lanes способно увеличить CPU contention.</p>
     *
     * <p>This is the number of polling threads available for channels that use
     * the same idle strategy. More lanes can reduce fan-in on busy nodes, but
     * too many lanes can also increase CPU contention.</p>
     *
     * @return число lanes на один idle strategy kind /
     * lane count per idle-strategy kind
     */
    public int sharedReceivePollerThreads() {
        return sharedReceivePollerThreads;
    }

    /**
     * Возвращает fragment limit, который использует каждый shared poller lane
     * за один проход polling loop-а.
     *
     * <p>Returns the fragment limit used by each shared poller lane during a
     * single subscription poll pass.</p>
     *
     * <p>Большие значения помогают быстрее дренировать загруженные каналы.
     * Меньшие значения могут улучшить fairness между большим числом каналов.</p>
     *
     * <p>Higher values can help drain busy channels faster. Lower values can
     * improve fairness across many channels.</p>
     *
     * @return fragment limit на один shared poll cycle /
     * fragment limit per shared poll cycle
     */
    public int sharedReceivePollerFragmentLimit() {
        return sharedReceivePollerFragmentLimit;
    }

    /**
     * Создаёт builder конфигурации узла.
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
        private boolean sharedReceivePoller = true;
        private int sharedReceivePollerThreads = 4;
        private int sharedReceivePollerFragmentLimit = 16;

        /**
         * Создаёт builder с настройками по умолчанию.
         *
         * <p>Creates a builder with default settings.</p>
         */
        public Builder() {
        }

        /**
         * Задаёт Aeron directory, через который узел подключается к MediaDriver.
         *
         * <p>Sets the Aeron directory used to connect to the MediaDriver.</p>
         *
         * @param v путь к Aeron directory / Aeron directory path
         * @return этот builder / this builder
         */
        public Builder aeronDir(final String v) {
            this.aeronDir = v;
            return this;
        }

        /**
         * Задаёт, нужно ли запускать embedded MediaDriver внутри этого process-а.
         *
         * <p>Sets whether an embedded MediaDriver should be started inside this
         * process.</p>
         *
         * @param v {@code true}, чтобы использовать embedded MediaDriver /
         *          {@code true} to use an embedded MediaDriver
         * @return этот builder / this builder
         */
        public Builder embeddedDriver(final boolean v) {
            this.embeddedDriver = v;
            return this;
        }

        /**
         * Включает или отключает shared receive poller для каналов, созданных
         * этим узлом.
         *
         * <p>Enables or disables the shared receive poller for channels created by
         * this node.</p>
         *
         * @param v {@code true}, чтобы использовать shared receive polling /
         *          {@code true} to use shared receive polling
         * @return этот builder / this builder
         */
        public Builder sharedReceivePoller(final boolean v) {
            this.sharedReceivePoller = v;
            return this;
        }

        /**
         * Задаёт число shared receive-poller lanes на каждый
         * {@link IdleStrategyKind}.
         *
         * <p>Sets the number of shared receive-poller lanes per
         * {@link IdleStrategyKind}.</p>
         *
         * @param v число lanes; должно быть {@code >= 1} /
         *          lane count; must be {@code >= 1}
         * @return этот builder / this builder
         */
        public Builder sharedReceivePollerThreads(final int v) {
            this.sharedReceivePollerThreads = v;
            return this;
        }

        /**
         * Задаёт fragment limit, который использует каждый shared poller lane
         * на каждой итерации polling loop-а.
         *
         * <p>Sets the fragment limit used by each shared poller lane on every poll
         * iteration.</p>
         *
         * @param v fragment limit; должно быть {@code >= 1} /
         *          fragment limit; must be {@code >= 1}
         * @return этот builder / this builder
         */
        public Builder sharedReceivePollerFragmentLimit(final int v) {
            this.sharedReceivePollerFragmentLimit = v;
            return this;
        }

        /**
         * Проверяет настройки и создаёт immutable node configuration.
         *
         * <p>Builds an immutable node configuration.</p>
         *
         * @return конфигурация узла / node configuration
         */
        public NodeConfig build() {
            if (aeronDir == null || aeronDir.isEmpty()) {
                throw new IllegalArgumentException("aeronDir is required");
            }
            if (sharedReceivePollerThreads < 1) {
                throw new IllegalArgumentException("sharedReceivePollerThreads >= 1");
            }
            if (sharedReceivePollerFragmentLimit < 1) {
                throw new IllegalArgumentException("sharedReceivePollerFragmentLimit >= 1");
            }
            return new NodeConfig(this);
        }
    }
}