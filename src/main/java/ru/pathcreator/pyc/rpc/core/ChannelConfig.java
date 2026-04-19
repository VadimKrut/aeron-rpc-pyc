package ru.pathcreator.pyc.rpc.core;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * Конфигурация одного RPC-канала.
 *
 * <h2>Ключевые группы настроек</h2>
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
 *   <li><b>maxMessageSize</b>: hard cap 16 MiB, validated and supported by the
 *       regular {@link RpcChannel} path.</li>
 * </ul>
 *
 * <p>Configuration of one RPC channel. It defines Aeron endpoints, stream
 * parameters, timeouts, heartbeat behavior, backpressure policy, receive idle
 * strategy, and server handler offload settings.</p>
 */
public final class ChannelConfig {

    /**
     * Максимальный размер сообщения по умолчанию, 16 MiB.
     *
     * <p>Default maximum message size, 16 MiB.</p>
     */
    public static final int DEFAULT_MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    /**
     * Маркерное значение: "выполнять handler прямо в rx-треде".
     *
     * <p>Используется так:
     * <pre>{@code
     * ChannelConfig.builder()
     *     .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
     *     ...
     * }</pre>
     *
     * <p>Тогда RpcChannel не копирует payload и не сабмитит в executor —
     * зовёт handler прямо из rx-треда. Минимум latency, но rx-тред
     * блокируется на время handler-а — годится только для очень быстрых
     * handler-ов.</p>
     */
    public static final ExecutorService DIRECT_EXECUTOR = new DirectExecutorMarker();

    // ---- fields ----
    private final int streamId;
    private final int sessionId;
    private final int mtuLength;
    private final int termLength;
    private final int socketSndBuf;
    private final int socketRcvBuf;
    private final String localEndpoint;
    private final String remoteEndpoint;

    private final Duration offerTimeout;
    private final Duration defaultTimeout;
    private final int heartbeatMissedLimit;
    private final Duration heartbeatInterval;

    private final int maxMessageSize;

    private final IdleStrategyKind rxIdleStrategy;
    private final BackpressurePolicy backpressurePolicy;
    private final ReconnectStrategy reconnectStrategy;

    private final int pendingPoolCapacity;
    private final int registryInitialCapacity;

