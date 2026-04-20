# Metrics Module

`rpc-core` keeps transport observability in the optional
`RpcChannelListener` API. The built-in metrics layer lives outside
`rpc.core` in `ru.pathcreator.pyc.rpc.metrics`.

## What It Is

`RpcMetricsListener` is a ready-to-use listener-backed counter collector.
It records:

- calls started
- calls succeeded
- calls timed out
- calls failed
- remote errors
- channel up/down transitions
- drain lifecycle events
- reconnect attempt/success/failure
- protocol handshake start/success/failure

## Why It Lives Outside `rpc.core`

The transport core should stay focused on latency and correctness.
Metrics are useful, but they are a service-level concern, so this layer is
kept separate and optional.

When no listeners are configured, the transport stays on the leanest path.

## Example

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
                .streamId(1001)
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

## Snapshot Helpers

`RpcMetricsSnapshot` provides convenience helpers:

- `completedCalls()`
- `isEmpty()`
- `deltaSince(...)`
- `renderTextReport()`
- `renderJsonReport()`

Example:

```java
RpcMetricsSnapshot before = metrics.snapshot();

// traffic happens

RpcMetricsSnapshot after = metrics.snapshot();
RpcMetricsSnapshot delta = after.deltaSince(before);
System.out.

println(delta.renderTextReport());
```

## Current Scope

This module intentionally stays simple:

- in-memory counters
- no dependency on Micrometer, Prometheus, or OpenTelemetry
- no polling thread
- no export backend

That keeps it easy to adopt and safe to leave out of the hot profile.