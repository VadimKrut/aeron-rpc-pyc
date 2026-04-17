package ru.pathcreator.pyc;

import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * Настройки одного RPC-канала.
 * <p>
 * Ключевые группы настроек:
 * <p>
 * === Сетевые ===
 * localEndpoint   — UDP, где слушаем.
 * remoteEndpoint  — UDP, куда шлём.
 * streamId        — Aeron stream id. На одном Node может быть несколько
 * каналов с разными streamId.
 * sessionId       — явный Publication sessionId (опц.), 0 = Aeron сам.
 * mtuLength / termLength / socketSndBuf / socketRcvBuf — low-level Aeron.
 * <p>
 * === Timeouts ===
 * defaultTimeout  — default на call() (per-call override доступен).
 * offerTimeout    — сколько sender-тред ждёт на BACK_PRESSURED publication
 * и сколько caller ждёт на full TX queue в режиме BLOCK.
 * <p>
 * === Heartbeat ===
 * heartbeatInterval / heartbeatMissedLimit — детекция DOWN/UP.
 * <p>
 * === Max size и capacity ===
 * maxMessageSize      — HARD limit на один envelope+payload. Default 16 MiB.
 * Больше 16 MiB → PayloadTooLargeException; для них
 * отдельный LargePayloadRpcChannel (TODO).
 * txStagingCapacity   — размер одного TxFrame buffer (должен вмещать
 * envelope+payload для типичного запроса).
 * <p>
 * === TX queue / backpressure ===
 * backpressurePolicy   — что делать когда TX queue заполнена.
 * По умолчанию BLOCK (самое безопасное для RPC).
 * senderQueueCapacity  — ёмкость MPSC-очереди TxFrame'ов между caller-ами
 * и sender-тредом. Должна быть степенью двойки.
 * txFramePoolSize      — число переиспользуемых TxFrame в пуле.
 * Рекомендуется >= senderQueueCapacity.
 * <p>
 * === Pending RPC / pools ===
 * pendingPoolCapacity  — initial размер пула PendingCall.
 * registryInitialCap   — initial capacity HashMap-а pending-calls.
 * <p>
 * === OFFLOAD executor ===
 * offloadExecutor      — куда сабмитить OFFLOAD-handler-ы. Null = default
 * (virtual thread per task executor, создаётся Node-ом
 * и шарится между каналами).
 * offloadTaskPoolSize  — пул OffloadTask-ов (pooled Runnable).
 * offloadCopyPoolSize / offloadCopyBufferSize — пул буферов для копирования
 * payload при OFFLOAD.
 */
public final class ChannelConfig {

    public static final int DEFAULT_MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    // network
    private final int streamId;
    private final int sessionId;
    private final int mtuLength;
    private final int termLength;
    private final int socketSndBuf;
    private final int socketRcvBuf;
    private final String localEndpoint;
    private final String remoteEndpoint;

    // timeouts
    private final Duration offerTimeout;
    private final Duration defaultTimeout;
    private final int heartbeatMissedLimit;
    private final Duration heartbeatInterval;

    // size
    private final int maxMessageSize;
    private final int txStagingCapacity;

    // tx queue
    private final int txFramePoolSize;
    private final int senderQueueCapacity;
    private final BackpressurePolicy backpressurePolicy;

    // wire batching (sender-side)
    private final int maxBatchMessages;
    private final boolean wireBatchingEnabled;

    // pending
    private final int pendingPoolCapacity;
    private final int registryInitialCapacity;

    // offload
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
        this.txStagingCapacity = b.txStagingCapacity;
        this.backpressurePolicy = b.backpressurePolicy;
        this.senderQueueCapacity = b.senderQueueCapacity;
        this.txFramePoolSize = b.txFramePoolSize;
        this.wireBatchingEnabled = b.wireBatchingEnabled;
        this.maxBatchMessages = b.maxBatchMessages;
        this.pendingPoolCapacity = b.pendingPoolCapacity;
        this.registryInitialCapacity = b.registryInitialCapacity;
        this.offloadExecutor = b.offloadExecutor;
        this.offloadTaskPoolSize = b.offloadTaskPoolSize;
        this.offloadCopyPoolSize = b.offloadCopyPoolSize;
        this.offloadCopyBufferSize = b.offloadCopyBufferSize;
    }

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

    public int txStagingCapacity() {
        return txStagingCapacity;
    }

    public BackpressurePolicy backpressurePolicy() {
        return backpressurePolicy;
    }

    public int senderQueueCapacity() {
        return senderQueueCapacity;
    }

    public int txFramePoolSize() {
        return txFramePoolSize;
    }

    public boolean wireBatchingEnabled() {
        return wireBatchingEnabled;
    }

    public int maxBatchMessages() {
        return maxBatchMessages;
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
        private int txStagingCapacity = 4096;

        private BackpressurePolicy backpressurePolicy = BackpressurePolicy.BLOCK;
        private int senderQueueCapacity = 4096;
        private int txFramePoolSize = 4096;

        // Wire-batching: "opportunistic drain" — склеивает уже готовые
        // сообщения в один tryClaim-слот. Не добавляет задержек (не ждём,
        // пока соберётся batch), поэтому безопасно включать по умолчанию.
        private boolean wireBatchingEnabled = true;
        private int maxBatchMessages = 16;

        private int pendingPoolCapacity = 4096;
        private int registryInitialCapacity = 1024;

        private ExecutorService offloadExecutor = null;   // null = node default
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

        public Builder txStagingCapacity(final int v) {
            this.txStagingCapacity = v;
            return this;
        }

        public Builder backpressurePolicy(final BackpressurePolicy v) {
            this.backpressurePolicy = v;
            return this;
        }

        public Builder senderQueueCapacity(final int v) {
            this.senderQueueCapacity = v;
            return this;
        }

        public Builder txFramePoolSize(final int v) {
            this.txFramePoolSize = v;
            return this;
        }

        public Builder wireBatchingEnabled(final boolean v) {
            this.wireBatchingEnabled = v;
            return this;
        }

        public Builder maxBatchMessages(final int v) {
            this.maxBatchMessages = v;
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
            if (Integer.bitCount(senderQueueCapacity) != 1)
                throw new IllegalArgumentException("senderQueueCapacity must be power of two");
            if (maxMessageSize > DEFAULT_MAX_MESSAGE_SIZE) {
                throw new IllegalArgumentException("maxMessageSize > 16 MiB not supported by RpcChannel. " + "For larger payloads use LargePayloadRpcChannel (not implemented yet).");
            }
            if (maxMessageSize < 128) {
                throw new IllegalArgumentException("maxMessageSize too small");
            }
            if (txStagingCapacity > maxMessageSize) {
                throw new IllegalArgumentException("txStagingCapacity > maxMessageSize");
            }
            if (heartbeatMissedLimit < 1) {
                throw new IllegalArgumentException("heartbeatMissedLimit >= 1");
            }
            if (maxBatchMessages < 1) {
                throw new IllegalArgumentException("maxBatchMessages >= 1");
            }
            return new ChannelConfig(this);
        }
    }
}