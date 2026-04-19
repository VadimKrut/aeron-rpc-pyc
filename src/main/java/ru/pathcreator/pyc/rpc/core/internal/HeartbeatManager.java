package ru.pathcreator.pyc.rpc.core.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * Управляет heartbeat-состоянием RPC-канала.
 *
 * <p>Менеджер периодически отправляет heartbeat-кадры, принимает уведомления
 * о входящих heartbeat-кадрах и переводит канал между состояниями UP и DOWN.
 * При переходе в DOWN он вызывает callback отказа, а при восстановлении входящих
 * heartbeat-кадров вызывает callback подъема.</p>
 *
 * <p>Manages heartbeat state for an RPC channel. It periodically sends heartbeat
 * frames, tracks incoming heartbeat notifications, and switches the channel
 * between UP and DOWN states.</p>
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

    /**
     * Создает менеджер heartbeat-состояния.
     *
     * <p>Creates a heartbeat state manager.</p>
     *
     * @param name          имя канала для служебного потока / channel name for the worker thread
     * @param intervalNs    период отправки heartbeat в наносекундах / heartbeat interval in nanoseconds
     * @param missedLimit   допустимое число пропущенных heartbeat-интервалов / allowed missed heartbeat intervals
     * @param sendHeartbeat callback отправки heartbeat, получает текущий {@code nanoTime} /
     *                      heartbeat sender callback that receives current {@code nanoTime}
     * @param onDown        callback перехода канала в DOWN / callback invoked when the channel goes DOWN
     * @param onUp          callback перехода канала в UP / callback invoked when the channel goes UP
     */
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

    /**
     * Запускает фоновый heartbeat-поток.
     *
     * <p>Starts the background heartbeat thread.</p>
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        // Считаем канал DOWN пока не увидели первый heartbeat с той стороны.
        // lastReceivedNanos остаётся 0 — поэтому isConnected() вернёт false.
        thread.start();
    }

    /**
     * Останавливает heartbeat-поток и ожидает его завершения.
     *
     * <p>Stops the heartbeat thread and waits for it to finish.</p>
     */
    public void close() {
        running.set(false);
        LockSupport.unpark(thread);
        try {
            thread.join(1000);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Отмечает получение heartbeat-кадра от удаленной стороны.
     *
     * <p>Marks that a heartbeat frame has been received from the remote side.</p>
     */
    public void onHeartbeatReceived() {
        lastReceivedNanos = System.nanoTime();
        if (!connected) {
            connected = true;
            try {
                onUp.run();
            } catch (final Throwable t) { /* log-worthy */ }
        }
    }

    /**
     * Проверяет, считается ли удаленная сторона подключенной.
     *
     * <p>Checks whether the remote side is considered connected.</p>
     *
     * @return {@code true}, если heartbeat-состояние канала UP /
     * {@code true} if the heartbeat state is UP
     */
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