# rpc-core

Synchronous request/response RPC over Aeron UDP for low-latency Java systems.

`rpc-core` keeps a blocking `call()` API while the transport stays focused on:

- low transport overhead
- minimal hot-path allocations
- predictable request/response latency
- safe concurrent callers
- many-channel layouts on one driver

## Why

Many low-latency transports push users toward async or reactive code even when
the business flow is naturally request/response.

`rpc-core` is for the opposite case:

- you want Aeron UDP
- you want a normal blocking API
- you still care about microseconds

## Good Fit

- internal low-latency services
- trading, fintech, and market-data style systems
- request/response flows on controlled networks
- services where synchronous business code is simpler and safer

## Not A Good Fit

- very large file transfer as the main workload
- internet-facing RPC without an external security layer
- systems that need built-in streaming, auth, or schema negotiation by default

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
    <artifactId>rpc-core</artifactId>
    <version>0.1.3</version>
</dependency>
</dependencies>
```

### Maven via GitHub Packages

GitHub Packages requires authentication even for reads. Add a token with
`read:packages` to Maven `settings.xml`, then configure:

```xml

<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vadimkrut/rpc-core</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>

<dependencies>
<dependency>
    <groupId>ru.pathcreator.pyc</groupId>
    <artifactId>rpc-core</artifactId>
    <version>0.1.3</version>
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
                .aeronDir("/tmp/rpc-core")
                .build()
);
```

### Create a channel

```java
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("127.0.0.1:40101")
                .remoteEndpoint("127.0.0.1:40102")
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
                request -> new MyResponse(request.id())
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

## Core Concepts

### `RpcNode`

`RpcNode` owns:

- the Aeron client
- the optional embedded `MediaDriver`
- the shared offload executor
- the shared receive poller
- all channels created through that node

### `RpcChannel`

`RpcChannel` is one bidirectional RPC transport pair:

- one `Publication`
- one `Subscription`
- one pending-call registry
- one correlation flow
- one handler registry

Multiple caller threads are safe. Responses are matched by correlation id, so
one caller cannot receive another caller's response.

### `MessageCodec`

`MessageCodec<T>` is the user-controlled serialization layer.

```java
interface MessageCodec<T> {
    int encode(T message, MutableDirectBuffer buffer, int offset);

    T decode(DirectBuffer buffer, int offset, int length);
}
```

### `RawRequestHandler`

`RawRequestHandler` is the low-level server path: raw request bytes in, raw
response bytes out.

Use it when you want tight control over allocations and serialization.

## Remote Errors

Unhandled server-side failures are returned as structured remote errors instead
of silently turning into client-side timeouts.

Built-in transport and shared validation failures use HTTP-like status codes.
Application code can return business-level errors by throwing
`RpcApplicationException`, preferably with custom codes `>= 1000`.

## Performance Model

The core transport path is designed to stay ultra-fast by default. Service-level
features such as listeners, protocol handshake, reconnect recreation, schema
reporting, and metrics export are optional and should only be enabled when you
need them.

For the latest measured numbers and commands, see
[`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

## Current Transport Architecture

`rpc-core` uses a shared receive-poller design.

Instead of forcing one hot RX thread per channel, `RpcNode` can host a shared
receive poller with multiple lanes. Each `RpcChannel` stays logically isolated,
but receive polling can be shared across many channels on the same driver.

Important properties:

- channels keep isolated pending state
- correlation safety is unchanged
- one `Subscription` is never polled concurrently by multiple threads
- empty RX lanes park instead of burning CPU
- `OFFLOAD` reuses pooled per-channel execution state for staging, claim, and
  idle helpers instead of repeatedly rebuilding that state on virtual-thread
  tasks

## Recommended Starting Point

For practical low-latency services with real handler work:

- `OFFLOAD` handlers
- virtual-thread offload is the default safe and general-purpose profile
- `YIELDING` receive idle strategy
- shared receive poller enabled
- `sharedReceivePollerThreads(2)` as the first measurement point
- `ReconnectStrategy.WAIT_FOR_CONNECTION` when brief disconnects should be
  tolerated

For deeper tuning guidance, see
[`docs/CHANNEL_TUNING.md`](docs/CHANNEL_TUNING.md).

## More Documentation

- [`docs/JAVA_EXAMPLES.md`](docs/JAVA_EXAMPLES.md) - copy-paste Java setup and
  integration examples
- [`docs/SERVICE_REGISTRY.md`](docs/SERVICE_REGISTRY.md) - optional startup-time
  method registry, validation, warnings, and schema export layer
- [`docs/METRICS_MODULE.md`](docs/METRICS_MODULE.md) - optional listener-based
  metrics layer that stays outside `rpc.core`
- [`docs/CHANNEL_TUNING.md`](docs/CHANNEL_TUNING.md) - what the important node
  and channel settings do, when to use them, and what they can cost
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) - benchmark commands, methodology,
  and current benchmark notes
- [`docs/PRODUCTION_GUIDE.md`](docs/PRODUCTION_GUIDE.md) - operational guidance,
  deployment advice, and production rollout notes
- [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) - lightweight release
  checklist for packaging, tagging, and publishing
- [`docs/PERFORMANCE_DISCIPLINE.md`](docs/PERFORMANCE_DISCIPLINE.md) - how to
  compare before/after changes without fooling yourself with noisy runs

## Optional Layers Outside Core

The project keeps additional service-level layers in clearly separate places:

- `ru.pathcreator.pyc.rpc.schema` - startup-time method registry, warnings, and
  schema reporting
- `ru.pathcreator.pyc.rpc.metrics` - listener-based metrics collector and
  snapshot reporting

That separation is intentional. The transport core stays small, while optional
integration layers can evolve around it.

## Testing

Run unit and integration-style correctness tests:

```bash
mvn test
```

Run benchmark packaging:

```bash
mvn -Pbenchmarks -DskipTests package
```

## Limitations

- no full automatic channel recreation by default
- regular `RpcChannel` supports a single message up to 16 MiB total size
- the usable payload is slightly smaller than 16 MiB because protocol envelope
  bytes are included in that limit
- payloads or files larger than a single 16 MiB message should use
  application-level chunking or a separate transport path
- `DIRECT_EXECUTOR` can block receive progress
- auth, encryption, and authorization belong to the application or
  infrastructure layer

## License

Apache License 2.0
