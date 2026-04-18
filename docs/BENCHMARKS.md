# Benchmarks

This project currently has two benchmark layers:

- JMH microbenchmarks for low-level transport/code-path exploration
- a standalone latency benchmark for realistic synchronous request/response RTT

If you care about real `RpcChannel.call(...)` behavior, use the standalone latency benchmark first.

## Build

```bash
mvn -Pbenchmarks -DskipTests package
```

The benchmark jar will be written to:

```text
target/aeron-rpc-benchmarks.jar
```

On Java 25, run benchmarks with:

```text
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

## Standalone Latency Benchmark

Main class:

```text
ru.pathcreator.pyc.bench.RpcLatencyHistogramMain
```

This benchmark measures the full synchronous round trip:

1. caller encodes request
2. request is published through `RpcChannel`
3. server handler runs
4. response is published back
5. caller receives and decodes response

It is closed-loop: one in-flight request per caller thread.

## Parameters

| Parameter                    | Meaning                                                 |
|------------------------------|---------------------------------------------------------|
| `--payload`                  | request and response payload size in bytes              |
| `--rate`                     | target total request rate across all caller threads     |
| `--threads`                  | caller thread count                                     |
| `--channels`                 | number of client/server channel pairs                   |
| `--rx-poller-threads`        | number of shared RX poller lanes per idle-strategy kind |
| `--rx-poller-fragment-limit` | fragment limit per poll                                 |
| `--burst-size`               | messages per pacing interval per caller thread          |
| `--handler`                  | `DIRECT` or `OFFLOAD`                                   |
| `--idle`                     | `YIELDING`, `BUSY_SPIN`, or `BACKOFF`                   |
| `--handler-io-nanos`         | artificial delay inside the server handler              |
| `--handler-io-micros`        | same as above, in microseconds                          |

## Recommended Starting Point

For blocking or I/O-like handlers:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/aeron-rpc-benchmarks.jar \
  ru.pathcreator.pyc.bench.RpcLatencyHistogramMain \
  --payload=32 \
  --rate=150000 \
  --threads=8 \
  --channels=8 \
  --rx-poller-threads=2 \
  --burst-size=1 \
  --warmup-iterations=5 \
  --warmup-messages=25000 \
  --measurement-iterations=10 \
  --measurement-messages=100000 \
  --handler=OFFLOAD \
  --idle=YIELDING
```

Why this profile:

- `OFFLOAD` keeps user I/O off the receive path
- `YIELDING` is the most practical low-latency default
- `burst-size=1` makes the test easier to compare with request/response RTT

## Useful Scenarios

### 1. Single-channel baseline

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/aeron-rpc-benchmarks.jar \
  ru.pathcreator.pyc.bench.RpcLatencyHistogramMain \
  --payload=32 \
  --rate=100000 \
  --threads=1 \
  --channels=1 \
  --rx-poller-threads=4 \
  --burst-size=1 \
  --warmup-iterations=5 \
  --warmup-messages=25000 \
  --measurement-iterations=10 \
  --measurement-messages=100000 \
  --handler=OFFLOAD \
  --idle=YIELDING
```

Use this to understand transport overhead without caller contention.

### 2. Many threads on one channel

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/aeron-rpc-benchmarks.jar \
  ru.pathcreator.pyc.bench.RpcLatencyHistogramMain \
  --payload=32 \
  --rate=150000 \
  --threads=8 \
  --channels=1 \
  --rx-poller-threads=4 \
  --burst-size=1 \
  --warmup-iterations=5 \
  --warmup-messages=25000 \
  --measurement-iterations=10 \
  --measurement-messages=100000 \
  --handler=OFFLOAD \
  --idle=YIELDING
```

This is the "many callers compete for one `ConcurrentPublication`" scenario.

### 3. Many channels on one driver

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/aeron-rpc-benchmarks.jar \
  ru.pathcreator.pyc.bench.RpcLatencyHistogramMain \
  --payload=32 \
  --rate=150000 \
  --threads=8 \
  --channels=8 \
  --rx-poller-threads=2 \
  --burst-size=1 \
  --warmup-iterations=5 \
  --warmup-messages=25000 \
  --measurement-iterations=10 \
  --measurement-messages=100000 \
  --handler=OFFLOAD \
  --idle=YIELDING
```

This is the most relevant scenario if users will create many `RpcChannel`s on one `MediaDriver`.

### 4. IO-like handler simulation

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/aeron-rpc-benchmarks.jar \
  ru.pathcreator.pyc.bench.RpcLatencyHistogramMain \
  --payload=32 \
  --rate=20000 \
  --threads=8 \
  --channels=4 \
  --rx-poller-threads=2 \
  --burst-size=1 \
  --warmup-iterations=2 \
  --warmup-messages=10000 \
  --measurement-iterations=3 \
  --measurement-messages=20000 \
  --handler=OFFLOAD \
  --handler-io-micros=250 \
  --idle=YIELDING
```

This is useful when your real server handlers do database, HTTP, filesystem, or other blocking work.

## How to Read the Results

Focus on:

- `p50`: normal-case transport behavior
- `p90`: practical latency under load
- `p99` and `p99.9`: tail sensitivity
- `Achieved rate`: whether the system actually keeps up with the requested rate

For synchronous RPC, `p90` and `p99` are usually more useful than just mean latency.

## Practical Observations From Current WSL Runs

These are example observations from local WSL measurements with `OFFLOAD + YIELDING`, `payload=32`, `burst=1`.

### 1 channel / 1 thread

- `p50 ~ 6 us`
- `p90 ~ 16 us`
- achieved rate close to target `100k`

This is the right reference for "pure" transport overhead.

### 1 channel / 8 threads

- latency rises sharply
- throughput does not scale linearly

This is expected: multiple caller threads contend on one `ConcurrentPublication` and one channel path.

### 2-4 channels / 8 threads

- usually much better than 1 channel / 8 threads
- throughput stays close to the `150k` target

This is the sweet spot for "more independent channels on one driver".

### 8 channels / 8 threads

The result depends strongly on `--rx-poller-threads`.

In our recent WSL runs:

- `rx-poller-threads=2` was better than `4`
- `rx-poller-threads=8` looked worse in achieved rate and tails

That suggests a real tradeoff:

- too few RX pollers can under-serve many channels
- too many `YIELDING` RX pollers start competing with caller threads, offload work, and the media driver

There is no universal best number. Measure on the target machine.

## Comparison With Raw Aeron

Raw Aeron echo benchmarks will usually look better than `aeron-rpc` because raw Aeron does less work:

- no request/response API layer
- no pending-call registry
- no codec dispatch
- no response matching for blocking callers
- no user handler abstraction

So the right comparison is not "why is RPC slower than raw Aeron".

The right comparison is:

- how much extra latency the synchronous RPC abstraction adds
- how well it scales across channels
- whether it remains safe and predictable under real handler behavior

In practice:

- with one caller thread, transport overhead stays relatively low
- with many threads on one channel, publication contention becomes visible
- with several channels on one driver, scaling is better than forcing all traffic through one channel

## JMH Microbenchmarks

JMH benchmarks are still useful for narrow code-path exploration, but they are secondary to the standalone RTT benchmark
when evaluating production-facing synchronous behavior.

Build:

```bash
mvn -Pbenchmarks -DskipTests package
```

List JMH benchmarks:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar target/aeron-rpc-benchmarks.jar -l
```

Run JMH benchmark:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark
```

## Validation Beyond Benchmarks

There are also correctness tests for the important non-happy-path cases:

- concurrent response correlation
- IO-like offloaded handlers across several channels
- reconnect wait strategy

Run them with:

```bash
mvn test
```