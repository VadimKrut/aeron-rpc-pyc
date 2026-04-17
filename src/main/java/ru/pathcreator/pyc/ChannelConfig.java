package ru.pathcreator.pyc;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * Конфигурация одного RPC-канала.
 *
 * <h3>Ключевые группы настроек</h3>
 *
 * <ul>
 *   <li><b>Сеть</b>: localEndpoint / remoteEndpoint / streamId.
 *       sessionId опционален (0 = Aeron выберет).</li>
 *   <li><b>Таймауты</b>: defaultTimeout (для call), offerTimeout (для
 *       Publication back-pressure), heartbeat.</li>
 *   <li><b>Idle стратегия rx-треда</b>: {@link #rxIdleStrategy()}.
 *       {@code YIELDING} (default) — rx-тред = 100% одного ядра, низкая
 *       latency. {@code BACKOFF} — ~0% CPU при idle, но штраф на Windows
 *       до ~1 ms на первое сообщение после idle. {@code BUSY_SPIN} —
 *       минимум latency, 100% CPU постоянно.</li>
 *   <li><b>Offload executor</b>: {@link #offloadExecutor()}. Куда
 *       сабмитить серверные handler-ы. Если null — node-default
 *       (virtual-thread-per-task). Специальное значение {@link
 *       #DIRECT_EXECUTOR} = выполнять handler прямо в rx-треде
 *       (zero-copy, минимальная latency, но блокирует приём на время
 *       handler-а — использовать ТОЛЬКО для гарантированно быстрых
 *       handler-ов типа ACK/lookup &lt; 5 µs).</li>
 *   <li><b>Backpressure</b>: BLOCK или FAIL_FAST.</li>
 *   <li><b>maxMessageSize</b>: hard cap 16 MiB.</li>
 * </ul>
 */
public final class ChannelConfig {

    public static final int DEFAULT_MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    /**
     * Маркерное значение: "выполнять handler прямо в rx-треде".
     *
     * <p>Используется так:
     * <pre>
     * ChannelConfig.builder()
     *     .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
     *     ...
     * </pre>
     *
     * <p>Тогда RpcChannel не копирует payload и не сабмитит в executor —
     * зовёт handler прямо из rx-треда. Минимум latency, но rx-тред
     * блокируется на время handler-а — годится только для очень быстрых
     * handler-ов.</p>
     */
    public static final ExecutorService DIRECT_EXECUTOR = new DirectExecutorMarker();

    // ---- fields ----

    private final String localEndpoint;
    private final String remoteEndpoint;
    private final int streamId;
    private final int sessionId;
    private final int mtuLength;
    private final int termLength;
    private final int socketSndBuf;
    private final int socketRcvBuf;

    private final Duration defaultTimeout;
    private final Duration offerTimeout;
    private final Duration heartbeatInterval;
    private final int heartbeatMissedLimit;

    private final int maxMessageSize;

    private final BackpressurePolicy backpressurePolicy;
    private final IdleStrategyKind rxIdleStrategy;

    private final int pendingPoolCapacity;
    private final int registryInitialCapacity;

    private final ExecutorService offloadExecutor;
    private final int offloadTaskPoolSize;
    private final int offloadCopyPoolSize;
    private final int offloadCopyBufferSize;

    private ChannelConfig(final Builder b) {
        this.localEndpoint = b.localEndpoint;
        this.remoteEndpoint = b.remoteEndpoint;
        this.streamId = b.streamId;
        this.sessionId = b.sessionId;
        this.mtuLength = b.mtuLength;
        this.termLength = b.termLength;
        this.socketSndBuf = b.socketSndBuf;
        this.socketRcvBuf = b.socketRcvBuf;
        this.defaultTimeout = b.defaultTimeout;
        this.offerTimeout = b.offerTimeout;
        this.heartbeatInterval = b.heartbeatInterval;
        this.heartbeatMissedLimit = b.heartbeatMissedLimit;
        this.maxMessageSize = b.maxMessageSize;
        this.backpressurePolicy = b.backpressurePolicy;
        this.rxIdleStrategy = b.rxIdleStrategy;
        this.pendingPoolCapacity = b.pendingPoolCapacity;
        this.registryInitialCapacity = b.registryInitialCapacity;
        this.offloadExecutor = b.offloadExecutor;
        this.offloadTaskPoolSize = b.offloadTaskPoolSize;
        this.offloadCopyPoolSize = b.offloadCopyPoolSize;
        this.offloadCopyBufferSize = b.offloadCopyBufferSize;
    }

    // ---- getters ----

    public String localEndpoint() {
        return localEndpoint;
    }

    public String remoteEndpoint() {
        return remoteEndpoint;
    }

    public int streamId() {
        return streamId;
    }

    public int sessionId() {
        return sessionId;
    }

    public int mtuLength() {
        return mtuLength;
    }

    public int termLength() {
        return termLength;
    }

    public int socketSndBuf() {
        return socketSndBuf;
    }

    public int socketRcvBuf() {
        return socketRcvBuf;
    }

    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    public Duration offerTimeout() {
        return offerTimeout;
    }

    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    public int heartbeatMissedLimit() {
        return heartbeatMissedLimit;
    }

    public int maxMessageSize() {
        return maxMessageSize;
    }

    public BackpressurePolicy backpressurePolicy() {
        return backpressurePolicy;
    }

    public IdleStrategyKind rxIdleStrategy() {
        return rxIdleStrategy;
    }

    public int pendingPoolCapacity() {
        return pendingPoolCapacity;
    }

    public int registryInitialCapacity() {
        return registryInitialCapacity;
    }

    public ExecutorService offloadExecutor() {
        return offloadExecutor;
    }

    public int offloadTaskPoolSize() {
        return offloadTaskPoolSize;
    }

    public int offloadCopyPoolSize() {
        return offloadCopyPoolSize;
    }

    public int offloadCopyBufferSize() {
        return offloadCopyBufferSize;
    }

    public boolean isDirectExecutor() {
        return offloadExecutor instanceof DirectExecutorMarker;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String localEndpoint;
        private String remoteEndpoint;
        private int streamId = 1001;
        private int sessionId = 0;
        private int mtuLength = 1408;
        private int termLength = 16 * 1024 * 1024;
        private int socketSndBuf = 256 * 1024;
        private int socketRcvBuf = 256 * 1024;

        private Duration defaultTimeout = Duration.ofSeconds(5);
        private Duration offerTimeout = Duration.ofSeconds(3);
        private Duration heartbeatInterval = Duration.ofSeconds(1);
        private int heartbeatMissedLimit = 3;

        private int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;

        private BackpressurePolicy backpressurePolicy = BackpressurePolicy.BLOCK;
        private IdleStrategyKind rxIdleStrategy = IdleStrategyKind.YIELDING;

        private int pendingPoolCapacity = 4096;
        private int registryInitialCapacity = 1024;

        private ExecutorService offloadExecutor = null;  // null = node default (virtual threads)
        private int offloadTaskPoolSize = 1024;
        private int offloadCopyPoolSize = 256;
        private int offloadCopyBufferSize = 8 * 1024;

        public Builder localEndpoint(final String v) {
            this.localEndpoint = v;
            return this;
        }

        public Builder remoteEndpoint(final String v) {
            this.remoteEndpoint = v;
            return this;
        }

        public Builder streamId(final int v) {
            this.streamId = v;
            return this;
        }

        public Builder sessionId(final int v) {
            this.sessionId = v;
            return this;
        }

        public Builder mtuLength(final int v) {
            this.mtuLength = v;
            return this;
        }

        public Builder termLength(final int v) {
            this.termLength = v;
            return this;
        }

        public Builder socketSndBuf(final int v) {
            this.socketSndBuf = v;
            return this;
        }

        public Builder socketRcvBuf(final int v) {
            this.socketRcvBuf = v;
            return this;
        }

        public Builder defaultTimeout(final Duration v) {
            this.defaultTimeout = v;
            return this;
        }

        public Builder offerTimeout(final Duration v) {
            this.offerTimeout = v;
            return this;
        }

        public Builder heartbeatInterval(final Duration v) {
            this.heartbeatInterval = v;
            return this;
        }

        public Builder heartbeatMissedLimit(final int v) {
            this.heartbeatMissedLimit = v;
            return this;
        }

        public Builder maxMessageSize(final int v) {
            this.maxMessageSize = v;
            return this;
        }

        public Builder backpressurePolicy(final BackpressurePolicy v) {
            this.backpressurePolicy = v;
            return this;
        }

        public Builder rxIdleStrategy(final IdleStrategyKind v) {
            this.rxIdleStrategy = v;
            return this;
        }

        public Builder pendingPoolCapacity(final int v) {
            this.pendingPoolCapacity = v;
            return this;
        }

        public Builder registryInitialCapacity(final int v) {
            this.registryInitialCapacity = v;
            return this;
        }

        public Builder offloadExecutor(final ExecutorService v) {
            this.offloadExecutor = v;
            return this;
        }

        public Builder offloadTaskPoolSize(final int v) {
            this.offloadTaskPoolSize = v;
            return this;
        }

        public Builder offloadCopyPoolSize(final int v) {
            this.offloadCopyPoolSize = v;
            return this;
        }

        public Builder offloadCopyBufferSize(final int v) {
            this.offloadCopyBufferSize = v;
            return this;
        }

        public ChannelConfig build() {
            if (localEndpoint == null || localEndpoint.isEmpty())
                throw new IllegalArgumentException("localEndpoint is required");
            if (remoteEndpoint == null || remoteEndpoint.isEmpty())
                throw new IllegalArgumentException("remoteEndpoint is required");
            if (Integer.bitCount(termLength) != 1)
                throw new IllegalArgumentException("termLength must be power of two");
            if (maxMessageSize > DEFAULT_MAX_MESSAGE_SIZE)
                throw new IllegalArgumentException("maxMessageSize > 16 MiB not supported by RpcChannel. " + "For larger payloads use LargePayloadRpcChannel.");
            if (maxMessageSize < 128)
                throw new IllegalArgumentException("maxMessageSize too small");
            if (heartbeatMissedLimit < 1)
                throw new IllegalArgumentException("heartbeatMissedLimit >= 1");
            if (rxIdleStrategy == null)
                throw new IllegalArgumentException("rxIdleStrategy is required");
            if (backpressurePolicy == null)
                throw new IllegalArgumentException("backpressurePolicy is required");
            return new ChannelConfig(this);
        }
    }
}