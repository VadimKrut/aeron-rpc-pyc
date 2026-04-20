# Benchmarks

This project has two benchmark layers:

- JMH microbenchmarks for narrow code-path exploration
- a standalone RTT benchmark for real synchronous `RpcChannel.call(...)`

If you care about production-facing request/response behavior, start with the
standalone RTT benchmark.

## Build

```bash
mvn -Pbenchmarks -DskipTests package
```

The benchmark jar is written to:

```text
target/rpc-core-benchmarks.jar
```

On Java 25, run benchmarks with:

```text
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

## Standalone RTT Benchmark

Main class:

```text
ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain
```

This benchmark measures the full synchronous round trip:

1. caller encodes request
2. request is published
3. server handler runs
4. response is published back
5. caller receives and decodes response

It is closed-loop: one in-flight request per caller thread.

## Parameters

| Parameter                    | Meaning                                                 |
|------------------------------|---------------------------------------------------------|
| `--payload`                  | request and response payload size in bytes              |
| `--rate`                     | target total request rate across caller threads         |
| `--threads`                  | caller thread count                                     |
| `--channels`                 | number of client/server channel pairs                   |
| `--rx-poller-threads`        | number of shared RX poller lanes per idle-strategy kind |
| `--rx-poller-fragment-limit` | fragment limit per poll                                 |
| `--burst-size`               | messages per pacing interval per caller thread          |
| `--handler`                  | `DIRECT` or `OFFLOAD`                                   |
| `--idle`                     | `YIELDING`, `BUSY_SPIN`, or `BACKOFF`                   |
| `--handler-io-nanos`         | artificial delay inside the server handler              |
| `--handler-io-micros`        | same as above, in microseconds                          |
| `--listeners`                | enable channel listeners during the run                 |
| `--protocol-handshake`       | enable protocol handshake                               |
| `--reconnect`                | reconnect mode, including `RECREATE_ON_DISCONNECT`      |

## Benchmark Methodology Notes

For meaningful comparisons:

- run one scenario at a time
- keep the machine state as stable as possible
- compare runs on the same OS and host layout
- do not mix Windows and WSL numbers as if they were the same environment

Important note for `--protocol-handshake`:

- the benchmark now primes the handshake before the timed phase
- that means steady-state RTT numbers exclude the one-time first-contact
  handshake cost
- this is intentional, because handshake is a startup or session concern, not a
  per-call concern

## Recommended Starting Point

For practical low-latency services with real handler work:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/rpc-core-benchmarks.jar \
  ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain \
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

- `OFFLOAD` keeps user work off the receive path
- `YIELDING` is the most practical low-latency default
- `burst-size=1` makes RTT comparison cleaner

## Useful Scenarios

### 1. Single-channel baseline

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/rpc-core-benchmarks.jar \
  ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain \
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

Use this to understand transport overhead with almost no caller contention.

### 2. Several caller threads on one channel

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/rpc-core-benchmarks.jar \
  ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain \
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

This shows contention on one publication and one channel path.

### 3. Many channels on one driver

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/rpc-core-benchmarks.jar \
  ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain \
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

This is the most relevant layout when many `RpcChannel`s share one
`MediaDriver`.

### 4. IO-like handler simulation

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp target/rpc-core-benchmarks.jar \
  ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain \
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

Useful when real handlers will do database, filesystem, or network I/O.

## How To Read Results

Focus on:

- `p50` for normal-case transport cost
- `p90` for practical latency under load
- `p99` and `p99.9` for tail sensitivity
- `Achieved rate` for whether the system actually holds the target rate

For synchronous RPC, `p90` and `p99` are usually more useful than mean latency.

## Current Feature-Cost Snapshot

The most useful recent comparison was a clean sequential WSL run with:

- `payload=32`
- `threads=4`
- `channels=2`
- `handler=OFFLOAD`
- `idle=YIELDING`
- `rx-poller-threads=4`

Baseline:

| Mode     |       p50 |       p90 |        p99 |      p99.9 | Achieved rate |
|----------|----------:|----------:|-----------:|-----------:|--------------:|
| baseline | 26.527 us | 55.359 us | 116.287 us | 277.759 us | 115,474 ops/s |

Optional features measured against that baseline:

| Mode                               |       p50 |       p90 |        p99 |      p99.9 | Achieved rate | Practical read                    |
|------------------------------------|----------:|----------:|-----------:|-----------:|--------------:|-----------------------------------|
| `listeners=true`                   | 25.647 us | 56.415 us | 119.743 us | 267.263 us | 116,246 ops/s | effectively noise in steady-state |
| `protocol-handshake=true`          | 26.399 us | 55.903 us | 112.831 us | 293.119 us | 115,177 ops/s | steady-state cost close to noise  |
| `reconnect=RECREATE_ON_DISCONNECT` | 25.023 us | 55.295 us | 113.023 us | 270.079 us | 120,300 ops/s | steady-state cost close to noise  |

What this means:

- optional service features no longer dominate the hot path in steady-state
- the meaningful handshake cost is still the first-contact/session event, not
  the steady-state call path
- if you want the leanest possible profile, you can still keep these features
  off

## Practical Observations

### 1 channel / 1 thread

This is the clean reference for transport overhead.

On healthy local runs, this profile should stay close to the lowest numbers in
your environment.

### Many threads on one channel

Latency rises faster and throughput usually stops scaling linearly.

This is expected because callers contend on one channel path.

### Several channels on one driver

Usually better than forcing all callers through one channel.

This is often the better scaling direction in real systems.

### RX poller count

`--rx-poller-threads` is always machine-dependent.

Too few poller lanes can under-serve many channels. Too many can start
competing with caller threads, offload work, and the media driver.

There is no universal best value. Measure on the actual target machine.

## Comparison With Raw Aeron

Raw Aeron echo benchmarks will usually look better than `rpc-core` because raw
Aeron does less work:

- no request/response API layer
- no pending-call registry
- no codec dispatch
- no response matching for blocking callers
- no user handler abstraction

The useful comparison is not "why is RPC slower than raw Aeron".

The useful comparison is:

- how much latency the synchronous RPC abstraction adds
- how it scales across channels
- whether it stays correct and predictable under real handler behavior

## JMH Microbenchmarks

JMH benchmarks are still useful for narrow code-path exploration, but they are
secondary to the standalone RTT benchmark for production-facing sync behavior.

Build:

```bash
mvn -Pbenchmarks -DskipTests package
```

List JMH benchmarks:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar target/rpc-core-benchmarks.jar -l
```

Run one JMH benchmark:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar target/rpc-core-benchmarks.jar RpcChannelBenchmark
```

## Validation Beyond Benchmarks

Correctness still matters as much as speed.

Useful validation commands:

```bash
mvn test
```

and

```bash
mvn -Pbenchmarks -DskipTests package
```