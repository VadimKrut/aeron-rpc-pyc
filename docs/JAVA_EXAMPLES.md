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

For startup-time channel and method inventory, validation, and reporting, see
[`SERVICE_REGISTRY.md`](SERVICE_REGISTRY.md).

For listener-based counters outside the transport core, see
[`METRICS_MODULE.md`](METRICS_MODULE.md).

For release and benchmark hygiene, see
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) and
[`PERFORMANCE_DISCIPLINE.md`](PERFORMANCE_DISCIPLINE.md).

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

```java
server.onRequest(1,101,priceRequestCodec, priceResponseCodec, this::handlePrice);
server.

onRequest(2,102,orderRequestCodec, orderResponseCodec, this::handleOrder);
server.

onRequest(3,103,riskRequestCodec, riskResponseCodec, this::handleRisk);
```

This is safe because:

- routing uses request `messageTypeId`
- responses are matched by correlation id
- the caller also validates the expected response type

## 4. Optional Service Registry

```java
import java.nio.file.Path;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcMethod;
import ru.pathcreator.pyc.rpc.schema.RpcServiceRegistry;

RpcMethod<OrderRequest, OrderResponse> placeOrder =
        RpcMethod.of(10, 110, orderRequestCodec, orderResponseCodec);

RpcMethod<RiskRequest, RiskResponse> checkRisk =
        RpcMethod.of(11, 111, riskRequestCodec, riskResponseCodec);

RpcServiceRegistry.Builder builder = RpcServiceRegistry.builder();
builder.

channel(
                "orders",
                ChannelConfig.builder()
                        .

localEndpoint("10.10.0.11:40101")
                        .

remoteEndpoint("10.10.0.12:40101")
                        .

streamId(2001)
                        .

protocolHandshakeEnabled(true)
                        .

protocolVersion(2)
                        .

build()
        )
                .

method("place-order",placeOrder, OrderRequest .class, OrderResponse .class, "v2","main order entry path")
        .

method("check-risk",checkRisk, RiskRequest .class, RiskResponse .class);

RpcServiceRegistry registry = builder.build();
System.out.

println(registry.renderTextReport());
        System.out.

println(registry.renderJsonReport());
        registry.

writeTextReport(Path.of("build", "rpc-schema.txt"));
        registry.

writeJsonReport(Path.of("build", "rpc-schema.json"));
```

This is a startup-time helper only. It does not change the transport call path.

## 5. Optional Metrics Layer

```java
import java.nio.file.Path;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcChannel;
import ru.pathcreator.pyc.rpc.metrics.RpcMetricsListener;
import ru.pathcreator.pyc.rpc.metrics.RpcMetricsSnapshot;

RpcMetricsListener metrics = new RpcMetricsListener();

RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("127.0.0.1:40101")
                .remoteEndpoint("127.0.0.1:40102")
                .streamId(2001)
                .listener(metrics)
                .build()
);

channel.

start();

RpcMetricsSnapshot snapshot = metrics.snapshot();
System.out.

println(snapshot.callsStarted());
        System.out.

println(snapshot.callsSucceeded());
        System.out.

println(snapshot.completedCalls());
        System.out.

println(snapshot.renderJsonReport());
        metrics.

writeTextReport(Path.of("build", "rpc-metrics.txt"));
        metrics.

writeJsonReport(Path.of("build", "rpc-metrics.json"));
```

This layer is optional and remains outside `rpc.core`.

## 6. Handler Modes

### `OFFLOAD`

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

Use it when handlers may do real work such as database calls, filesystem work,
HTTP clients, or non-trivial business logic.

### `DIRECT`

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

Use `DIRECT_EXECUTOR` only for extremely small predictable handlers.

## 7. Reconnect Modes

```java
ChannelConfig failFast = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .reconnectStrategy(ReconnectStrategy.FAIL_FAST)
        .build();

ChannelConfig waitForConnection = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .defaultTimeout(Duration.ofSeconds(2))
        .reconnectStrategy(ReconnectStrategy.WAIT_FOR_CONNECTION)
        .build();

ChannelConfig recreate = ChannelConfig.builder()
        .localEndpoint("127.0.0.1:40101")
        .remoteEndpoint("127.0.0.1:40102")
        .streamId(1001)
        .reconnectStrategy(ReconnectStrategy.RECREATE_ON_DISCONNECT)
        .build();
```

## 8. Many Channels On One Driver

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

marketData.

start();
orders.

start();
```

In practice, several channels usually scale better than pushing many callers
through one channel.

## 9. Several Caller Threads On One Channel

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

## 10. Embedded Driver Vs External Driver

```java
RpcNode embedded = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/rpc-core-demo")
                .embeddedDriver(true)
                .build()
);

RpcNode external = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/dev/shm/aeron-your-driver")
                .embeddedDriver(false)
                .build()
);
```

## 11. Backpressure Policy

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

        channel.

call(
        1,
                2,
        request,
        requestCodec,
        responseCodec,
        Duration.ofMillis(10),

BackpressurePolicy.FAIL_FAST
);
```

## 12. Large Payload Guidance

Regular `RpcChannel` supports a single message up to `16 MiB` total size.

That means:

- the normal `RpcChannel` is the supported max-size path today
- usable payload is slightly smaller than `16 MiB` because the envelope is
  included in that limit
- payloads larger than one message should use application-level chunking or a
  separate transfer path

## 13. Remote Errors

```java
import ru.pathcreator.pyc.rpc.core.exceptions.RpcApplicationException;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcStatus;

server.onRequest(
        1,
                2,
        orderRequestCodec,
        orderResponseCodec,
        request ->{
        if(request.

quantity() <=0){
        throw new

RpcApplicationException(RpcStatus.BAD_REQUEST, "quantity must be positive");
            }
                    if(request.

accountId() ==0){
        throw new

RpcApplicationException(1001,"account is not allowed to trade");
            }
                    return

handleOrder(request);
        }
                );
```

Recommended convention:

- use built-in 4xx and 5xx style statuses for transport or shared validation
  failures
- use custom codes `>= 1000` for business-specific errors

## 14. Optional Compatibility And Observability Features

Service-level features above the core fast path include:

- protocol handshake
- channel listeners
- reconnect recreation
- drain mode

Keep them off if you want the smallest possible profile, and turn them on only
when you need the behavior.

## 15. Validation

After changing settings, validate with both:

```bash
mvn test
```

and

```bash
mvn -Pbenchmarks -DskipTests package
```

If the service has many methods or channels, it is also a good idea to render
and review the startup registry report before rollout.