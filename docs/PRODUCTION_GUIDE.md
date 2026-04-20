# Production Guide

This guide is about operating `rpc-core` safely and predictably in real
services.

The focus is not framework convenience. The focus is:

- keep the core path fast
- understand what to turn on and what to leave off
- deploy it in a way that is easy to reason about later

## 1. Keep The Default Path Lean

If lowest steady-state latency is the main goal, start with:

- `OFFLOAD`
- `YIELDING`
- shared receive poller enabled
- a small RX poller lane count such as `2`
- listeners off
- protocol handshake off unless compatibility checks matter
- reconnect recreation off unless stronger recovery is required

This keeps the transport close to the lean sync RPC profile.

## 2. Prefer Many Channels Over Many Threads On One Channel

If the service has several logical flows, scaling is usually cleaner by using:

- more `RpcChannel`s
- fewer caller threads per channel

instead of:

- one heavily contended shared channel for everything

This tends to reduce contention and improve isolation.

## 3. Shared Receive Poller Guidance

For many channels on one driver, start with:

- `sharedReceivePoller(true)`
- `sharedReceivePollerThreads(2)`
- `sharedReceivePollerFragmentLimit(16)`

Too few RX lanes can under-serve channels. Too many can start competing with
callers, handler execution, and the media driver.

There is no universal best value. Measure on the actual deployment host.

## 4. OFFLOAD Vs DIRECT

### Use `OFFLOAD` for most services

This is the safest practical default when handlers may do:

- database calls
- file I/O
- HTTP or gRPC calls
- meaningful business logic

### Use `DIRECT` only for tiny handlers

Use `DIRECT_EXECUTOR` only when:

- the handler is extremely small
- it never blocks
- you are intentionally chasing the smallest possible path

Remember that a slow direct handler blocks receive progress.

## 5. Reconnect Modes

### `FAIL_FAST`

Best when the caller should immediately fail and choose another path.

### `WAIT_FOR_CONNECTION`

Best when a short disconnect is acceptable and waiting is better than immediate
failure.

### `RECREATE_ON_DISCONNECT`

Use only when you need a stronger local recovery mode.

It is optional, and recent steady-state measurements showed it close to noise
when enabled in the tested profile, but it is still not part of the minimal
core shape.

## 6. Protocol Handshake

Use protocol handshake only when startup compatibility checks matter.

Examples:

- multiple deployable versions may coexist
- capabilities need to be validated before traffic
- you want a clean startup failure instead of later surprises

If both sides are always deployed together in a tightly controlled environment,
it may be reasonable to leave it off.

## 7. Listeners

Listeners are useful for:

- call lifecycle visibility
- remote error visibility
- reconnect visibility
- channel up/down visibility

If operations really need those signals, turn them on.

If the service is extremely latency-sensitive and the hooks are not needed,
leave them off.

If you need simple counters without bringing a monitoring framework into the
core deployment, use the optional listener-based metrics layer in
`ru.pathcreator.pyc.rpc.metrics`.

## 8. Driver Choice

### Embedded driver

Good for:

- local development
- standalone processes
- tests

### External driver

Good for:

- shared driver setups
- multi-process deployments
- tighter operational control

Choose one intentionally. Do not drift between the two without measuring and
documenting why.

## 9. Payload Guidance

Regular `RpcChannel` supports one message up to `16 MiB` total size.

That means:

- the usable payload is slightly smaller than `16 MiB`
- very large files or transfers should use chunking or a separate path

Do not design the system around oversized single-message payloads unless you are
ready to manage that tradeoff explicitly.

## 10. Security Guidance

`rpc-core` is a transport core, not a full secure deployment solution.

Recommended approach:

- keep encryption and network authentication outside the core transport
- prefer outer secure deployment models such as WireGuard, IPSec, service mesh,
  or another proven network-level wrapper

This keeps the RPC path simpler and avoids mixing transport concerns with custom
crypto design.

## 11. Startup Checklist

Before a service starts taking traffic:

1. validate channel layout
2. validate method ids
3. validate protocol settings when handshake is enabled
4. log or export the service map
5. confirm the expected number of channels and streams

The optional service registry layer exists exactly for this kind of startup
check.

## 12. Shutdown Checklist

For controlled shutdowns:

1. stop accepting new external work
2. enter drain mode if the service uses it
3. allow in-flight requests to finish
4. close channels
5. close the node

Make graceful shutdown part of the service lifecycle plan, not an afterthought.

## 13. Benchmark Discipline

When you change config or behavior:

1. run the same scenario before and after
2. compare on the same OS and host layout
3. do not mix Windows and WSL numbers as if they were the same environment
4. compare `p50`, `p90`, `p99`, `p99.9`, and achieved rate
5. do not trust a single noisy run

For command templates and current observations, see
[`BENCHMARKS.md`](BENCHMARKS.md).

Useful recent reference points from clean WSL sanity runs:

- `1 channel / 1 thread / 32 bytes`: `p50 7.027 us`, `p90 12.367 us`,
  `p99 39.359 us`, `99,754 ops/s`
- `2 channels / 4 threads / 32 bytes`: `p50 24.367 us`, `p90 55.743 us`,
  `p99 106.751 us`, `120,755 ops/s`

These are not universal targets, but they are a good reminder of the shape you
want to preserve when adding startup-time or operational features.

## 14. Framework Boundaries

The repository now keeps the intended layering explicit:

- `rpc.core` for the transport path
- `rpc.schema` for startup-time validation and reporting
- `rpc.metrics` for optional listener-based counters

That separation is intentional. Keep framework-heavy integration outside the
transport core whenever possible.