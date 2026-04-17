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

### Maven (GitHub Packages)

Then use the GitHub Packages repository:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vadimkrut/aeron-rpc-pyc</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
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

## Tests and benchmarks

### Unit tests

Run regular unit tests:

```bash
mvn test
```

Run the full verification lifecycle, including compilation, unit tests, source jar and Javadoc jar:

```bash
mvn clean verify
```

On Java 25 the tests need module access for Agrona internals. The required JVM option is already configured
for Maven Surefire in `pom.xml`:

```text
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

### Javadoc

Generate API documentation:

```bash
mvn javadoc:javadoc
```

The generated documentation is written to:

```text
target/reports/apidocs
```

### JMH benchmarks

Make sure Maven runs on JDK 25:

```bash
mvn -version
```

If Maven prints an older Java version on Windows PowerShell, set `JAVA_HOME` for the current terminal:

```powershell
$env:JAVA_HOME = "...\.jdks\openjdk-ea-25+36-3489"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

Build the benchmark jar:

```bash
mvn -Pbenchmarks -DskipTests clean package
```

Run all RPC channel benchmarks:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark
```

Run one focused scenario:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark.oneChannelEightThreads
```

Limit benchmark parameters for a shorter run:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark.oneChannelEightThreads -p handlerMode=DIRECT -p payloadSize=256 -p idleStrategy=YIELDING
```

Compare RX idle strategies:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark.oneChannelEightThreads -p idleStrategy=YIELDING,BUSY_SPIN,BACKOFF
```

