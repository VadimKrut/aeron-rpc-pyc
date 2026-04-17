package ru.pathcreator.pyc;

/**
 * Режим выполнения handler-а.
 * <p>
 * INLINE — выполняется прямо на rx-треде:
 * - максимально быстро, нет копирования payload-а;
 * - но блокирует приём на время работы handler-а;
 * - использовать только для ОЧЕНЬ быстрых handler-ов (обычно &lt; 10 µs).
 * <p>
 * OFFLOAD — ядро копирует payload и сабмитит handler в virtual thread executor:
 * - стоит одну копию payload-а (из пула буферов, не аллокация на каждый вызов);
 * - rx-тред сразу возвращается к polling, не блокирует приём;
 * - правильный выбор для любой бизнес-логики (DB-запросы, http-вызовы, CPU compute).
 */
public enum HandlerMode {
    INLINE,
    OFFLOAD
}