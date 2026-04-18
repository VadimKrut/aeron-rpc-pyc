# aeron-rpc

Synchronous request/response RPC over Aeron UDP for low-latency Java systems.

The library keeps a blocking `call()` API while the transport path is tuned for:

- low transport overhead
- minimal allocations on the hot path
- predictable latency for request/response workloads
- safe concurrent callers

## Why

Many low-latency transports push users into async/reactive code even when the business flow is naturally request/response.

`aeron-rpc` is for the opposite case:

- you want Aeron UDP
- you want a normal blocking API
- you still care about microseconds

## Good Fit

- internal low-latency services
- trading / fintech / market-data style systems
- request/response flows on controlled networks
- systems where user code is easier to reason about synchronously

## Not a Good Fit

- large payload transfer as the main workload

## Installation

### Maven via GitVerse

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
        <version>0.0.6</version>
    </dependency>
</dependencies>
```

### Maven via GitHub Packages

GitHub Packages requires authentication even for reads. Add a token with `read:packages` to Maven `settings.xml`, then configure:

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
        <version>0.0.6</version>
    </dependency>
</dependencies>
```

## Requirements

- Java 25+
- Maven 3.9+

## Quick Start

### Start a node

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/aeron")
                .build()
);
```

### Create a channel

```java
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("0.0.0.0:40101")
                .remoteEndpoint("localhost:40102")
                .streamId(1001)
                .build()
);
```

### Register a handler

```java
channel.onRequest(
        1,
        2,
        new MyRequestCodec(),
        new MyResponseCodec(),
        req -> new MyResponse(req.id())
);
```

### Start and call

```java
channel.start();

MyResponse response = channel.call(
        1,
        2,
        new MyRequest(42),
        new MyRequestCodec(),
        new MyResponseCodec()
);
```

For full Java integration examples, including production-style configuration,
reconnect handling, many-channel layouts, and a complete settings reference, see
[`docs/JAVA_EXAMPLES.md`](docs/JAVA_EXAMPLES.md).

## Core Concepts

### `RpcNode`

`RpcNode` owns:

- the Aeron client
- optional embedded `MediaDriver`
- the shared offload executor
- the shared receive poller
- all channels created through that node

### `RpcChannel`

`RpcChannel` is one bidirectional RPC transport pair:

- one `Publication`
- one `Subscription`
- its own pending-call registry
- its own correlation flow
- its own handler registry

Multiple caller threads are safe. Responses are matched by correlation id, so one caller cannot receive another caller's response.

### `MessageCodec`

`MessageCodec<T>` is the user serialization layer.

```java
interface MessageCodec<T> {
    int encode(T message, MutableDirectBuffer buffer, int offset);
    T decode(DirectBuffer buffer, int offset, int length);
}
```

### `RawRequestHandler`

`RawRequestHandler` is the low-level server path: raw request bytes in, raw response bytes out.

Use it when you want tight control over allocations and serialization.

## Current Transport Architecture

Recent changes introduced a shared receive-poller design.

Instead of forcing one hot RX thread per channel, `RpcNode` can host a shared receive poller with multiple lanes. Each `RpcChannel` stays logically isolated, but receive polling can be shared across many channels on the same driver.

Important properties:

- channels still keep isolated pending state
- correlation safety is unchanged
- one `Subscription` is never polled concurrently by multiple threads
- empty RX lanes now park instead of burning CPU

This matters especially for workloads with many channels on one `MediaDriver`.

## Main Tuning Knobs

### Handler execution

| Setting | Runs where | Use when |
|---|---|---|
| default | node default executor | normal blocking or I/O-heavy handlers |
| `.offloadExecutor(myPool)` | your executor | custom scheduling / CPU-heavy work |
| `.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)` | directly in receive path | only for extremely fast handlers |

`DIRECT_EXECUTOR` removes offload scheduling and payload copy, but a slow handler blocks receive progress.

### RX idle strategy

| Strategy | Use when |
|---|---|
| `YIELDING` | low-latency default |
| `BUSY_SPIN` | you are willing to burn a core for the hot path |
| `BACKOFF` | mostly idle channels and CPU-sensitive hosts |

### Reconnect strategy

`ChannelConfig` now supports:

- `ReconnectStrategy.FAIL_FAST` - old behavior, fail immediately if disconnected
- `ReconnectStrategy.WAIT_FOR_CONNECTION` - wait until the existing publication/heartbeat becomes connected again

This does not recreate channels automatically. It only waits for the current Aeron path to come back before sending the request.

Example:

```java
ChannelConfig.builder()
        .localEndpoint("localhost:40101")
        .remoteEndpoint("localhost:40102")
        .streamId(1001)
        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
        .build();
```

### Shared receive poller

`NodeConfig` now supports:

- `sharedReceivePoller(boolean)`
- `sharedReceivePollerThreads(int)`
- `sharedReceivePollerFragmentLimit(int)`

Example:

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/aeron")
                .sharedReceivePoller(true)
                .sharedReceivePollerThreads(2)
                .sharedReceivePollerFragmentLimit(16)
                .build()
);
```

For many channels on one host, this is often better than spinning one dedicated RX thread per channel.

## Testing

Run unit tests:

```bash
mvn test
```

Notable coverage now includes:

- concurrent correlation correctness
- IO-like offloaded handlers across multiple channels and threads
- reconnect wait strategy

## Benchmarks

The old benchmark section in this README was replaced with the current standalone latency workflow.

See:

- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md)
- [`docs/JAVA_EXAMPLES.md`](docs/JAVA_EXAMPLES.md)

That document contains:

- current benchmark commands
- parameter explanations
- `OFFLOAD + YIELDING` examples
- multi-channel and multi-thread scenarios
- IO-like handler scenarios
- Java integration examples for application code
- notes about comparison with raw Aeron

## Limitations

- no automatic channel recreation
- default max payload is 16 MiB
- `DIRECT_EXECUTOR` can block receive progress
- auth, encryption, and authorization belong to the application or infrastructure layer

## License

Apache License 2.0
