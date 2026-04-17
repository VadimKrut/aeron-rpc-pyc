# aeron-rpc

Synchronous request/response RPC over Aeron UDP, designed for **low-latency Java systems**.

Provides a simple, blocking API (like HTTP), while keeping the internals optimized for throughput, minimal allocations,
and predictable latency.

---

## Why this exists

Most high-performance transports force you into async/reactive complexity.

This project aims to provide:

* **Synchronous API** — simple `call()` model
* **Low latency** — ~15–40 µs p50 on localhost
* **Aeron-based transport** — UDP, no HTTP overhead
* **Pluggable serialization** — SBE / Kryo / Protobuf / raw bytes

---

## When to use

Good fit:

* internal microservices with strict latency requirements
* trading / fintech systems
* request/response flows where simplicity matters

Not a good fit:

* large payload transfer (>16MB)
* public APIs
* service mesh replacement

---

## Installation

### Maven (GitVerse)

```xml

<repositories>
    <repository>
        <id>gitverse</id>
        <url>https://gitverse.ru/api/packages/VadimKrut/maven/</url>
    </repository>
</repositories>

<dependencies>
<dependency>
    <groupId>ru.pathcreator.pyc</groupId>
    <artifactId>aeron-rpc</artifactId>
    <version>1.0.2-SNAPSHOT</version>
</dependency>
</dependencies>
```

---

## Requirements

* Java 25+
* Maven 3.9+
* Aeron compatible environment

---

## Quick Start

### 1. Create node

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/aeron")
                .build()
);
```

### 2. Create channel

```java
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("0.0.0.0:40101")
                .remoteEndpoint("localhost:40102")
                .streamId(1001)
                .build()
);
```

### 3. Register handler

```java
channel.onRequest(
                1,
                2,
                new MyRequestCodec(),
                new MyResponseCodec(),
                req ->new MyResponse(req.id)
        );
```

### 4. Start

```java
channel.start();
```

### 5. Call

```java
MyResponse resp = channel.call(
        1, 2,
        new MyRequest(42),
        new MyRequestCodec(),
        new MyResponseCodec()
);
```

---

## Core Concepts

### RpcNode

* one Aeron MediaDriver (shared threading mode by default)
* multiple channels
* shared virtual-thread executor for server handlers

### RpcChannel

* one Publication + one Subscription
* one RX thread
* caller writes directly via `tryClaim` (no sender thread)
* concurrent calls from multiple threads / virtual threads are safe

### MessageCodec

User-defined serialization layer:

```java
interface MessageCodec<T> {
    int encode(T msg, MutableDirectBuffer buffer, int offset);

    T decode(DirectBuffer buffer, int offset, int length);
}
```

### RawRequestHandler

Zero-alloc server path — handler gets raw bytes + pre-allocated response buffer, returns bytes-written.
Use when decode-to-POJO + encode overhead matters.

---

## Choosing configuration

Two main knobs: **where the handler runs** and **how the RX thread idles**.

### Handler execution

| Setting                                           | Where handler runs     | Use when                                                 |
|---------------------------------------------------|------------------------|----------------------------------------------------------|
| default (no `offloadExecutor`)                    | virtual thread         | handler does I/O (DB/HTTP/files) — won't block RX        |
| `.offloadExecutor(myPool)`                        | your `ExecutorService` | CPU-heavy handlers — avoid virtual-thread scheduler cost |
| `.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)` | directly in RX thread  | ultra-fast handlers (<5 µs), e.g. ACK, in-memory lookup  |

`DIRECT_EXECUTOR` skips the payload copy and dispatch — lowest latency, but a slow handler stalls RX for all callers.

### RX idle strategy

| Strategy             | CPU (idle)      | Latency hit after idle | Use when                                    |
|----------------------|-----------------|------------------------|---------------------------------------------|
| `YIELDING` (default) | ~100% of 1 core | none                   | low-latency default                         |
| `BUSY_SPIN`          | ~100% of 1 core | none (fastest)         | you need every microsecond                  |
| `BACKOFF`            | ~0% when idle   | ~1 ms first message    | mostly-idle channels, CPU-constrained hosts |

### Backpressure

Applied when the publication is BACK_PRESSURED longer than `offerTimeout`.

| Policy            | Behavior                                              | Use when                                     |
|-------------------|-------------------------------------------------------|----------------------------------------------|
| `BLOCK` (default) | caller waits until accepted or `offerTimeout` expires | normal RPC — "request waits, like HTTP"      |
| `FAIL_FAST`       | throws `BackpressureException` immediately            | low-latency, caller has retry/fallback logic |

---

## Recipes

### Max-throughput / min-latency (ACK-style handler)

```java
ChannelConfig
        .builder()
        .localEndpoint("...")
        .remoteEndpoint("...")
        .streamId(1001)
        .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
        .rxIdleStrategy(IdleStrategyKind.BUSY_SPIN)
        .build();
```

### Typical I/O handler (DB, HTTP calls)

```java
ChannelConfig
        .builder()
        .localEndpoint("...")
        .remoteEndpoint("...")
        .streamId(1002)
        .rxIdleStrategy(IdleStrategyKind.YIELDING)
        .build();
```

### Mostly-idle channel, CPU-constrained host

```java
ChannelConfig
        .builder()
        .localEndpoint("...")
        .remoteEndpoint("...")
        .streamId(1003)
        .rxIdleStrategy(IdleStrategyKind.BACKOFF)
        .build();
```

---

## Performance design

* no sender thread — caller invokes `tryClaim` directly on a `ConcurrentPublication`
* `tryClaim` fast-path for all messages ≤ MTU; `offer` fallback for larger
* thread-local direct staging buffers — no per-call allocations in the hot path
* pooled `PendingCall`, `OffloadTask`, copy buffers (Agrona lock-free queues)
* primitive maps (`Long2ObjectHashMap`, `Int2ObjectHashMap`) — no boxing
* 3-phase sync wait: spin → yield → park (avoids Windows timer penalty)
* MediaDriver in `SHARED` threading mode — 1 driver thread, not 3

---

## Limitations

* no auto-reconnect (failfast via heartbeat instead)
* max payload: 16MB (`LargePayloadRpcChannel` placeholder for future)
* `DIRECT_EXECUTOR` handlers block RX — use only for truly fast handlers

---

## License

Apache License 2.0