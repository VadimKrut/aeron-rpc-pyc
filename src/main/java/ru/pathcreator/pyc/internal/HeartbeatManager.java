package ru.pathcreator.pyc.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * Поддержка heartbeat-состояния канала.
 * <p>
 * Обязанности:
 * 1. Периодически (heartbeatIntervalNs) отправлять heartbeat-envelope через
 * переданный LongConsumer (callback — "отправь heartbeat сейчас").
 * 2. Принимать notification о получении heartbeat (onHeartbeatReceived())
 * — обновляет lastReceivedNanos.
 * 3. Детектить DOWN: если (now - lastReceivedNanos) &gt; missedLimit * interval
 * — вызвать downCallback ровно один раз до следующего UP.
 * 4. Детектить UP: если был DOWN и вдруг снова пришёл heartbeat — вызвать
 * upCallback.
 * <p>
 * Используется RpcChannel-ом для failfast pending calls и для блокировки
 * новых call-ов когда канал DOWN.
 */
public final class HeartbeatManager {

    private final Runnable onUp;
    private final Runnable onDown;
    private final long intervalNs;
    private final int missedLimit;
    private final LongConsumer sendHeartbeat;   // arg = nanoTime тика; реализация шлёт envelope

    private final Thread thread;
    private volatile boolean connected = false;
    private volatile long lastReceivedNanos = 0L;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HeartbeatManager(
            final String name,
            final long intervalNs,
            final int missedLimit,
            final LongConsumer sendHeartbeat,
            final Runnable onDown,
            final Runnable onUp
    ) {
        this.intervalNs = intervalNs;
        this.missedLimit = missedLimit;
        this.sendHeartbeat = sendHeartbeat;
        this.onDown = onDown;
        this.onUp = onUp;
        this.thread = new Thread(this::loop, "rpc-heartbeat-" + name);
        this.thread.setDaemon(true);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        // Считаем канал DOWN пока не увидели первый heartbeat с той стороны.
        // lastReceivedNanos остаётся 0 — поэтому isConnected() вернёт false.
        thread.start();
    }

    public void close() {
        running.set(false);
        LockSupport.unpark(thread);
        try {
            thread.join(1000);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public void onHeartbeatReceived() {
        lastReceivedNanos = System.nanoTime();
        if (!connected) {
            connected = true;
            try {
                onUp.run();
            } catch (final Throwable t) { /* log-worthy */ }
        }
    }

    public boolean isConnected() {
        return connected;
    }

    private void loop() {
        while (running.get()) {
            final long now = System.nanoTime();

            // 1. Отправляем свой heartbeat (всегда, независимо от состояния).
            try {
                sendHeartbeat.accept(now);
            } catch (final Throwable t) {
                // send может бросить NotConnected / timeout — игнорируем,
                // логика DOWN всё равно сработает по отсутствию входящих.
            }

            // 2. Проверяем, приходят ли heartbeat-ы с той стороны.
            final long last = lastReceivedNanos;
            if (last == 0L) {
                // Никогда не видели — ждём, не триггерим onDown (ещё не поднялись).
            } else {
                final long age = now - last;
                final long threshold = intervalNs * missedLimit;
                if (age > threshold && connected) {
                    connected = false;
                    try {
                        onDown.run();
                    } catch (final Throwable t) { /* log-worthy */ }
                }
            }
            LockSupport.parkNanos(intervalNs);
        }
    }
}