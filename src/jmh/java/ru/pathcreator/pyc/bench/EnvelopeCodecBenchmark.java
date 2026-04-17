package ru.pathcreator.pyc.bench;

import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import ru.pathcreator.pyc.envelope.Envelope;
import ru.pathcreator.pyc.envelope.EnvelopeCodec;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
        value = 1,
        jvmArgsAppend = "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
)
public class EnvelopeCodecBenchmark {

    @State(Scope.Thread)
    public static class EnvelopeState {
        UnsafeBuffer buffer;

        @Setup
        public void setup() {
            buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(Envelope.LENGTH));
            EnvelopeCodec.encode(
                    buffer,
                    0,
                    42,
                    123_456_789L,
                    Envelope.FLAG_IS_REQUEST,
                    128
            );
        }
    }

    @Benchmark
    public void encode(final EnvelopeState state) {
        EnvelopeCodec.encode(
                state.buffer,
                0,
                42,
                123_456_789L,
                Envelope.FLAG_IS_REQUEST,
                128
        );
    }

    @Benchmark
    public long decodeFields(final EnvelopeState state) {
        return EnvelopeCodec.magic(state.buffer, 0)
                + EnvelopeCodec.version(state.buffer, 0)
                + EnvelopeCodec.messageTypeId(state.buffer, 0)
                + EnvelopeCodec.correlationId(state.buffer, 0)
                + EnvelopeCodec.flags(state.buffer, 0)
                + EnvelopeCodec.payloadLength(state.buffer, 0);
    }
}
