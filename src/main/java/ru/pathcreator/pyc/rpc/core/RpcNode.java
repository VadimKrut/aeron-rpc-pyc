package ru.pathcreator.pyc.rpc.core;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.BackoffIdleStrategy;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Корневой объект RPC-слоя на один процесс.
 * <p>
 * Держит:
 * - один embedded MediaDriver (если NodeConfig.embeddedDriver == true),
 * - одного Aeron-клиента,
 * - один virtual-thread Executor для OFFLOAD-handler-ов (шарится между каналами),
 * - список открытых каналов (для централизованного close()).
 * <p>
 * Использование:
 * <p>
 * RpcNode node = RpcNode.start(NodeConfig.builder().aeronDir("...").build());
 * <p>
 * RpcChannel ch = node.channel(ChannelConfig.builder()
 * .localEndpoint("localhost:40101")
 * .remoteEndpoint("localhost:40102")
 * .streamId(1001)
 * .build());
 * <p>
 * // регистрируем handlers ...
 * ch.start();
 * <p>
 * // параллельные клиентские вызовы с виртуалок ...
 * <p>
 * node.close();   // закроет все каналы, клиент, driver, executor.
 *
 * <p>Root RPC object for one process. It owns the Aeron client, optionally an
 * embedded MediaDriver, the shared offload executor, and all channels created
 * through this node.</p>
 */
public final class RpcNode implements AutoCloseable {

    private final Aeron aeron;
    private final MediaDriver mediaDriver;     // null, если внешний driver
    private final ExecutorService offloadExecutor;
    private final SharedReceivePoller receivePoller;
    private final CopyOnWriteArrayList<RpcChannel> channels = new CopyOnWriteArrayList<>();

    private RpcNode(
            final MediaDriver driver,
            final Aeron aeron,
            final ExecutorService exec,
            final SharedReceivePoller receivePoller
    ) {
        this.mediaDriver = driver;
        this.aeron = aeron;
        this.offloadExecutor = exec;
        this.receivePoller = receivePoller;
    }

    /**
     * Запускает RPC-узел с заданной конфигурацией.
     *
     * <p>Starts an RPC node using the provided configuration.</p>
     *
     * @param config конфигурация узла / node configuration
     * @return запущенный RPC-узел / started RPC node
     */
    public static RpcNode start(final NodeConfig config) {
        ensureAeronDirParent(config.aeronDir());
        final MediaDriver driver;
        final String aeronDirName;
        if (config.embeddedDriver()) {
            // ThreadingMode.SHARED: все компоненты MediaDriver-а (sender,
            // receiver, conductor) в ОДНОМ треде. Против DEDICATED экономим
            // 2 ядра CPU на процесс. Latency чуть выше (один тред обрабатывает
            // и send и receive), но для RPC с локального хоста разница в
            // единицах микросекунд. В реальной сетке это всё равно перекроет
            // сам network round-trip.
            //
            // Если нужен абсолютный минимум latency — поменяйте на DEDICATED.
            final MediaDriver.Context driverCtx = new MediaDriver.Context()
                    .aeronDirectoryName(config.aeronDir())
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.SHARED)
                    .termBufferSparseFile(true)
                    // sharedIdleStrategy используется в SHARED mode.
                    // BackoffIdleStrategy с min=1ns, max=1ms — стандарт Aeron.
                    .sharedIdleStrategy(new BackoffIdleStrategy(
                            100, 10,
                            1L,
                            1_000_000L))
                    .useWindowsHighResTimer(true)
                    .warnIfDirectoryExists(true);
            driver = MediaDriver.launchEmbedded(driverCtx);
            aeronDirName = driver.aeronDirectoryName();
        } else {
            driver = null;
            aeronDirName = config.aeronDir();
        }
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDirName));
        // Общий executor виртуальных потоков для всех OFFLOAD-handler-ов всех каналов.
        // Шарится намеренно: уменьшает число carrier-ов и упрощает lifecycle.
        final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        final SharedReceivePoller receivePoller = config.sharedReceivePoller()
                ? new SharedReceivePoller(config.sharedReceivePollerThreads(), config.sharedReceivePollerFragmentLimit())
                : null;
        return new RpcNode(driver, aeron, exec, receivePoller);
    }

    /**
     * Создаёт новый канал и возвращает его. Handler-ы регистрируются пользователем,
     * после чего вызывается channel.start().
     * <p>
     * Канал автоматически добавляется в список для close-в-каскаде.
     *
     * <p>Creates a new channel and adds it to the node-owned channel list so it
     * can be closed together with the node.</p>
     *
     * @param channelConfig конфигурация канала / channel configuration
     * @return новый RPC-канал / new RPC channel
     */
    public RpcChannel channel(final ChannelConfig channelConfig) {
        final RpcChannel ch = new RpcChannel(channelConfig, aeron, offloadExecutor, receivePoller);
        channels.add(ch);
        return ch;
    }

    /**
     * Закрывает все каналы, Aeron-клиент, MediaDriver и общий executor.
     *
     * <p>Closes all channels, the Aeron client, MediaDriver, and shared executor.</p>
     */
    @Override
    public void close() {
        for (final RpcChannel ch : channels) {
            try {
                ch.close();
            } catch (final Throwable t) { /* keep going */ }
        }
        channels.clear();
        if (receivePoller != null) {
            receivePoller.close();
        }
        offloadExecutor.shutdown();
        CloseHelper.closeAll(aeron, mediaDriver);
    }

    private static void ensureAeronDirParent(final String aeronDir) {
        final File dir = new File(aeronDir).getAbsoluteFile();
        final File parent = dir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create parent dir for Aeron dir: " + parent);
        }
    }
}