    private final int offloadTaskPoolSize;
    private final int offloadCopyPoolSize;
    private final int offloadCopyBufferSize;
    private final ExecutorService offloadExecutor;

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
        this.reconnectStrategy = b.reconnectStrategy;
        this.rxIdleStrategy = b.rxIdleStrategy;
        this.pendingPoolCapacity = b.pendingPoolCapacity;
        this.registryInitialCapacity = b.registryInitialCapacity;
        this.offloadExecutor = b.offloadExecutor;
        this.offloadTaskPoolSize = b.offloadTaskPoolSize;
        this.offloadCopyPoolSize = b.offloadCopyPoolSize;
        this.offloadCopyBufferSize = b.offloadCopyBufferSize;
    }

    // ---- getters ----

    /**
     * Возвращает локальный UDP endpoint канала.
     *
     * <p>Returns the local UDP endpoint of the channel.</p>
     *
     * @return локальный endpoint / local endpoint
     */
    public String localEndpoint() {
        return localEndpoint;
    }

    /**
     * Возвращает удаленный UDP endpoint канала.
     *
     * <p>Returns the remote UDP endpoint of the channel.</p>
     *
     * @return удаленный endpoint / remote endpoint
     */
    public String remoteEndpoint() {
        return remoteEndpoint;
    }

    /**
     * Возвращает Aeron stream id.
     *
     * <p>Returns the Aeron stream id.</p>
     *
     * @return stream id / stream id
     */
    public int streamId() {
        return streamId;
    }

    /**
     * Возвращает Aeron session id.
     *
     * <p>Returns the Aeron session id.</p>
     *
     * @return session id; {@code 0} означает выбор Aeron / session id; {@code 0} means Aeron-selected
     */
    public int sessionId() {
        return sessionId;
    }

    /**
     * Возвращает MTU канала.
     *
     * <p>Returns the channel MTU length.</p>
     *
     * @return MTU в байтах / MTU length in bytes
     */
    public int mtuLength() {
        return mtuLength;
    }

    /**
     * Возвращает длину term buffer.
     *
     * <p>Returns the term buffer length.</p>
     *
     * @return длина term buffer в байтах / term buffer length in bytes
     */
    public int termLength() {
        return termLength;
    }

    /**
     * Возвращает размер socket send buffer.
     *
     * <p>Returns the socket send buffer size.</p>
     *
     * @return размер send buffer в байтах / send buffer size in bytes
     */
    public int socketSndBuf() {
        return socketSndBuf;
    }

    /**
     * Возвращает размер socket receive buffer.
     *
     * <p>Returns the socket receive buffer size.</p>
     *
     * @return размер receive buffer в байтах / receive buffer size in bytes
     */
    public int socketRcvBuf() {
        return socketRcvBuf;
    }

    /**
     * Возвращает таймаут RPC-вызова по умолчанию.
     *
     * <p>Returns the default RPC call timeout.</p>
     *
     * @return таймаут вызова по умолчанию / default call timeout
     */
    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    /**
     * Возвращает таймаут ожидания отправки при backpressure.
     *
     * <p>Returns the offer timeout used while handling backpressure.</p>
     *
     * @return таймаут offer / offer timeout
     */
    public Duration offerTimeout() {
        return offerTimeout;
    }

    /**
     * Возвращает интервал heartbeat.
     *
     * <p>Returns the heartbeat interval.</p>
     *
     * @return интервал heartbeat / heartbeat interval
     */
    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * Возвращает допустимое число пропущенных heartbeat-интервалов.
     *
     * <p>Returns the allowed number of missed heartbeat intervals.</p>
     *
     * @return лимит пропущенных heartbeat / missed heartbeat limit
     */
    public int heartbeatMissedLimit() {
        return heartbeatMissedLimit;
    }

    /**
     * Возвращает максимальный размер сообщения.
     *
     * <p>Returns the maximum message size.</p>
     *
     * @return максимальный размер сообщения в байтах / maximum message size in bytes
     */
    public int maxMessageSize() {
        return maxMessageSize;
    }

    /**
     * Возвращает политику обработки backpressure.
     *
     * <p>Returns the backpressure handling policy.</p>
     *
     * @return политика backpressure / backpressure policy
     */
    public BackpressurePolicy backpressurePolicy() {
        return backpressurePolicy;
    }

    /**
     * Returns the reconnect behavior used when a call starts or a publish
     * attempt happens while the channel is temporarily disconnected.
     *
     * <p>{@link ReconnectStrategy#FAIL_FAST} preserves the old behavior and
     * fails immediately. {@link ReconnectStrategy#WAIT_FOR_CONNECTION} waits
     * for the existing Aeron publication and heartbeat path to become
     * connected again within the call timeout.</p>
     *
     * @return reconnect strategy for this channel
     */
    public ReconnectStrategy reconnectStrategy() {
        return reconnectStrategy;
    }

    /**
     * Возвращает idle-стратегию rx-потока.
     *
     * <p>Returns the receive thread idle strategy.</p>
     *
     * @return idle-стратегия rx-потока / receive idle strategy
     */
    public IdleStrategyKind rxIdleStrategy() {
        return rxIdleStrategy;
    }

    /**
     * Возвращает емкость пула ожидающих вызовов.
     *
     * <p>Returns the pending call pool capacity.</p>
     *
     * @return емкость pending-пула / pending pool capacity
     */
    public int pendingPoolCapacity() {
        return pendingPoolCapacity;
    }

    /**
     * Возвращает начальную емкость реестра ожидающих вызовов.
     *
     * <p>Returns the initial capacity of the pending call registry.</p>
     *
     * @return начальная емкость реестра / registry initial capacity
     */
    public int registryInitialCapacity() {
        return registryInitialCapacity;
    }

    /**
     * Возвращает executor для offload-обработчиков.
     *
     * <p>Returns the executor used for offloaded handlers.</p>
     *
     * @return executor или {@code null} для node-default executor /
     * executor or {@code null} for the node-default executor
     */
    public ExecutorService offloadExecutor() {
        return offloadExecutor;
    }

    /**
     * Возвращает размер пула offload-задач.
     *
     * <p>Returns the offload task pool size.</p>
     *
     * @return размер пула offload-задач / offload task pool size
     */
    public int offloadTaskPoolSize() {
        return offloadTaskPoolSize;
    }

    /**
     * Возвращает размер пула буферов копирования для offload.
     *
     * <p>Returns the offload copy buffer pool size.</p>
     *
     * @return размер пула copy-буферов / copy buffer pool size
     */
    public int offloadCopyPoolSize() {
        return offloadCopyPoolSize;
    }

    /**
     * Возвращает размер одного буфера копирования для offload.
     *
     * <p>Returns the size of one offload copy buffer.</p>
     *
     * @return размер copy-буфера в байтах / copy buffer size in bytes
     */
    public int offloadCopyBufferSize() {
        return offloadCopyBufferSize;
    }

    /**
     * Проверяет, настроено ли выполнение обработчиков прямо в rx-потоке.
     *
     * <p>Checks whether handlers are configured to run directly in the receive thread.</p>
     *
     * @return {@code true}, если используется {@link #DIRECT_EXECUTOR} /
     * {@code true} when {@link #DIRECT_EXECUTOR} is used
     */
    public boolean isDirectExecutor() {
        return offloadExecutor instanceof DirectExecutorMarker;
    }

    /**
     * Создает builder конфигурации канала.
     *
     * <p>Creates a channel configuration builder.</p>
     *
     * @return новый builder / new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder для создания {@link ChannelConfig}.
     *
     * <p>Builder used to create {@link ChannelConfig} instances.</p>
     */
    public static final class Builder {

        private String localEndpoint;
        private String remoteEndpoint;

        private int sessionId = 0;
        private int streamId = 1001;
        private int mtuLength = 1408;
        private int socketSndBuf = 256 * 1024;
        private int socketRcvBuf = 256 * 1024;
        private int termLength = 16 * 1024 * 1024;

        private int heartbeatMissedLimit = 3;
        private Duration offerTimeout = Duration.ofSeconds(3);
        private Duration defaultTimeout = Duration.ofSeconds(5);
        private Duration heartbeatInterval = Duration.ofSeconds(1);

        private int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;

        private IdleStrategyKind rxIdleStrategy = IdleStrategyKind.YIELDING;
        private BackpressurePolicy backpressurePolicy = BackpressurePolicy.BLOCK;
        private ReconnectStrategy reconnectStrategy = ReconnectStrategy.FAIL_FAST;

        private int pendingPoolCapacity = 4096;
        private int registryInitialCapacity = 1024;

        private int offloadTaskPoolSize = 1024;
        private int offloadCopyPoolSize = 256;
        private int offloadCopyBufferSize = 8 * 1024;
        private ExecutorService offloadExecutor = null;  // null = node default (virtual threads)

        /**
         * Создает builder с настройками по умолчанию.
         *
         * <p>Creates a builder with default settings.</p>
         */
        public Builder() {
        }

        /**
         * Задает локальный endpoint канала.
         *
         * <p>Sets the local channel endpoint.</p>
         *
         * @param v локальный endpoint / local endpoint
         * @return этот builder / this builder
         */
        public Builder localEndpoint(final String v) {
            this.localEndpoint = v;
            return this;
        }

        /**
         * Задает удаленный endpoint канала.
         *
         * <p>Sets the remote channel endpoint.</p>
         *
         * @param v удаленный endpoint / remote endpoint
         * @return этот builder / this builder
         */
        public Builder remoteEndpoint(final String v) {
            this.remoteEndpoint = v;
            return this;
        }

        /**
         * Задает Aeron stream id.
         *
         * <p>Sets the Aeron stream id.</p>
         *
         * @param v stream id / stream id
         * @return этот builder / this builder
         */
        public Builder streamId(final int v) {
            this.streamId = v;
            return this;
        }

        /**
         * Задает Aeron session id.
         *
         * <p>Sets the Aeron session id.</p>
         *
         * @param v session id; {@code 0} позволяет Aeron выбрать значение /
         *          session id; {@code 0} lets Aeron choose the value
         * @return этот builder / this builder
         */
        public Builder sessionId(final int v) {
            this.sessionId = v;
            return this;
        }

        /**
         * Задает MTU канала.
         *
         * <p>Sets the channel MTU length.</p>
         *
         * @param v MTU в байтах / MTU length in bytes
         * @return этот builder / this builder
         */
        public Builder mtuLength(final int v) {
            this.mtuLength = v;
            return this;
        }

        /**
         * Задает длину term buffer.
         *
         * <p>Sets the term buffer length.</p>
         *
         * @param v длина term buffer в байтах / term buffer length in bytes
         * @return этот builder / this builder
         */
        public Builder termLength(final int v) {
            this.termLength = v;
            return this;
        }

        /**
         * Задает размер socket send buffer.
         *
         * <p>Sets the socket send buffer size.</p>
         *
         * @param v размер send buffer в байтах / send buffer size in bytes
         * @return этот builder / this builder
         */
        public Builder socketSndBuf(final int v) {
            this.socketSndBuf = v;
            return this;
        }

        /**
         * Задает размер socket receive buffer.
         *
         * <p>Sets the socket receive buffer size.</p>
         *
         * @param v размер receive buffer в байтах / receive buffer size in bytes
         * @return этот builder / this builder
         */
        public Builder socketRcvBuf(final int v) {
            this.socketRcvBuf = v;
            return this;
        }

        /**
         * Задает таймаут RPC-вызова по умолчанию.
         *
         * <p>Sets the default RPC call timeout.</p>
         *
         * @param v таймаут вызова / call timeout
         * @return этот builder / this builder
         */
        public Builder defaultTimeout(final Duration v) {
            this.defaultTimeout = v;
            return this;
        }

        /**
         * Задает таймаут ожидания отправки при backpressure.
         *
         * <p>Sets the offer timeout used while handling backpressure.</p>
         *
         * @param v таймаут offer / offer timeout
         * @return этот builder / this builder
         */
        public Builder offerTimeout(final Duration v) {
            this.offerTimeout = v;
            return this;
        }

        /**
         * Задает интервал heartbeat.
         *
         * <p>Sets the heartbeat interval.</p>
         *
         * @param v интервал heartbeat / heartbeat interval
         * @return этот builder / this builder
         */
        public Builder heartbeatInterval(final Duration v) {
            this.heartbeatInterval = v;
            return this;
        }

        /**
         * Задает допустимое число пропущенных heartbeat-интервалов.
         *
         * <p>Sets the allowed number of missed heartbeat intervals.</p>
         *
         * @param v лимит пропущенных heartbeat / missed heartbeat limit
         * @return этот builder / this builder
         */
        public Builder heartbeatMissedLimit(final int v) {
            this.heartbeatMissedLimit = v;
            return this;
        }

        /**
         * Задает максимальный размер сообщения.
         *
         * <p>Sets the maximum message size.</p>
         *
         * @param v максимальный размер сообщения в байтах / maximum message size in bytes
         * @return этот builder / this builder
         */
        public Builder maxMessageSize(final int v) {
            this.maxMessageSize = v;
            return this;
        }

        /**
         * Задает политику обработки backpressure.
         *
         * <p>Sets the backpressure handling policy.</p>
         *
         * @param v политика backpressure / backpressure policy
         * @return этот builder / this builder
         */
        public Builder backpressurePolicy(final BackpressurePolicy v) {
            this.backpressurePolicy = v;
            return this;
        }

        /**
         * Sets how the channel should behave when a call begins or a publish
         * attempt occurs while the underlying transport is not connected.
         *
         * <p>Use {@link ReconnectStrategy#FAIL_FAST} when the caller should
         * immediately trigger fallback logic. Use
         * {@link ReconnectStrategy#WAIT_FOR_CONNECTION} when short reconnect
         * windows are acceptable and the request should wait for the current
         * Aeron path to recover.</p>
         *
         * @param v reconnect strategy
         * @return this builder
         */
        public Builder reconnectStrategy(final ReconnectStrategy v) {
            this.reconnectStrategy = v;
            return this;
        }

        /**
         * Задает idle-стратегию rx-потока.
         *
         * <p>Sets the receive thread idle strategy.</p>
         *
         * @param v idle-стратегия / idle strategy
         * @return этот builder / this builder
         */
        public Builder rxIdleStrategy(final IdleStrategyKind v) {
            this.rxIdleStrategy = v;
            return this;
        }

        /**
         * Задает емкость пула ожидающих вызовов.
         *
         * <p>Sets the pending call pool capacity.</p>
         *
         * @param v емкость pending-пула / pending pool capacity
         * @return этот builder / this builder
         */
        public Builder pendingPoolCapacity(final int v) {
            this.pendingPoolCapacity = v;
            return this;
        }

        /**
         * Задает начальную емкость реестра ожидающих вызовов.
         *
         * <p>Sets the initial capacity of the pending call registry.</p>
         *
         * @param v начальная емкость реестра / registry initial capacity
         * @return этот builder / this builder
         */
        public Builder registryInitialCapacity(final int v) {
            this.registryInitialCapacity = v;
            return this;
        }

        /**
         * Задает executor для offload-обработчиков.
         *
         * <p>Sets the executor used for offloaded handlers.</p>
         *
         * @param v executor; {@code null} означает node-default executor /
         *          executor; {@code null} means the node-default executor
         * @return этот builder / this builder
         */
        public Builder offloadExecutor(final ExecutorService v) {
            this.offloadExecutor = v;
            return this;
        }

        /**
         * Задает размер пула offload-задач.
         *
         * <p>Sets the offload task pool size.</p>
         *
         * @param v размер пула задач / task pool size
         * @return этот builder / this builder
         */
        public Builder offloadTaskPoolSize(final int v) {
            this.offloadTaskPoolSize = v;
            return this;
        }

        /**
         * Задает размер пула буферов копирования для offload.
         *
         * <p>Sets the offload copy buffer pool size.</p>
         *
         * @param v размер пула copy-буферов / copy buffer pool size
         * @return этот builder / this builder
         */
        public Builder offloadCopyPoolSize(final int v) {
            this.offloadCopyPoolSize = v;
            return this;
        }

        /**
         * Задает размер одного буфера копирования для offload.
         *
         * <p>Sets the size of one offload copy buffer.</p>
         *
         * @param v размер copy-буфера в байтах / copy buffer size in bytes
         * @return этот builder / this builder
         */
        public Builder offloadCopyBufferSize(final int v) {
            this.offloadCopyBufferSize = v;
            return this;
        }

        /**
         * Проверяет настройки и создает неизменяемую конфигурацию канала.
         *
         * <p>Validates settings and builds an immutable channel configuration.</p>
         *
         * @return конфигурация канала / channel configuration
         */
        public ChannelConfig build() {
            if (localEndpoint == null || localEndpoint.isEmpty())
                throw new IllegalArgumentException("localEndpoint is required");
            if (remoteEndpoint == null || remoteEndpoint.isEmpty())
                throw new IllegalArgumentException("remoteEndpoint is required");
            if (Integer.bitCount(termLength) != 1)
                throw new IllegalArgumentException("termLength must be power of two");
            if (maxMessageSize > DEFAULT_MAX_MESSAGE_SIZE)
                throw new IllegalArgumentException("maxMessageSize > 16 MiB not supported by RpcChannel.");
            if (maxMessageSize < 128)
                throw new IllegalArgumentException("maxMessageSize too small");
            if (heartbeatMissedLimit < 1)
                throw new IllegalArgumentException("heartbeatMissedLimit >= 1");
            if (rxIdleStrategy == null)
                throw new IllegalArgumentException("rxIdleStrategy is required");
            if (backpressurePolicy == null)
                throw new IllegalArgumentException("backpressurePolicy is required");
            if (reconnectStrategy == null)
                throw new IllegalArgumentException("reconnectStrategy is required");
            return new ChannelConfig(this);
        }
    }
}