Save benchmark results to CSV:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark -rf csv -rff target/jmh-rpc.csv
```

In WSL/Linux use `/` in paths, for example `target/aeron-rpc-benchmarks.jar`.
The Windows-style path `target\aeron-rpc-benchmarks.jar` is not valid there.

The RPC benchmark parameters are:

| Parameter      | Values                         | Meaning                         |
|----------------|--------------------------------|---------------------------------|
| `handlerMode`  | `DIRECT`, `OFFLOAD`            | where server handlers run       |
| `payloadSize`  | `16`, `256`, `1024`            | request and response size       |
| `idleStrategy` | `YIELDING`, `BUSY_SPIN`, `BACKOFF` | RX thread idle strategy      |

### Reference benchmark results

These are example local results from a WSL run on Java 25. Treat them as a reference point, not as a portable
guarantee: Aeron performance depends heavily on CPU, OS scheduler, power mode, MediaDriver placement and whether
the benchmark runs on a native Linux filesystem or under `/mnt/c`.

JMH reports `Score` as throughput in operations per second. The `Error` column is not a test failure; it is the
statistical confidence interval for the measured score. A large `Error` means the result was noisy and should be
rerun with more iterations or a cleaner environment before drawing strong conclusions.

| Benchmark | Handler | RX idle | Payload | Score, ops/s | Error, ops/s |
|-----------|---------|---------|---------|--------------|--------------|
| `fourChannelsEightThreadsEach` | DIRECT | YIELDING | 16 B | 61,407.683 | 9,502.823 |
| `fourChannelsEightThreadsEach` | DIRECT | YIELDING | 256 B | 49,200.071 | 56,955.723 |
| `fourChannelsEightThreadsEach` | DIRECT | YIELDING | 1024 B | 64,005.432 | 36,969.464 |
| `fourChannelsEightThreadsEach` | DIRECT | BUSY_SPIN | 16 B | 77,975.351 | 10,021.085 |
| `fourChannelsEightThreadsEach` | DIRECT | BUSY_SPIN | 256 B | 88,761.459 | 107,865.061 |
| `fourChannelsEightThreadsEach` | DIRECT | BUSY_SPIN | 1024 B | 85,725.096 | 38,059.795 |
| `fourChannelsEightThreadsEach` | DIRECT | BACKOFF | 16 B | 54,572.523 | 9,958.987 |
| `fourChannelsEightThreadsEach` | DIRECT | BACKOFF | 256 B | 53,701.850 | 32,721.614 |
| `fourChannelsEightThreadsEach` | DIRECT | BACKOFF | 1024 B | 64,984.427 | 22,871.002 |
| `fourChannelsEightThreadsEach` | OFFLOAD | YIELDING | 16 B | 47,850.989 | 24,732.797 |
| `fourChannelsEightThreadsEach` | OFFLOAD | YIELDING | 256 B | 38,428.156 | 8,847.332 |
| `fourChannelsEightThreadsEach` | OFFLOAD | YIELDING | 1024 B | 49,100.813 | 53,337.230 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BUSY_SPIN | 16 B | 62,579.518 | 15,203.081 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BUSY_SPIN | 256 B | 44,528.867 | 37,385.968 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BUSY_SPIN | 1024 B | 61,105.251 | 76,631.469 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BACKOFF | 16 B | 43,451.862 | 9,035.131 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BACKOFF | 256 B | 38,047.466 | 6,368.076 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BACKOFF | 1024 B | 51,211.319 | 47,061.843 |
| `oneChannelEightThreads` | DIRECT | YIELDING | 16 B | 454,729.955 | 244,878.842 |
| `oneChannelEightThreads` | DIRECT | YIELDING | 256 B | 230,722.594 | 12,815.495 |
| `oneChannelEightThreads` | DIRECT | YIELDING | 1024 B | 236,397.916 | 43,727.005 |
| `oneChannelEightThreads` | DIRECT | BUSY_SPIN | 16 B | 282,136.430 | 39,527.653 |
| `oneChannelEightThreads` | DIRECT | BUSY_SPIN | 256 B | 298,127.380 | 64,247.462 |
| `oneChannelEightThreads` | DIRECT | BUSY_SPIN | 1024 B | 254,938.200 | 13,770.398 |
| `oneChannelEightThreads` | DIRECT | BACKOFF | 16 B | 83,531.929 | 21,939.100 |
| `oneChannelEightThreads` | DIRECT | BACKOFF | 256 B | 97,467.152 | 39,229.694 |
| `oneChannelEightThreads` | DIRECT | BACKOFF | 1024 B | 145,545.016 | 35,216.105 |
| `oneChannelEightThreads` | OFFLOAD | YIELDING | 16 B | 145,612.182 | 27,664.428 |
| `oneChannelEightThreads` | OFFLOAD | YIELDING | 256 B | 140,335.127 | 21,444.443 |
| `oneChannelEightThreads` | OFFLOAD | YIELDING | 1024 B | 127,947.144 | 22,790.599 |
| `oneChannelEightThreads` | OFFLOAD | BUSY_SPIN | 16 B | 137,504.890 | 41,220.196 |
| `oneChannelEightThreads` | OFFLOAD | BUSY_SPIN | 256 B | 133,542.345 | 32,919.160 |
| `oneChannelEightThreads` | OFFLOAD | BUSY_SPIN | 1024 B | 135,113.223 | 28,632.751 |
| `oneChannelEightThreads` | OFFLOAD | BACKOFF | 16 B | 49,856.387 | 4,628.959 |
| `oneChannelEightThreads` | OFFLOAD | BACKOFF | 256 B | 50,740.938 | 2,239.948 |
| `oneChannelEightThreads` | OFFLOAD | BACKOFF | 1024 B | 49,546.680 | 879.996 |
| `oneChannelOneThread` | DIRECT | YIELDING | 16 B | 186,904.075 | 6,926.592 |
| `oneChannelOneThread` | DIRECT | YIELDING | 256 B | 182,500.107 | 6,742.407 |
| `oneChannelOneThread` | DIRECT | YIELDING | 1024 B | 168,516.496 | 12,630.781 |
| `oneChannelOneThread` | DIRECT | BUSY_SPIN | 16 B | 189,722.932 | 2,501.386 |
| `oneChannelOneThread` | DIRECT | BUSY_SPIN | 256 B | 184,005.769 | 3,727.608 |
| `oneChannelOneThread` | DIRECT | BUSY_SPIN | 1024 B | 167,798.554 | 2,143.439 |
| `oneChannelOneThread` | DIRECT | BACKOFF | 16 B | 19,800.224 | 2,735.737 |
| `oneChannelOneThread` | DIRECT | BACKOFF | 256 B | 20,936.170 | 20,567.851 |
| `oneChannelOneThread` | DIRECT | BACKOFF | 1024 B | 16,980.122 | 4,493.010 |
| `oneChannelOneThread` | OFFLOAD | YIELDING | 16 B | 139,926.312 | 19,629.895 |
| `oneChannelOneThread` | OFFLOAD | YIELDING | 256 B | 141,598.614 | 16,001.812 |
| `oneChannelOneThread` | OFFLOAD | YIELDING | 1024 B | 125,358.303 | 13,997.795 |
| `oneChannelOneThread` | OFFLOAD | BUSY_SPIN | 16 B | 143,182.508 | 23,719.307 |
| `oneChannelOneThread` | OFFLOAD | BUSY_SPIN | 256 B | 139,550.293 | 8,069.328 |
| `oneChannelOneThread` | OFFLOAD | BUSY_SPIN | 1024 B | 124,440.943 | 20,101.422 |
| `oneChannelOneThread` | OFFLOAD | BACKOFF | 16 B | 10,474.733 | 738.089 |
| `oneChannelOneThread` | OFFLOAD | BACKOFF | 256 B | 10,522.227 | 738.932 |
| `oneChannelOneThread` | OFFLOAD | BACKOFF | 1024 B | 10,484.348 | 586.815 |

Practical reading of these numbers:

* `DIRECT` is best for very fast handlers because it avoids offload queueing and payload copying.
* `OFFLOAD` is safer for blocking or slow handlers because it protects the RX thread from user code.
* `YIELDING` is the default low-latency choice and performed well in the one-channel tests.
* `BUSY_SPIN` can be slightly faster or more stable in some hot-loop cases, but it burns CPU constantly.
* `BACKOFF` saves CPU while idle, but it can reduce throughput and add latency after idle periods.
* More channels do not automatically mean more throughput in one process: extra RX threads, shared embedded
  MediaDriver work and scheduler contention can dominate the measurement.

---

### I/O handler recommendation

For I/O-bound handlers, use `OFFLOAD + YIELDING`.

`OFFLOAD` keeps blocking user code away from the RX thread, while `YIELDING` remains a good low-latency default for the receive loop.

Based on the 1024-byte benchmark results, the expected transport overhead is approximately:

* `~8 us` per round-trip with one caller thread;
* `~60-65 us` average round-trip latency with eight concurrent caller threads.

Real database, HTTP, file-system or network work should be added on top of this transport overhead.


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
* responses are matched to requests by transport correlation id, so concurrent callers do not receive each other's replies

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
