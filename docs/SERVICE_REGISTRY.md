# Service Registry

`rpc-core` includes an optional startup-time registry layer above `RpcMethod`.

Important design rule:

- it lives outside `ru.pathcreator.pyc.rpc.core`
- it is meant for validation, reporting, and startup analysis
- it does not participate in the transport hot path

The registry classes live in:

```text
ru.pathcreator.pyc.rpc.schema
```

## Why It Exists

When a service grows, method definitions become easy to lose track of:

- duplicate `requestMessageTypeId`
- conflicting method names
- unclear stream layout
- unclear protocol version and capability settings per channel
- suspicious startup config combinations that are not outright invalid

The registry gives you a startup-time place to describe and validate the shape
of the service before traffic begins.

## What It Does

`RpcServiceRegistry` can:

- register logical channels
- attach `RpcMethod` definitions to those channels
- validate duplicate method names inside a channel
- validate duplicate request message type ids inside a channel
- reject reserved transport message type ids
- count channels and methods
- analyze suspicious startup config combinations
- render readable text reports
- render machine-readable JSON reports
- write those reports to disk

## What It Does Not Do

It is not:

- a transport replacement
- a runtime dispatch layer
- a hot-path routing abstraction
- a schema negotiation protocol

It is intentionally a startup-time helper.

## Minimal Example

```java
import java.nio.file.Path;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcMethod;
import ru.pathcreator.pyc.rpc.schema.RpcServiceRegistry;
import ru.pathcreator.pyc.rpc.schema.RpcValidationIssue;

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

protocolCapabilities(0b101L)
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

        for(
RpcValidationIssue issue :registry.

analyze()){
        System.out.

println(issue.code() +": "+issue.

message());
        }

        registry.

writeTextReport(Path.of("build", "rpc-schema.txt"));
        registry.

writeJsonReport(Path.of("build", "rpc-schema.json"));
```

## Typical Text Report

```text
rpc-core service registry
Channels: 1
Methods: 2
Warnings: 0

Channel: orders
  Stream: 2001
  Local endpoint: 10.10.0.11:40101
  Remote endpoint: 10.10.0.12:40101
  RX idle strategy: YIELDING
  Reconnect strategy: WAIT_FOR_CONNECTION
  Protocol handshake: true
  Protocol version: 2
  Protocol capabilities: 0x5
  Required remote capabilities: 0x1
  Listener count: 0
  Methods: 2
    - place-order [req=10, resp=110, requestType=com.example.OrderRequest, responseType=com.example.OrderResponse, version=v2] - main order entry path
    - check-risk [req=11, resp=111, requestType=com.example.RiskRequest, responseType=com.example.RiskResponse]
```

## Typical Warning Examples

`analyze()` returns non-fatal warnings that are still worth reviewing before
rollout.

Current examples include:

- same local and remote endpoint
- custom protocol settings while handshake is disabled
- `offerTimeout` longer than `defaultTimeout`
- very small pending pool relative to method count
- duplicate transport layout across logical channels

## Recommended Use

Use this layer when:

- the service has several methods
- you want startup-time conflict detection
- you want a readable inventory of the service shape
- you want logs or deployment tooling to see channels, streams, and method ids
- you want repeatable text/JSON startup artifacts

## Performance Note

This layer is intended to be used before the service starts accepting real
traffic.

It should not be used as an extra per-call dispatch layer, and it is not
designed to change `RpcChannel.call(...)` behavior.

That is why it lives outside `rpc.core` and only holds metadata, validation,
and reporting.