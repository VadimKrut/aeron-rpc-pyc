package ru.pathcreator.pyc;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;

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
 */
public final class RpcNode implements AutoCloseable {

    private final Aeron aeron;
    private final MediaDriver mediaDriver;     // null, если внешний driver
    private final ExecutorService offloadExecutor;
    private final CopyOnWriteArrayList<RpcChannel> channels = new CopyOnWriteArrayList<>();

    private RpcNode(final MediaDriver driver, final Aeron aeron, final ExecutorService exec) {
        this.mediaDriver = driver;
        this.aeron = aeron;
        this.offloadExecutor = exec;
    }

    public static RpcNode start(final NodeConfig config) {
        ensureAeronDirParent(config.aeronDir());
        final MediaDriver driver;
        final String aeronDirName;
        if (config.embeddedDriver()) {
            final MediaDriver.Context driverCtx = new MediaDriver.Context()
                    .aeronDirectoryName(config.aeronDir())
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.DEDICATED)
                    .termBufferSparseFile(true)
                    .conductorIdleStrategy(new BackoffIdleStrategy(10, 20, 1, 1000))
                    .sharedIdleStrategy(new BackoffIdleStrategy(10, 20, 1, 1000))
                    .senderIdleStrategy(new BusySpinIdleStrategy())
                    .receiverIdleStrategy(new BusySpinIdleStrategy())
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
        return new RpcNode(driver, aeron, exec);
    }

    /**
     * Создаёт новый канал и возвращает его. Handler-ы регистрируются пользователем,
     * после чего вызывается channel.start().
     * <p>
     * Канал автоматически добавляется в список для close-в-каскаде.
     */
    public RpcChannel channel(final ChannelConfig channelConfig) {
        final RpcChannel ch = new RpcChannel(channelConfig, aeron, offloadExecutor);
        channels.add(ch);
        return ch;
    }

    @Override
    public void close() {
        for (final RpcChannel ch : channels) {
            try {
                ch.close();
            } catch (final Throwable t) { /* keep going */ }
        }
        channels.clear();
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