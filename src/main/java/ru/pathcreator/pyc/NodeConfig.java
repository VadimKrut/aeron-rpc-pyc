package ru.pathcreator.pyc;

/**
 * Configuration of the root {@link RpcNode}.
 *
 * <p>The node owns the Aeron client, the optional embedded MediaDriver, and
 * the optional shared receive poller used by channels created through that
 * node.</p>
 *
 * <p>Main groups of settings:</p>
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
     * Returns the Aeron directory.
     *
     * @return Aeron directory path
     */
    public String aeronDir() {
        return aeronDir;
    }

    /**
     * Returns whether this node should start its own embedded MediaDriver.
     *
     * <p>When {@code false}, the node connects to an already running external
     * MediaDriver through {@link #aeronDir()}.</p>
     *
     * @return {@code true} if an embedded MediaDriver should be started
     */
    public boolean embeddedDriver() {
        return embeddedDriver;
    }

    /**
     * Returns whether channels created by this node should use the shared
     * receive poller instead of one dedicated RX thread per channel.
     *
     * <p>When enabled, channels stay logically isolated, but their receive
     * polling is multiplexed through node-owned poller lanes.</p>
     *
     * @return {@code true} if shared receive polling is enabled
     */
    public boolean sharedReceivePoller() {
        return sharedReceivePoller;
    }

    /**
     * Returns the number of shared receive-poller lanes created per
     * {@link IdleStrategyKind}.
     *
     * <p>This is the number of polling threads available for channels that use
     * the same idle strategy. More lanes can reduce fan-in on busy nodes, but
     * too many lanes can also increase CPU contention.</p>
     *
     * @return lane count per idle-strategy kind
     */
    public int sharedReceivePollerThreads() {
        return sharedReceivePollerThreads;
    }

    /**
     * Returns the fragment limit used by each shared poller lane during a
     * single subscription poll pass.
     *
     * <p>Higher values can help drain busy channels faster. Lower values can
     * improve fairness across many channels.</p>
     *
     * @return fragment limit per shared poll cycle
     */
    public int sharedReceivePollerFragmentLimit() {
        return sharedReceivePollerFragmentLimit;
    }

    /**
     * Creates a node configuration builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder used to create {@link NodeConfig} instances.
     */
    public static final class Builder {
        private String aeronDir;
        private boolean embeddedDriver = true;
        private boolean sharedReceivePoller = true;
        private int sharedReceivePollerThreads = 4;
        private int sharedReceivePollerFragmentLimit = 16;

        /**
         * Creates a builder with default settings.
         */
        public Builder() {
        }

        /**
         * Sets the Aeron directory used to connect to the MediaDriver.
         *
         * @param v Aeron directory path
         * @return this builder
         */
        public Builder aeronDir(final String v) {
            this.aeronDir = v;
            return this;
        }

        /**
         * Sets whether an embedded MediaDriver should be started inside this
         * process.
         *
         * @param v {@code true} to use an embedded MediaDriver
         * @return this builder
         */
        public Builder embeddedDriver(final boolean v) {
            this.embeddedDriver = v;
            return this;
        }

        /**
         * Enables or disables the shared receive poller for channels created by
         * this node.
         *
         * @param v {@code true} to use shared receive polling
         * @return this builder
         */
        public Builder sharedReceivePoller(final boolean v) {
            this.sharedReceivePoller = v;
            return this;
        }

        /**
         * Sets the number of shared receive-poller lanes per
         * {@link IdleStrategyKind}.
         *
         * @param v lane count; must be {@code >= 1}
         * @return this builder
         */
        public Builder sharedReceivePollerThreads(final int v) {
            this.sharedReceivePollerThreads = v;
            return this;
        }

        /**
         * Sets the fragment limit used by each shared poller lane on every poll
         * iteration.
         *
         * @param v fragment limit; must be {@code >= 1}
         * @return this builder
         */
        public Builder sharedReceivePollerFragmentLimit(final int v) {
            this.sharedReceivePollerFragmentLimit = v;
            return this;
        }

        /**
         * Builds an immutable node configuration.
         *
         * @return node configuration
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