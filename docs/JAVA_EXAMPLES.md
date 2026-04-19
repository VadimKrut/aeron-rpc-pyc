# Java Integration Examples

This document shows how to use `rpc-core` from another Java project after you
add the dependency.

The goal is to make integration copy-paste friendly:

- start a node
- create one or more channels
- register handlers
- choose `OFFLOAD` or `DIRECT`
- tune reconnect and RX polling
- understand what each setting changes

## 1. Smallest Working Example

```java
import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.NodeConfig;
import ru.pathcreator.pyc.rpc.core.RpcChannel;
import ru.pathcreator.pyc.rpc.core.RpcNode;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.nio.charset.StandardCharsets;

public final class MinimalExample {

    public static void main(String[] args) {
        RpcNode serverNode = RpcNode.start(
                NodeConfig.builder()
                        .aeronDir("/tmp/rpc-core-demo")
                        .build()
        );

        RpcNode clientNode = RpcNode.start(
                NodeConfig.builder()
                        .aeronDir("/tmp/rpc-core-demo")
                        .build()
        );

        RpcChannel server = serverNode.channel(
                ChannelConfig.builder()
                        .localEndpoint("127.0.0.1:40102")
                        .remoteEndpoint("127.0.0.1:40101")
                        .streamId(1001)
                        .build()
        );

        RpcChannel client = clientNode.channel(
                ChannelConfig.builder()
                        .localEndpoint("127.0.0.1:40101")
                        .remoteEndpoint("127.0.0.1:40102")
                        .streamId(1001)
                        .build()
        );

        MessageCodec<String> codec = new StringCodec();

        server.onRequest(
                1,
                2,
                codec,
                codec,
                request -> "echo:" + request
        );

        server.start();
        client.start();

        String response = client.call(
                1,
                2,
                "hello",
                codec,
                codec
        );

        System.out.println(response);

        client.close();
        server.close();
        clientNode.close();
        serverNode.close();
    }

    static final class StringCodec implements MessageCodec<String> {
        @Override
        public int encode(final String message, final MutableDirectBuffer buffer, final int offset) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            buffer.putBytes(offset, bytes);
            return bytes.length;
        }

        @Override
        public String decode(final DirectBuffer buffer, final int offset, final int length) {
            byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
```

## 2. Recommended Starting Point For Real Services

This is the profile we currently target for practical synchronous RPC:

- `OFFLOAD` handlers
- `YIELDING` receive idle strategy
- shared receive poller enabled
- reconnect waits instead of failing immediately

```java
import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.IdleStrategyKind;
import ru.pathcreator.pyc.rpc.core.NodeConfig;
import ru.pathcreator.pyc.rpc.core.ReconnectStrategy;
import ru.pathcreator.pyc.rpc.core.RpcChannel;
import ru.pathcreator.pyc.rpc.core.RpcNode;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RecommendedProfileExample {

    private static final ExecutorService OFFLOAD_POOL =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public static void main(String[] args) {
        RpcNode node = RpcNode.start(
                NodeConfig.builder()
                        .aeronDir("/var/run/rpc-core")
                        .sharedReceivePoller(true)
                        .sharedReceivePollerThreads(2)
                        .sharedReceivePollerFragmentLimit(16)
                        .build()
        );

        RpcChannel channel = node.channel(
                ChannelConfig.builder()
                        .localEndpoint("10.10.0.11:40101")
                        .remoteEndpoint("10.10.0.12:40101")
                        .streamId(2001)
                        .defaultTimeout(Duration.ofMillis(5))
                        .offerTimeout(Duration.ofMillis(2))
                        .heartbeatInterval(Duration.ofMillis(250))
                        .heartbeatMissedLimit(3)
                        .backpressurePolicy(ru.pathcreator.pyc.rpc.core.BackpressurePolicy.BLOCK)
                        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
                        .rxIdleStrategy(IdleStrategyKind.YIELDING)
                        .offloadExecutor(OFFLOAD_POOL)
                        .pendingPoolCapacity(8192)
                        .registryInitialCapacity(8192)
                        .build()
        );

        channel.start();

        // register handlers and use channel.call(...)
    }
}
```

Use this when handlers may do:

- database calls
- filesystem work
- HTTP/gRPC clients
- blocking caches
- any non-trivial business logic

## 3. Server Handler Modes

### OFFLOAD handler

Best default for real services.

```java
channel.onRequest(
        10,
                11,
        requestCodec,
        responseCodec,
        request ->service.

handle(request)
);
```

This uses the configured offload executor.

### DIRECT handler

Use only when the handler is extremely small and predictable.

```java
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("127.0.0.1:40101")
                .remoteEndpoint("127.0.0.1:40102")
                .streamId(3001)
                .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
                .build()
);
```

Good fit:

- tiny in-memory transforms
- prevalidated request/response mapping
- no blocking I/O

Risk:

- slow handler logic blocks receive progress for that channel

## 4. Reconnect Behavior

### Fail fast

