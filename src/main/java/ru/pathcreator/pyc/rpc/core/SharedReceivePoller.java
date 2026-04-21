package ru.pathcreator.pyc.rpc.core;

import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.util.EnumMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Общий node-level RX poller для {@link RpcChannel}.
 *
 * <p>Shared node-level receive poller for {@link RpcChannel} instances.</p>
 *
 * <p>Пуллер группирует каналы по {@link IdleStrategyKind}. У каждой группы есть
 * настраиваемое число lane-ов. Один lane — это долгоживущий поток, который
 * обходит назначенные ему каналы и вызывает {@link RpcChannel#pollRx(int)}.
 * Это позволяет сохранить изоляцию состояния канала, но не держать отдельный
 * RX-thread на каждый канал.</p>
 *
 * <p>The poller groups channels by {@link IdleStrategyKind}. Each group owns a
 * configurable number of polling lanes. A lane is a long-lived thread that
 * iterates over its assigned channels and calls {@link RpcChannel#pollRx(int)}.
 * This keeps channel state isolated while avoiding one dedicated RX thread per
 * channel.</p>
 *
 * <p>Ключевые свойства:</p>
 * <ul>
 *   <li>одна subscription одного канала никогда не poll-ится конкурентно из
 *       нескольких потоков</li>
 *   <li>канал назначается в наименее загруженный lane своей idle-группы</li>
 *   <li>пустые lane-ы паркуются вместо busy loop, чтобы не жечь CPU зря</li>
 * </ul>
 *
 * <p>Important properties:</p>
 * <ul>
 *   <li>a single channel subscription is never polled concurrently by multiple threads</li>
 *   <li>channels are assigned to the least-loaded lane inside their idle-strategy group</li>
 *   <li>empty lanes park instead of spinning, which avoids burning CPU when
 *       only a subset of lanes currently have channels</li>
 * </ul>
 */
final class SharedReceivePoller implements AutoCloseable {

    private final int lanesPerKind;
    private final int fragmentLimit;
    private final EnumMap<IdleStrategyKind, LaneGroup> groups = new EnumMap<>(IdleStrategyKind.class);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Создаёт общий RX poller.
     *
     * <p>Creates a shared receive poller.</p>
     *
     * @param lanesPerKind  число lane-ов на каждый {@link IdleStrategyKind} /
     *                      number of poller lanes per {@link IdleStrategyKind}
     * @param fragmentLimit лимит fragments на один poll pass lane-а /
     *                      fragment limit used by each lane on every poll pass
     */
    SharedReceivePoller(final int lanesPerKind, final int fragmentLimit) {
        if (lanesPerKind < 1) {
            throw new IllegalArgumentException("lanesPerKind >= 1");
        }
        if (fragmentLimit < 1) {
            throw new IllegalArgumentException("fragmentLimit >= 1");
        }
        this.lanesPerKind = lanesPerKind;
        this.fragmentLimit = fragmentLimit;
    }

    /**
     * Регистрирует канал в lane-группе, соответствующей его idle strategy.
     *
     * <p>Registers a channel into the lane group matching its idle strategy.</p>
     *
     * @param channel канал для регистрации / channel to register
     */
    void register(final RpcChannel channel) {
        if (closed.get()) {
            throw new IllegalStateException("receive poller is closed");
        }
        group(channel.rxIdleStrategyKind()).register(channel);
    }

    /**
     * Удаляет канал из общего poller-а, если он сейчас зарегистрирован.
     *
     * <p>Removes a channel from the shared poller if it is currently registered.</p>
     *
     * @param channel канал для удаления / channel to unregister
     */
    void unregister(final RpcChannel channel) {
        final LaneGroup group = groups.get(channel.rxIdleStrategyKind());
        if (group != null) {
            group.unregister(channel);
        }
    }

    private LaneGroup group(final IdleStrategyKind kind) {
        synchronized (groups) {
            LaneGroup group = groups.get(kind);
            if (group == null) {
                group = new LaneGroup(kind, lanesPerKind, fragmentLimit);
                groups.put(kind, group);
                group.start();
            }
            return group;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (groups) {
            for (final LaneGroup group : groups.values()) {
                group.close();
            }
            groups.clear();
        }
    }

    private static final class LaneGroup implements AutoCloseable {
        private final Lane[] lanes;
        private final AtomicInteger next = new AtomicInteger();

        private LaneGroup(final IdleStrategyKind kind, final int laneCount, final int fragmentLimit) {
            this.lanes = new Lane[laneCount];
            for (int i = 0; i < laneCount; i++) {
                lanes[i] = new Lane(kind, i, fragmentLimit);
            }
        }

        private void start() {
            for (final Lane lane : lanes) {
                lane.start();
            }
        }

        private void register(final RpcChannel channel) {
            lanes[leastLoadedLane()].register(channel);
            next.incrementAndGet();
        }

        private void unregister(final RpcChannel channel) {
            for (final Lane lane : lanes) {
                lane.unregister(channel);
            }
        }

        @Override
        public void close() {
            for (final Lane lane : lanes) {
                lane.close();
            }
        }

        /**
         * Выбирает наименее загруженный lane на данный момент.
         *
         * <p>Picks the currently least-loaded lane.</p>
         *
         * <p>{@code next} используется как стартовая позиция пробирования,
         * чтобы при равной нагрузке не выбирать всегда lane {@code 0}.</p>
         *
         * <p>The {@code next} cursor is used as the initial probe position so
         * equal-size cases do not always prefer lane {@code 0}.</p>
         *
         * @return индекс выбранного lane-а / index of the chosen lane
         */
        private int leastLoadedLane() {
            int bestIndex = Math.floorMod(next.get(), lanes.length);
            int bestSize = lanes[bestIndex].size();
            for (int i = 0; i < lanes.length; i++) {
                final int size = lanes[i].size();
                if (size < bestSize) {
                    bestIndex = i;
                    bestSize = size;
                }
            }
            return bestIndex;
        }
    }

    private static final class Lane implements AutoCloseable {
        private final CopyOnWriteArrayList<RpcChannel> channels = new CopyOnWriteArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final int fragmentLimit;
        private final IdleStrategy idle;
        private final Thread thread;

        private Lane(final IdleStrategyKind kind, final int index, final int fragmentLimit) {
            this.fragmentLimit = fragmentLimit;
            this.idle = createIdleStrategy(kind);
            this.thread = new Thread(this::loop, "rpc-rx-shared-" + kind.name().toLowerCase() + "-" + index);
            this.thread.setDaemon(false);
        }

        private void start() {
            thread.start();
        }

        private void register(final RpcChannel channel) {
            channels.addIfAbsent(channel);
            LockSupport.unpark(thread);
        }

        private void unregister(final RpcChannel channel) {
            channels.remove(channel);
        }

        private int size() {
            return channels.size();
        }

        /**
         * Главный polling loop одного общего lane-а.
         *
         * <p>Main polling loop for one shared lane.</p>
         */
        private void loop() {
            while (running.get()) {
                if (channels.isEmpty()) {
                    idle.reset();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    continue;
                }
                int fragments = 0;
                for (final RpcChannel channel : channels) {
                    fragments += channel.pollRx(fragmentLimit);
                }
                idle.idle(fragments);
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                thread.join(2000);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private static IdleStrategy createIdleStrategy(final IdleStrategyKind kind) {
            return switch (kind) {
                case BUSY_SPIN -> new BusySpinIdleStrategy();
                case BACKOFF -> new BackoffIdleStrategy(
                        100, 10,
                        TimeUnit.NANOSECONDS.toNanos(1),
                        TimeUnit.MILLISECONDS.toNanos(1));
                default -> new YieldingIdleStrategy();
            };
        }
    }
}