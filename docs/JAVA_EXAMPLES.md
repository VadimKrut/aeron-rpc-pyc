# Java Integration Examples

This document shows how to use `rpc-core` from another Java project after you
add the dependency.

The focus here is practical integration:

- start a node
- create channels
- register handlers
- pick a handler mode
- understand reconnect and scaling choices

For setting-by-setting tuning advice, see
[`CHANNEL_TUNING.md`](CHANNEL_TUNING.md).

## 1. Smallest Working Example

```java
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.NodeConfig;
import ru.pathcreator.pyc.rpc.core.RpcChannel;
import ru.pathcreator.pyc.rpc.core.RpcNode;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;

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

        server.onRequest(1, 2, codec, codec, request -> "echo:" + request);

        server.start();
        client.start();

        String response = client.call(1, 2, "hello", codec, codec);
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

## 2. Recommended Service Profile

This is the practical low-latency profile to start with:

- `OFFLOAD`
- `YIELDING`
- shared receive poller enabled
- a small shared RX lane count

```java
import ru.pathcreator.pyc.rpc.core.BackpressurePolicy;
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
                        .backpressurePolicy(BackpressurePolicy.BLOCK)
                        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
                        .rxIdleStrategy(IdleStrategyKind.YIELDING)
                        .offloadExecutor(OFFLOAD_POOL)
                        .pendingPoolCapacity(8192)
                        .registryInitialCapacity(8192)
                        .build()
        );

        channel.start();
    }
}
```

## 3. Several Methods On One Channel

You do not need one stream per method.

A common layout is:

- one channel
- one stream
- several request message types

```java
server.onRequest(1, 101, priceRequestCodec, priceResponseCodec, this::handlePrice);
server.onRequest(2, 102, orderRequestCodec, orderResponseCodec, this::handleOrder);
server.onRequest(3, 103, riskRequestCodec, riskResponseCodec, this::handleRisk);
```

Why this is safe:

- routing uses request `messageTypeId`
- responses are matched by correlation id
- the caller also validates the expected response type

Use separate channels or streams only when you want stronger isolation.

## 4. Handler Modes

### `OFFLOAD`

Best default for real services.

```java
channel.onRequest(
        10,
        11,
        requestCodec,
        responseCodec,
        request -> service.handle(request)
);
```

Use it when handlers may do:

- database access
- filesystem work
- HTTP or gRPC clients
- any non-trivial business logic

### `DIRECT`

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

This removes offload scheduling and copy overhead, but a slow handler blocks
receive progress.

## 5. Reconnect Modes

### Fail fast

```java
ChannelConfig config = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .reconnectStrategy(ReconnectStrategy.FAIL_FAST)
        .build();
```

Use this when the caller should immediately handle the failure.

### Wait for connection

```java
ChannelConfig config = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .defaultTimeout(Duration.ofSeconds(2))
        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
        .build();
```

Use this when short disconnects are acceptable.

### Recreate on disconnect

```java
ChannelConfig config = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .reconnectStrategy(ReconnectStrategy.RECREATE_ON_DISCONNECT)
        .build();
```

Use this only when you need stronger recovery behavior than simply waiting for
the current path to reconnect.

## 6. Many Channels On One Driver

This is the recommended layout when your application has several independent
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
                .build()
);

RpcChannel orders = node.channel(
        ChannelConfig.builder()
                .localEndpoint("10.10.0.11:41002")
                .remoteEndpoint("10.10.0.12:41002")
                .streamId(41002)
                .build()
);

RpcChannel referenceData = node.channel(
        ChannelConfig.builder()
                .localEndpoint("10.10.0.11:41003")
                .remoteEndpoint("10.10.0.12:41003")
                .streamId(41003)
                .build()
);

marketData.start();
orders.start();
referenceData.start();
```

In practice, several channels usually scale better than pushing many callers
through one channel.

## 7. Several Caller Threads On One Channel

This is supported and safe, but it usually scales worse than spreading the
traffic across several channels.

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

sharedChannel.start();

// Many application threads can call sharedChannel.call(...)
```

## 8. Embedded Driver Vs External Driver

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

Better when you already run a dedicated `MediaDriver` and want multiple
processes to share it.

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/dev/shm/aeron-your-driver")
                .embeddedDriver(false)
                .build()
);
```

## 9. Backpressure Policy

### `BLOCK`

Default and usually the right choice for synchronous RPC.

```java
channel.call(
        1,
        2,
        request,
        requestCodec,
        responseCodec,
        Duration.ofMillis(10),
        BackpressurePolicy.BLOCK
);
```

### `FAIL_FAST`

Use this when the caller should immediately fall back or abort.

```java
channel.call(
        1,
        2,
        request,
        requestCodec,
        responseCodec,
        Duration.ofMillis(10),
        BackpressurePolicy.FAIL_FAST
);
```

## 10. Large Payload Guidance

Regular `RpcChannel` supports a single message up to `16 MiB` total size.

That means:

- the normal `RpcChannel` is the supported max-size path today
- usable payload is slightly smaller than `16 MiB` because the envelope is
  included in that limit
- payloads larger than one message should use application-level chunking or a
  separate transfer path

## 11. Remote Errors

If a server-side handler fails, the caller receives a structured
`RemoteRpcException` instead of waiting until timeout.

Built-in transport statuses use HTTP-like numeric codes, and application code
can raise its own business errors:

```java
import ru.pathcreator.pyc.rpc.core.exceptions.RpcApplicationException;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcStatus;

server.onRequest(
        1,
        2,
        orderRequestCodec,
        orderResponseCodec,
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

- use built-in 4xx and 5xx style statuses for transport or shared validation
  failures
- use custom codes `>= 1000` for business-specific errors

## 12. Optional Compatibility And Observability Features

Recent `0.0.9` work added optional service-level features above the core fast
path:

- protocol handshake
- channel listeners
- reconnect recreation
- drain mode

Important guidance:

- keep them off if you want the smallest possible profile
- enable them when you need the behavior
- recent clean steady-state benchmark runs show that these features are now
  close to noise when enabled one at a time

For the benchmark commands and current measurements, see
[`BENCHMARKS.md`](BENCHMARKS.md).

## 13. Validation

After changing settings, validate with both:

```bash
mvn test
```

and

```bash
mvn -Pbenchmarks -DskipTests package
```