Best when the caller should immediately trigger fallback logic.

```java
ChannelConfig config = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .reconnectStrategy(ReconnectStrategy.FAIL_FAST)
        .build();
```

### Wait for connection

Best when brief disconnects are acceptable and you want the request to wait for
the Aeron path to recover.

```java
ChannelConfig config = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .defaultTimeout(Duration.ofSeconds(2))
        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
        .build();
```

Important:

- this does not recreate the channel automatically
- it waits for the current publication/heartbeat path to become connected again

## 5. Many Channels On One Driver

This is the recommended layout when your application has many independent
logical connections.

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/var/run/rpc-core")
                .sharedReceivePoller(true)
                .sharedReceivePollerThreads(2)
                .sharedReceivePollerFragmentLimit(16)
                .build()
);

RpcChannel marketData = node.channel(
        ChannelConfig.builder()
                .localEndpoint("10.10.0.11:41001")
                .remoteEndpoint("10.10.0.12:41001")
                .streamId(41001)
                .rxIdleStrategy(IdleStrategyKind.YIELDING)
                .build()
);

RpcChannel orders = node.channel(
        ChannelConfig.builder()
                .localEndpoint("10.10.0.11:41002")
                .remoteEndpoint("10.10.0.12:41002")
                .streamId(41002)
                .rxIdleStrategy(IdleStrategyKind.YIELDING)
                .build()
);

RpcChannel referenceData = node.channel(
        ChannelConfig.builder()
                .localEndpoint("10.10.0.11:41003")
                .remoteEndpoint("10.10.0.12:41003")
                .streamId(41003)
                .rxIdleStrategy(IdleStrategyKind.YIELDING)
                .build()
);

marketData.

start();
orders.

start();
referenceData.

start();
```

In general, many channels are a better scaling direction than forcing many
caller threads through one channel.

## 6. Multiple Caller Threads On One Channel

This is supported and safe, but usually scales worse than spreading the work
across multiple channels.

```java
RpcChannel sharedChannel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("127.0.0.1:40101")
                .remoteEndpoint("127.0.0.1:40102")
                .streamId(5001)
                .pendingPoolCapacity(16384)
                .registryInitialCapacity(16384)
                .build()
);

sharedChannel.

start();

// Many application threads can call sharedChannel.call(...)
```

Practical guidance:

- safe: yes
- fastest option under high contention: usually no

## 7. Embedded Driver vs External Driver

### Embedded driver

Simple for local apps, tests, and standalone services.

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/rpc-core-demo")
                .embeddedDriver(true)
                .build()
);
```

### External driver

Better when you already run a dedicated MediaDriver and want multiple processes
to share it.

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/dev/shm/aeron-your-driver")
                .embeddedDriver(false)
                .build()
);
```

## 8. Backpressure Policy

### BLOCK

Default and usually the right choice for synchronous RPC.

```java
channel.call(
        1,
                2,
        request,
        requestCodec,
        responseCodec,
        Duration.ofMillis(10),

ru.pathcreator.pyc.rpc.core.BackpressurePolicy.BLOCK
);
```

Meaning:

- keep trying to publish until `offerTimeout`
- if still back-pressured, throw an exception

### FAIL_FAST

Better when the caller should fail immediately and choose another path.

```java
channel.call(
        1,
                2,
        request,
        requestCodec,
        responseCodec,
        Duration.ofMillis(10),

ru.pathcreator.pyc.rpc.core.BackpressurePolicy.FAIL_FAST
);
```

## 9. Idle Strategy Choice

### YIELDING

Recommended default for low-latency RPC.

```java
.rxIdleStrategy(IdleStrategyKind.YIELDING)
```

### BUSY_SPIN

Best raw latency, highest CPU usage.

```java
.rxIdleStrategy(IdleStrategyKind.BUSY_SPIN)
```

### BACKOFF

Useful when CPU matters more than absolute latency.

```java
.rxIdleStrategy(IdleStrategyKind.BACKOFF)
```

Practical note:

- for low-latency targets, `YIELDING` is the most balanced starting point
- `BUSY_SPIN` is for hot paths where dedicating cores is acceptable
- `BACKOFF` is more conservative on CPU, but should be measured carefully on the target OS

## 10. Full `ChannelConfig` Settings Reference

```java
ChannelConfig config = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .sessionId(42)
        .mtuLength(1408)
        .termLength(16 * 1024 * 1024)
        .socketSndBuf(4 * 1024 * 1024)
        .socketRcvBuf(4 * 1024 * 1024)
        .defaultTimeout(Duration.ofMillis(5))
        .offerTimeout(Duration.ofMillis(2))
        .heartbeatInterval(Duration.ofMillis(250))
        .heartbeatMissedLimit(3)
        .maxMessageSize(1024 * 1024)
        .backpressurePolicy(ru.pathcreator.pyc.rpc.core.BackpressurePolicy.BLOCK)
        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
        .rxIdleStrategy(IdleStrategyKind.YIELDING)
        .pendingPoolCapacity(8192)
        .registryInitialCapacity(8192)
        .offloadExecutor(customExecutor)
        .offloadTaskPoolSize(2048)
        .offloadCopyPoolSize(2048)
        .offloadCopyBufferSize(64 * 1024)
        .build();
