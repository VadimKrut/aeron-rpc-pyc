# Channel Tuning Guide

This document explains the most important `NodeConfig` and `ChannelConfig`
settings in `rpc-core`, what they change, and when they are worth touching.

The goal is simple:

- keep the default fast path clean
- understand which settings affect latency, throughput, and CPU
- know which newer service features are truly optional

## First Rule

Start simple, then measure.

For most services, a good first profile is:

- `OFFLOAD`
- `YIELDING`
- shared receive poller enabled
- `sharedReceivePollerThreads(2)` as the first measurement point
- `ReconnectStrategy.WAIT_FOR_CONNECTION` only if short disconnects should be
  tolerated

Do not tune ten things at once. Change one variable, then rerun the standalone
benchmark.

## Fast Baseline Profile

```java
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
                .rxIdleStrategy(IdleStrategyKind.YIELDING)
                .reconnectStrategy(ReconnectStrategy.FAIL_FAST)
                .offloadExecutor(offloadPool)
                .build()
);
```

This keeps the transport close to the intended low-latency profile while still
being practical for real services.

## What Usually Matters Most

If you only tune a few things, tune these:

1. handler mode: `OFFLOAD` vs `DIRECT`
2. receive idle strategy
3. number of channels vs number of caller threads per channel
4. shared receive poller thread count
5. reconnect behavior

Most other settings are secondary until your workload is already understood.

## Handler Execution

### `OFFLOAD`

Recommended default for real services.

Use it when handlers may do:

- blocking I/O
- cache access
- business logic with noticeable work
- any code that should not block receive progress

Effect:

- safer receive path
- slightly more moving parts
- much better behavior when handlers are not tiny

### `DIRECT`

Only for extremely small and predictable handlers.

Use it when handlers are:

- purely in-memory
- very short
- non-blocking
- allocation-light

Effect:

- removes offload scheduling and copy overhead
- but a slow handler blocks receive progress for that channel

If in doubt, use `OFFLOAD`.

## Receive Idle Strategy

### `YIELDING`

Best practical default for low-latency RPC.

Why:

- low latency without fully dedicating a core the way `BUSY_SPIN` does
- usually the best first choice for real deployments

### `BUSY_SPIN`

Best raw latency, highest CPU usage.

Use it only when:

- you can dedicate cores
- the machine is built for very hot paths
- you have already measured that it helps

### `BACKOFF`

Lower CPU usage, more conservative latency.

Use it when:

- channels are often idle
- CPU headroom matters more than absolute latency

## Shared Receive Poller

`NodeConfig` controls whether channels share receive polling.

Important settings:

| Setting                            | Meaning                                   | Practical note                                           |
|------------------------------------|-------------------------------------------|----------------------------------------------------------|
| `sharedReceivePoller`              | share RX polling across channels          | usually a good idea for many channels                    |
| `sharedReceivePollerThreads`       | number of RX lanes per idle-strategy kind | too low can under-serve channels, too high can waste CPU |
| `sharedReceivePollerFragmentLimit` | fragment limit per poll cycle             | affects fairness and per-poll work                       |

### When it helps

Shared receive polling is especially useful when:

- many `RpcChannel`s run on one `MediaDriver`
- each individual channel is not busy enough to justify its own RX thread
- you want better many-channel density

### Starting point

Try:

- `sharedReceivePoller(true)`
- `sharedReceivePollerThreads(2)`
- `sharedReceivePollerFragmentLimit(16)`

Then measure on your target host.

There is no universal best RX poller count.

## One Channel Or Many

This matters a lot.

### One channel, many caller threads

Safe, but usually not the best scaling path.

Why:

- many callers contend on one publication path
- tail latency tends to rise sooner

### Several channels on one driver

Usually better for scaling.

Why:

- less contention per channel
- better logical isolation
- easier to reason about service boundaries

If your workload can be split into several logical connections, prefer more
channels before adding more caller threads to one channel.

## Timeouts And Backpressure

### `defaultTimeout`

How long a normal synchronous call is allowed to take unless you override it per
call.

Think of it as the end-to-end request deadline.

### `offerTimeout`

How long publish retries are allowed when the transport is back-pressured.

If you use:

- `BLOCK`: publish retries continue until `offerTimeout`
- `FAIL_FAST`: the call fails immediately on backpressure

For synchronous RPC, `BLOCK` is usually the right default.

