# aeron-rpc

Synchronous request/response RPC over Aeron UDP, designed for **low-latency Java systems**.

Provides a simple, blocking API (like HTTP), while keeping the internals optimized for throughput, minimal allocations,
and predictable latency.

---

## Why this exists

Most high-performance transports force you into async/reactive complexity.

This project aims to provide:

* **Synchronous API** — simple `call()` model
* **Low latency** — optimized hot path, minimal allocations
* **Aeron-based transport** — high throughput, UDP, no HTTP overhead
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
    <version>1.0.0-SNAPSHOT</version>
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

---

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

---

### 3. Register handler

```java
channel.onRequest(
                1,
                2,
                new MyRequestCodec(), 
                new MyResponseCodec(),
                HandlerMode.OFFLOAD,
                req -> new MyResponse(req.id)
);
```

---

### 4. Start

```java
channel.start();
```

---

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

* one Aeron MediaDriver
* multiple channels

### RpcChannel

* dedicated sender thread
* dedicated receiver thread
* MPSC queue between callers and sender

### MessageCodec

User-defined serialization layer:

```java
interface MessageCodec<T> {
    int encode(T msg, MutableDirectBuffer buffer, int offset);

    T decode(DirectBuffer buffer, int offset, int length);
}
```

---

## Handler Modes

* `INLINE` — zero-copy, runs in RX thread (fast, but blocking)
* `OFFLOAD` — executed in executor (safe for IO)

---

## Backpressure

* `BLOCK` (default)
* `FAIL_FAST`
* `DROP_NEW`
* `DROP_OLDEST`

For RPC, use:

* `BLOCK` or `FAIL_FAST`

---

## Performance Design

* single sender thread (no contention)
* lock-free MPSC queue
* pooled buffers
* no per-request allocations in hot path
* `tryClaim` fast-path for Aeron
* opportunistic batching

---

## Limitations

* no auto-reconnect
* max payload: 16MB
* heavy INLINE handlers block RX thread
* logging is minimal (System.err)

---

## Roadmap

* reconnect / channel supervision
* metrics
* large payload support
* zero-copy API extensions
* better logging integration

---

## Status

Early public release.

API may change before stable version.

---

## License

Apache License 2.0