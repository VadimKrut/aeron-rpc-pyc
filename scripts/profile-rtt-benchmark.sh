#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ASPROF="${ASPROF:-$HOME/.local/opt/async-profiler/bin/asprof}"
JAVA_BIN="${JAVA_BIN:-java}"
JCMD_BIN="${JCMD_BIN:-jcmd}"
MVN_BIN="${MVN_BIN:-mvn}"

JAR_PATH="${JAR_PATH:-$REPO_ROOT/target/rpc-core-benchmarks.jar}"
MAIN_CLASS="ru.pathcreator.pyc.rpc.core.bench.RpcLatencyHistogramMain"

PROFILE_SECONDS="${PROFILE_SECONDS:-20}"
ATTACH_DELAY_SECONDS="${ATTACH_DELAY_SECONDS:-8}"
MODE="${MODE:-all}"
RUN_LABEL="${RUN_LABEL:-default}"

DEFAULT_BENCH_ARGS=(
  --payload=32
  --rate=150000
  --threads=8
  --channels=8
  --rx-poller-threads=2
  --burst-size=1
  --warmup-iterations=8
  --warmup-messages=50000
  --measurement-iterations=20
  --measurement-messages=200000
  --handler=OFFLOAD
  --idle=YIELDING
)

if [[ -n "${BENCH_ARGS:-}" ]]; then
  # shellcheck disable=SC2206
  BENCH_ARGS_ARRAY=(${BENCH_ARGS})
else
  BENCH_ARGS_ARRAY=("${DEFAULT_BENCH_ARGS[@]}")
fi

require_bin() {
  local bin="$1"
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "Missing required binary: $bin" >&2
    exit 1
  fi
}

build_benchmarks_if_needed() {
  if [[ ! -f "$JAR_PATH" ]]; then
    echo "Benchmark jar not found, building it..."
    (cd "$REPO_ROOT" && "$MVN_BIN" -Pbenchmarks -DskipTests package)
  fi
}

prepare_output_dir() {
  local ts
  ts="$(date +%Y%m%d-%H%M%S)"
  OUTPUT_DIR="$REPO_ROOT/profiles/${ts}-${RUN_LABEL}"
  mkdir -p "$OUTPUT_DIR"
}

start_benchmark() {
  local mode_name="$1"
  local bench_log="$OUTPUT_DIR/${mode_name}.benchmark.log"

  (
    cd "$REPO_ROOT"
    exec "$JAVA_BIN" \
      --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
      -cp "$JAR_PATH" \
      "$MAIN_CLASS" \
      "${BENCH_ARGS_ARRAY[@]}"
  ) >"$bench_log" 2>&1 &

  BENCH_PID=$!
  echo "$BENCH_PID" > "$OUTPUT_DIR/${mode_name}.pid"
}

wait_for_process() {
  local pid="$1"
  for _ in $(seq 1 50); do
    if kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

stop_benchmark_if_running() {
  if [[ -n "${BENCH_PID:-}" ]] && kill -0 "$BENCH_PID" 2>/dev/null; then
    kill "$BENCH_PID" 2>/dev/null || true
    wait "$BENCH_PID" 2>/dev/null || true
  fi
}

profile_jfr() {
  local name="$1"
  local file="$OUTPUT_DIR/${name}.jfr"
  "$JCMD_BIN" "$BENCH_PID" JFR.start \
    name="$name" \
    settings=profile \
    filename="$file" \
    duration="${PROFILE_SECONDS}s" >/dev/null
}

profile_async() {
  local event="$1"
  local name="$2"
  local file="$OUTPUT_DIR/${name}.html"
  "$ASPROF" -e "$event" -d "$PROFILE_SECONDS" -f "$file" "$BENCH_PID"
}

run_single_mode() {
  local mode_name="$1"

  echo
  echo "=== Running mode: $mode_name ==="
  start_benchmark "$mode_name"

  if ! wait_for_process "$BENCH_PID"; then
    echo "Benchmark process failed to start for mode $mode_name" >&2
    return 1
  fi

  sleep "$ATTACH_DELAY_SECONDS"

  case "$mode_name" in
    jfr)
      profile_jfr "$mode_name"
      ;;
    wall|cpu|lock|alloc)
      profile_async "$mode_name" "$mode_name"
      ;;
    *)
      echo "Unknown mode: $mode_name" >&2
      stop_benchmark_if_running
      return 1
      ;;
  esac

  wait "$BENCH_PID" || true
  BENCH_PID=""
}

main() {
  require_bin "$JAVA_BIN"
  require_bin "$JCMD_BIN"
  require_bin "$MVN_BIN"
  build_benchmarks_if_needed
  prepare_output_dir

  if [[ "$MODE" != "jfr" && "$MODE" != "wall" && "$MODE" != "cpu" && "$MODE" != "lock" && "$MODE" != "alloc" && "$MODE" != "all" ]]; then
    echo "Unsupported MODE=$MODE" >&2
    exit 1
  fi

  if [[ "$MODE" == "all" ]]; then
    require_bin "$ASPROF"
    run_single_mode jfr
    run_single_mode wall
    run_single_mode cpu
    run_single_mode lock
    run_single_mode alloc
  elif [[ "$MODE" == "jfr" ]]; then
    run_single_mode jfr
  else
    require_bin "$ASPROF"
    run_single_mode "$MODE"
  fi

  {
    echo "output_dir=$OUTPUT_DIR"
    echo "mode=$MODE"
    echo "profile_seconds=$PROFILE_SECONDS"
    echo "attach_delay_seconds=$ATTACH_DELAY_SECONDS"
    echo "bench_args=${BENCH_ARGS_ARRAY[*]}"
  } > "$OUTPUT_DIR/run.meta"

  echo
  echo "Profiling complete. Results: $OUTPUT_DIR"
}

trap stop_benchmark_if_running EXIT

main "$@"