Use `FAIL_FAST` when the caller should immediately fall back to another path.

## Heartbeats

Heartbeats help the channel notice liveness loss.

Important settings:

| Setting                | Meaning                               |
|------------------------|---------------------------------------|
| `heartbeatInterval`    | how often heartbeat frames are sent   |
| `heartbeatMissedLimit` | how many misses mark the channel down |

Smaller intervals react faster, but create more heartbeat traffic.

For most services, leave them moderate unless you have a clear reason to tighten
them.

## Reconnect Strategy

### `FAIL_FAST`

Best when the caller should immediately handle the failure.

Use it when:

- fallback logic exists outside the channel
- you care more about immediate failure than waiting

### `WAIT_FOR_CONNECTION`

Best when brief disconnects are acceptable.

Use it when:

- the channel may recover shortly
- waiting is better than instant failure

### `RECREATE_ON_DISCONNECT`

Optional recovery mode for cases where the transport resources may need to be
recreated after a real disconnect.

Important note:

- this is a service feature, not part of the minimal ultra-fast profile
- in recent clean steady-state measurements its overhead was close to noise
- still, only enable it when you need recovery behavior

## Optional Protocol Handshake

Protocol handshake is an optional compatibility check between peers.

Use it when you want:

- fail-fast behavior on protocol mismatch
- version and capability validation
- clearer startup/session compatibility checks

Do not use it just because it exists.

If both sides are deployed together and version mismatch is already controlled,
you may not need it.

Performance note:

- the meaningful cost is at first contact
- recent benchmark runs exclude that one-time cost from steady-state timing
- steady-state overhead after handshake completion is now close to noise

## Optional Listeners

Listeners are for observability and lifecycle hooks.

Use them when you need events such as:

- call started or completed
- remote error
- channel up or down
- reconnect attempts
- protocol mismatch or handshake state

Performance note:

- listeners are optional
- after the latest fast-path cleanup, disabled listeners are effectively free
- enabled listeners in recent steady-state runs were still close to noise, but
  they remain outside the absolute minimal core profile

If ultra-low latency is the only goal, keep them off.

## Drain Mode

Drain mode is useful for graceful shutdown.

Use it when you need:

- stop taking new calls
- allow in-flight work to finish
- then close cleanly

This is a lifecycle tool, not a tuning tool. Do not enable it unless your
service lifecycle actually needs it.

## Pending Pools And Registry Capacity

These settings matter when concurrency is high:

| Setting                   | Meaning                             |
|---------------------------|-------------------------------------|
| `pendingPoolCapacity`     | pooled pending-call objects         |
| `registryInitialCapacity` | initial pending-call registry size  |
| `offloadTaskPoolSize`     | pooled offload task objects         |
| `offloadCopyPoolSize`     | pooled buffers for offloaded copies |
| `offloadCopyBufferSize`   | size of each pooled copy buffer     |

Raise them when:

- concurrency is high
- the service is very hot
- default pool sizes cause too much fallback allocation

Do not raise them blindly. Bigger pools also mean more retained memory.

## Payload Size

Regular `RpcChannel` supports one message up to `16 MiB` total size.

That means:

- the supported max-size path today is the normal `RpcChannel`
- usable payload is slightly smaller because the protocol envelope is included
- files or payloads larger than one message should use chunking or a separate
  transfer path

## Recommended Profiles

### Ultra-fast default profile

Use:

- `OFFLOAD`
- `YIELDING`
- shared receive poller enabled
- handshake off
- listeners off
- reconnect recreation off unless needed

This keeps the normal path as close as possible to the lean core profile.

### Practical service profile

Use:

- `OFFLOAD`
- `YIELDING`
- shared receive poller enabled
- `WAIT_FOR_CONNECTION`
- optional listeners only if you need operational visibility

### Small in-memory hot path

Try:

- `DIRECT_EXECUTOR`
- `BUSY_SPIN`

Only if:

- handlers never block
- the code path is tiny
- CPU burn is acceptable

## How To Tune Safely

Use this order:

1. get a baseline with the standalone benchmark
2. change one setting
3. rerun the same benchmark scenario
4. compare `p50`, `p90`, `p99`, `p99.9`, and achieved rate

Do not trust one noisy run. Use repeatable commands and compare like with like.

For the benchmark commands and current measurements, see
[`BENCHMARKS.md`](BENCHMARKS.md).