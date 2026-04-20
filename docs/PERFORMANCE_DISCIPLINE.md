# Performance Discipline

This project is sensitive to measurement quality. A noisy or mismatched run can
look like a serious regression when nothing meaningful changed.

Use these rules whenever you compare performance.

## 1. Compare Like With Like

Do not compare:

- Windows vs WSL
- one machine vs another machine
- different Java flags
- different warmup/measurement settings
- different handler modes

Keep the command line and environment the same unless the environment is the
thing you are intentionally measuring.

## 2. Keep One Variable Moving

If you are testing one feature, move one feature at a time:

- baseline
- baseline + feature A
- baseline + feature B
- baseline + feature C

Do not enable several optional layers at once and then guess which one moved
the result.

## 3. Treat Startup Cost Separately

Some features are session-time or startup-time concerns:

- protocol handshake
- schema rendering
- startup validation

Do not treat those as per-call steady-state cost unless the measurement really
includes them on every call.

## 4. Read More Than One Number

Always look at:

- `p50`
- `p90`
- `p99`
- `p99.9`
- achieved rate

Mean latency alone is not enough for synchronous RPC.

## 5. Repeat Runs

Prefer several clean runs over one dramatic run.

If one result is wildly different from the rest, treat it as suspicious until
it repeats.

## 6. Keep The Core Honest

When a feature is optional, evaluate two questions separately:

1. what does the default path cost now?
2. what does the optional feature cost when enabled?

That distinction matters more than a single blended number.

## 7. Suggested Comparison Pattern

For a normal transport change:

1. run the old baseline
2. run the new baseline
3. run the new baseline with the optional feature enabled
4. compare both latency percentiles and achieved rate

For startup-only features:

1. confirm hot-path numbers remain healthy
2. validate startup output, warnings, and reports separately

## 8. Useful Sanity Commands

Core correctness:

```bash
mvn -Dmaven.compiler.release=21 test
```

Benchmark packaging:

```bash
mvn -Pbenchmarks -DskipTests package
```

RTT benchmark examples live in [`BENCHMARKS.md`](BENCHMARKS.md).