```

What each setting is for:

| Setting                   | Meaning                                           |
|---------------------------|---------------------------------------------------|
| `localEndpoint`           | local UDP endpoint for this channel               |
| `remoteEndpoint`          | peer UDP endpoint                                 |
| `streamId`                | Aeron stream id                                   |
| `sessionId`               | explicit Aeron session id when you need one       |
| `mtuLength`               | Aeron MTU                                         |
| `termLength`              | Aeron term buffer length                          |
| `socketSndBuf`            | OS send socket buffer                             |
| `socketRcvBuf`            | OS receive socket buffer                          |
| `defaultTimeout`          | default call timeout                              |
| `offerTimeout`            | how long publish retries are allowed              |
| `heartbeatInterval`       | heartbeat send cadence                            |
| `heartbeatMissedLimit`    | how many missed heartbeats mark the channel down  |
| `maxMessageSize`          | max request or response payload for this channel  |
| `backpressurePolicy`      | whether publish backpressure blocks or fails fast |
| `reconnectStrategy`       | fail immediately or wait for reconnection         |
| `rxIdleStrategy`          | receive-path idle behavior                        |
| `pendingPoolCapacity`     | pooled pending-call objects                       |
| `registryInitialCapacity` | initial correlation registry capacity             |
| `offloadExecutor`         | executor for handlers                             |
| `offloadTaskPoolSize`     | pooled offload task objects                       |
| `offloadCopyPoolSize`     | pooled buffers for offloaded request copies       |
| `offloadCopyBufferSize`   | size of each offload copy buffer                  |

## 11. Full `NodeConfig` Settings Reference

```java
NodeConfig config = NodeConfig.builder()
        .aeronDir("/var/run/rpc-core")
        .embeddedDriver(false)
        .sharedReceivePoller(true)
        .sharedReceivePollerThreads(2)
        .sharedReceivePollerFragmentLimit(16)
        .build();
```

| Setting                            | Meaning                                          |
|------------------------------------|--------------------------------------------------|
| `aeronDir`                         | Aeron directory shared with the media driver     |
| `embeddedDriver`                   | start media driver inside this process           |
| `sharedReceivePoller`              | share receive polling across channels            |
| `sharedReceivePollerThreads`       | number of shared RX lanes per idle-strategy kind |
| `sharedReceivePollerFragmentLimit` | fragment limit used in each poll cycle           |

## 12. Large Payloads

Regular `RpcChannel` supports a single message up to `16 MiB` total size.

That means:

- the supported max-size path today is the normal `RpcChannel`
- the usable payload is slightly smaller than `16 MiB` because the protocol envelope is included in that limit
- payloads or files larger than a single `16 MiB` message should use application-level chunking or a separate transfer path

## 13. Remote Errors

If a server-side handler fails, the caller receives a structured
`RemoteRpcException` instead of waiting until timeout.

Built-in transport statuses use HTTP-like numeric codes:

- `400` bad request
- `413` payload too large
- `500` internal server error
- `501` not implemented
- `503` service unavailable

Application handlers can return their own business errors:

```java
import ru.pathcreator.pyc.rpc.core.exceptions.RpcApplicationException;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcStatus;

server.onRequest(
        1,
        2,
        OrderRequestCodec.INSTANCE,
        OrderResponseCodec.INSTANCE,
        request -> {
            if (request.quantity() <= 0) {
                throw new RpcApplicationException(RpcStatus.BAD_REQUEST, "quantity must be positive");
            }
            if (request.accountId() == 0) {
                throw new RpcApplicationException(1001, "account is not allowed to trade");
            }
            return handleOrder(request);
        }
);
```

Recommended convention:

- use built-in 4xx/5xx style codes for transport or generic validation failures
- use custom codes `>= 1000` for service-specific business errors

## 14. Which Profile Should I Start With?

### Most users

Start with:

- `OFFLOAD`
- `YIELDING`
- shared receive poller enabled
- `sharedReceivePollerThreads(2)` as the first measurement point
- `ReconnectStrategy.WAIT_FOR_CONNECTION` if brief disconnects should be tolerated

### Lowest possible latency, very small handlers

Try:

- `DIRECT_EXECUTOR`
- `BUSY_SPIN`

Only do this if:

- handlers never block
- handler work is tiny
- burning CPU is acceptable

### Many logical connections

Prefer:

- more `RpcChannel`s
- fewer callers per channel

Instead of:

- one heavily contended shared channel for everything

## 15. Validation

After changing settings, validate with both:

```bash
mvn test
```

and

```bash
mvn -Pbenchmarks -DskipTests package
```

Then run the standalone benchmark scenarios from
[`BENCHMARKS.md`](BENCHMARKS.md).
