# aeron-rpc

Двунаправленный синхронный RPC поверх Aeron UDP. Ядро не знает о конкретном
сериализаторе (SBE/Kryo/Protobuf/plain bytes — всё через `MessageCodec<T>`).

Цели, под которые оно спроектировано:

- **как HTTP, но быстро**: привычный синхронный API с timeout-ом;
- **low-latency friendly**: zero-alloc hot path (pooled frames, pooled tasks,
  primitive maps, direct buffers);
- **расширяемо**: добавление нового метода/флоу не требует правок в ядре;
- **JIT-friendly**: `final` классы, no lambdas captured per-request, stable
  call sites.

## Содержимое

```
rpc-core/src/main/java/demo/rpc/
│
├── RpcNode                  корневой объект — один MediaDriver + N каналов
├── RpcChannel               один канал = pub + sub + handlers + heartbeat + TX/RX треды
├── NodeConfig
├── ChannelConfig
├── HandlerMode              INLINE | OFFLOAD
├── BackpressurePolicy       BLOCK | FAIL_FAST | DROP_NEW | DROP_OLDEST
├── RequestHandler<Req,Resp> high-level серверный handler (возвращает response-POJO)
├── RawRequestHandler        low-level zero-alloc серверный handler (байты + буфер)
├── LargePayloadRpcChannel   стаб-namespace для будущих >16MiB payload
│
├── codec/
│   └── MessageCodec<T>      контракт сериализации пользователя
│
├── envelope/
│   ├── Envelope             формат транспортного заголовка ядра (24 байта)
│   └── EnvelopeCodec        zero-alloc read/write
│
├── exceptions/
│   ├── RpcException
│   ├── RpcTimeoutException
│   ├── NotConnectedException
│   ├── PayloadTooLargeException
│   └── BackpressureException
│
└── internal/
    ├── CorrelationIdGenerator
    ├── HeartbeatManager
    ├── OffloadTask          pooled Runnable для OFFLOAD-хендлеров
    ├── PendingCall          volatile-based слот ожидания ответа
    ├── PendingCallPool      lock-free пул
    ├── PendingCallRegistry  correlationId -> PendingCall (Agrona Long2ObjectHashMap)
    ├── SenderQueue          выделенный TX-тред + MPSC очередь (lock-free)
    ├── SyncWaiter           3-фазный backoff: spin → yield → parkNanos
    └── TxFrame              pooled direct-буфер для исходящего сообщения
```

## Как это работает

### Основная картина

```
 caller threads (virtual or platform)
      │    
      │   call(req, codec, respCodec)
      ▼
 ┌─────────────────────────────────────┐
 │ 1. acquire PendingCall (pool)       │
 │ 2. register correlationId           │
 │ 3. acquire TxFrame (pool)           │
 │ 4. encode envelope+payload in-place │
 │ 5. submit(frame, policy)            │
 │ 6. SyncWaiter.await() (3-phase)     │
 └───────────────┬─────────────────────┘
                 │
                 ▼
     lock-free MPSC (ManyToOneConcurrentArrayQueue)
                 │
                 ▼
 ┌─────────────────────────────────────┐
 │   single sender thread              │
 │   publication.offer() loop          │
 │   BackoffIdleStrategy               │
 │   errors -> fail pending calls      │
 └───────────────┬─────────────────────┘
                 │
                 ▼   (Aeron UDP)
                 │
 ┌─────────────────────────────────────┐
 │   single RX thread                  │
 │   subscription.poll() + assembler   │
 │   dispatch by messageTypeId         │
 │                                     │
 │   response -> PendingCall.complete  │
 │   request INLINE -> handler here    │
 │   request OFFLOAD -> copy + submit  │
 │                     to executor     │
 └─────────────────────────────────────┘
```

Ключевое отличие от «обернуть offer в lock»: один sender-тред — единственный
продюсер публикации, **никакой конкуренции** за Publication. Caller-ы только
пишут в MPSC-очередь; это одна CAS-операция.

### Envelope (транспортный заголовок ядра, 24 байта, LE)

```
 offset  size  field
 [0..1]   2    magic          = 0xAE01
 [2..3]   2    version        = 1
 [4..7]   4    messageTypeId  int32 (user-defined)
 [8..15]  8    correlationId  int64 (транспортный)
 [16..19] 4    flags          bit0=isRequest, bit1=isHeartbeat
 [20..23] 4    payloadLength  int32
 [24..]         user payload   (SBE/Kryo/proto/whatever — ядро не смотрит)
```

Envelope — единственный кусок байт, который интерпретирует ядро. Всё что
после — сквозь, через ваш `MessageCodec`.

### Handler modes

Регистрируются явно на каждый handler.

- **INLINE** — handler исполняется прямо в RX-треде. Zero-copy, быстро, но
  блокирует приём на время выполнения. Годится для handler-ов &lt; 10 µs
  (pure CPU, без IO).
- **OFFLOAD** — ядро копирует payload в буфер из пула, берёт pooled
  `OffloadTask`, сабмитит в executor. Ровно одна копия payload-а;
  RX-тред не блокируется. Рекомендуется для любой бизнес-логики с IO
  или с неопределённой latency.

Default executor для OFFLOAD — virtual threads
(`Executors.newVirtualThreadPerTaskExecutor()`). Если ваши OFFLOAD-handler-ы
делают **только CPU** и не блокируются на IO — передайте в `ChannelConfig`
свой `ExecutorService` с fixed-size platform pool (виртуалки бесполезны
там, где нет блокировок; platform thread с pinned carrier-ом чуть быстрее).

### Sender paths: tryClaim + opportunistic batching

Sender-тред выбирает путь отправки автоматически для каждого сообщения:

| Условие                                                                      | Путь               | Что происходит                                               |
|------------------------------------------------------------------------------|--------------------|--------------------------------------------------------------|
| Один frame ≤ `maxPayloadLength`, batching OFF                                | `tryClaim` single  | один wire-фрагмент, одна copy frame→log-buffer               |
| Один frame > `maxPayloadLength`                                              | `offer()` fallback | Aeron сам фрагментирует в несколько wire-фреймов             |
| Несколько frames в очереди, каждый и сумма ≤ `maxPayloadLength`, batching ON | `tryClaim` batch   | склеиваются в один wire-фрагмент; receiver разбирает в цикле |

**`tryClaim` vs `offer()`**: `tryClaim` сразу пишет в log buffer Aeron-а
(одна copy sender-ом), `offer()` копирует через внутренний буфер Aeron-а
и может фрагментировать. Поэтому мы всегда предпочитаем `tryClaim`,
когда размер позволяет.

**Opportunistic batching** — "берём всё, что уже готово":

- sender polls первый frame (`lead`);
- polls следующие только если они ЕСТЬ прямо сейчас и суммарно влезают
  в один `tryClaim` slot;
- НЕ ждёт пока накопится batch → нулевая добавочная latency;
- выигрыш появляется только под burst-нагрузкой, когда несколько callers
  уже положили сообщения в очередь.

Если ваш профиль — очень редкие RPC с ультра-low latency важнее чем
throughput — выключите batching:

```java
ChannelConfig.builder()
    .

wireBatchingEnabled(false)
// ...
```

Это уберёт один лишний `peek-poll` цикл в sender-треде (~десятки наносекунд).
Для обычного RPC под нагрузкой оставьте включённым.

### Backpressure policies

Применяются, когда sender queue заполнена (caller быстрее чем remote успевает
принять, или чем ваша Aeron publication успевает слать).

| Policy            | Поведение                                                                                  | Когда использовать                                   |
|-------------------|--------------------------------------------------------------------------------------------|------------------------------------------------------|
| `BLOCK` (default) | caller паркуется до появления места; по истечении `offerTimeout` — `BackpressureException` | обычный RPC: "запрос подождёт, как в HTTP"           |
| `FAIL_FAST`       | сразу `BackpressureException` без ожидания                                                 | low-latency системы; retry/fallback стратегии поверх |
| `DROP_NEW`        | новый запрос тихо отброшен (caller получает fail)                                          | fire-and-forget телеметрия (НЕ для RPC)              |
| `DROP_OLDEST`     | вытесняется самый старый pending (тот caller получает fail), новый занимает место          | live-данные где свежесть важнее порядка (НЕ для RPC) |

**Для RPC (request/response) практически используются только `BLOCK` и
`FAIL_FAST`.** Остальные два есть для полноты и для one-way use-case-ов.

Ответы на запросы (server → client) всегда отправляются с policy=`BLOCK`
независимо от настройки: потерять ответ, которого ждёт caller — грубая ошибка.

Heartbeat отправляется с policy=`DROP_NEW`: пропустить один тик нормально,
следующий перекроет.

### Heartbeat + failfast

- Каждые `heartbeatInterval` (default 1s) шлётся служебный envelope с
  флагом `HEARTBEAT`.
- На приёме обновляется `lastReceivedNanos`.
- Если `(now - lastReceivedNanos) > heartbeatMissedLimit * interval`
  (default 3s) → канал DOWN:
    - все pending calls получают `completeFail(...)` → просыпаются с
      `RpcException`;
    - новые `call(...)` бросают `NotConnectedException` без ожидания timeout.
- Когда heartbeat снова приходит — канал UP, работа возобновляется.

Reconnect-а нет: если Aeron publication потеряла session (например remote
рестартанул), новых ответов не будет, но failfast по heartbeat спасёт
от зависания.

### 16 MiB hard cap

`ChannelConfig.maxMessageSize` = 16 MiB по умолчанию и жёстко валидируется.
Превышение → `PayloadTooLargeException`. Под эту границу спроектированы:
размер `TxFrame` buffer, `offloadCopyBufferSize`, сам стиль
«single-envelope-per-fragment».

Для больших payload-ов зарезервирован namespace `LargePayloadRpcChannel`
(стаб). Для него нужна **другая** логика: chunk-based передача,
flow-control на уровне чанка, отдельный пул больших буферов. Смешивать
с текущим `RpcChannel` нельзя без ухудшения small-message path.

## Использование

### Минимальный скелет

```java
// 1) Нода (один MediaDriver на процесс; несколько каналов под ним).
RpcNode node = RpcNode.start(
                NodeConfig.builder()
                        .aeronDir("/var/aeron/myapp")
                        .build()
        );

// 2) Канал для конкретной связки peer-ов.
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("0.0.0.0:40101")       // где слушаем
                .remoteEndpoint("peer-host:40102")    // куда шлём
                .streamId(1001)
                .defaultTimeout(Duration.ofSeconds(2))
                .heartbeatInterval(Duration.ofSeconds(1))
                .backpressurePolicy(BackpressurePolicy.BLOCK)
                .senderQueueCapacity(4096)            // power of two
                .build()
);

// 3) Серверные handlers (все до start()).
channel.

onRequest(
        MY_REQUEST_ID,
        MY_RESPONSE_ID,
    new MyRequestCodec(),
    new

MyResponseCodec(),

HandlerMode.OFFLOAD,                       // IO внутри? OFFLOAD.
        (
MyRequest req)->new

MyResponse(req.id, OK)
);

// Или raw, когда критична каждая наносекунда и аллокации:
        channel.

onRaw(
        MY_FAST_REQUEST_ID,
        MY_FAST_RESPONSE_ID,
        HandlerMode.INLINE,                        // hot path
    (reqBuf, reqOff, reqLen, respBuf, respOff, respCap) ->{
        // свой декодер flyweight-ом, своя логика, своя запись в respBuf.
        respBuf.

putLong(respOff, value);
        return 8;                               // вернули сколько записали
                }
                );

// 4) Старт.
                channel.

start();

// 5) Клиентские вызовы (из любых потоков; виртуалки приветствуются).
try(
var exec = Executors.newVirtualThreadPerTaskExecutor()){
        for(
int i = 0;
i< 1000;i++){
final long id = i;
        exec.

submit(() ->{
MyResponse resp = channel.call(
        MY_REQUEST_ID, MY_RESPONSE_ID,
        new MyRequest(id),
        new MyRequestCodec(),
        new MyResponseCodec()
);
            System.out.

println(resp);
        });
                }
                }

// Override timeout и policy на конкретный вызов:
                channel.

call(...,500_000_000L /* ns */,BackpressurePolicy.FAIL_FAST);

// 6) Останов (cascade).
node.

close();
```

### Писать свой MessageCodec

Интерфейс:

```java
public interface MessageCodec<T> {
    int encode(T message, MutableDirectBuffer buffer, int offset);

    T decode(DirectBuffer buffer, int offset, int length);
}
```

Минимальный пример (без SBE, руками):

```java
public final class MyRequestCodec implements MessageCodec<MyRequest> {
    @Override
    public int encode(final MyRequest m, final MutableDirectBuffer buf, final int offset) {
        buf.putLong(offset, m.id, ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(offset + 8, m.amount, ByteOrder.LITTLE_ENDIAN);
        return 16;
    }

    @Override
    public MyRequest decode(final DirectBuffer buf, final int offset, final int length) {
        final long id = buf.getLong(offset, ByteOrder.LITTLE_ENDIAN);
        final double amount = buf.getDouble(offset + 8, ByteOrder.LITTLE_ENDIAN);
        return new MyRequest(id, amount);
    }
}
```

Если вы хотите zero-alloc decode — реализуйте flyweight: ваш POJO хранит
ссылку на buffer/offset/length, а геттеры читают его лениво. Такой codec
не создаёт объекта-обёртки заново. Но для этого удобнее **`RawRequestHandler`**
(см. ниже) — там вообще нет ни encode, ни decode, только байты.

### `RawRequestHandler` — zero-alloc server path

Когда high-level handler (с decode-to-POJO + encode-POJO) слишком тяжёл:

```java
channel.onRaw(
        MY_REQ_ID, MY_RESP_ID, HandlerMode.INLINE,
    (reqBuf, reqOff, reqLen, respBuf, respOff, respCap) ->{
// raw read из reqBuf (может быть SBE flyweight decoder или что угодно)
final long id = reqBuf.getLong(reqOff, ByteOrder.LITTLE_ENDIAN);
// raw write в respBuf, начиная с respOff
        respBuf.

putLong(respOff, id, ByteOrder.LITTLE_ENDIAN);
        respBuf.

putInt(respOff +8, /*status*/ 0,ByteOrder.LITTLE_ENDIAN);
        return 12;  // байт записано
                }
                );
```

- Возврат `<= 0` → one-way (ответа не будет).
- Возврат `> respCap` → `PayloadTooLargeException`.
- В INLINE `reqBuf` валиден до выхода из handler-а; в OFFLOAD — всё время
  жизни вызова (это копия из пула).

## Добавление нового флоу (новый "RPC-метод")

Допустим, хотим новый метод `Foo`: Service A → `FooRequest` → Service B;
Service B → `FooResponse` → Service A.

1. Выберите `int` константы для `messageTypeId`: например
   `FOO_REQUEST=5`, `FOO_RESPONSE=6`. Положите их в общий модуль
   сообщений, доступный обеим сторонам.
2. Опишите POJO `FooRequest`, `FooResponse` (или сразу работайте raw).
3. Напишите `MessageCodec<FooRequest>` и `MessageCodec<FooResponse>`
   (или только `RawRequestHandler`).
4. На сервере: `channel.onRequest(FOO_REQUEST, FOO_RESPONSE, ...)` или
   `channel.onRaw(...)`.
5. На клиенте: `channel.call(FOO_REQUEST, FOO_RESPONSE, req, reqCodec, respCodec)`.

Ни `streamId`, ни порты, ни прочие настройки ядра трогать не надо.
Один `RpcChannel` переносит любое количество типов сообщений — они
разделяются по `messageTypeId`.

## Смена сериализатора

Просто замените реализации `MessageCodec<T>`. Ядро не знает ни SBE,
ни Kryo, ни protobuf — нет ни одной ссылки на них в `rpc-core`.

## Несколько каналов

```java
RpcChannel trading = node.channel(cfg1.streamId(1001).remoteEndpoint("t:40002").build());
RpcChannel telemetry = node.channel(cfg2.streamId(1002).remoteEndpoint("t:40002")
        .backpressurePolicy(BackpressurePolicy.DROP_NEW).build());
RpcChannel admin = node.channel(cfg3.streamId(1003).remoteEndpoint("t:40003").build());
```

У каждого канала свой sender-тред, свой rx-тред, свой heartbeat, свои
pending/registry, свой `BackpressurePolicy`. Трединг-модель простая: N
каналов → 2N dedicated threads + shared offload executor от node-а.

## Производительность: что уже сделано

- **Один sender-тред на канал** (single producer → `publication.offer`)
  вместо lock-contention от N caller-ов.
- **Lock-free MPSC-очередь** (Agrona `ManyToOneConcurrentArrayQueue`) между
  caller-ами и sender-ом.
- **Все пулы lock-free** (`ManyToManyConcurrentArrayQueue`): `TxFrame.Pool`,
  `PendingCallPool`, `OffloadTask.Pool`, `offloadCopyPool`.
- **Pooled `OffloadTask`** вместо per-request lambda — нет synthetic inner
  class с captured vars.
- **Primitive maps** (Agrona `Long2ObjectHashMap`, `Int2ObjectHashMap`) —
  нет боксинга long/int.
- **Envelope читается руками** (getShort/getInt/getLong) без временных
  объектов.
- **`PendingCall` хранит результат как байты** в direct-буфере — decode
  делает caller на своём потоке через свой codec. Никакого типа в ядре.
- **3-фазный backoff** в `SyncWaiter` (`onSpinWait` → `yield` → `parkNanos`
  экспоненциально до 100 µs).
- **`ReentrantLock`** вместо `synchronized` везде где может ждать виртуалка.
- **`final`** на всех классах и полях где нет наследования — inline-friendly.
- **`tryClaim` fast-path** для одиночных сообщений ≤ `publication.maxPayloadLength`.
  Sender пишет в log-buffer через `BufferClaim` и делает `commit()` — это
  гарантированно один wire-фрагмент без промежуточной буферизации Aeron-а.
  Fallback на `offer()` только для сообщений > `maxPayloadLength` (там
  Aeron сам фрагментирует).
- **Opportunistic wire-batching**: sender склеивает уже готовые в очереди
  сообщения в один `tryClaim` slot. Никакого linger-time — если в очереди
  одно сообщение, отправляется одно; если накопился burst — пакуется
  до `maxBatchMessages` (default 16) или до `maxPayloadLength`.
  Receiver в dispatch-цикле разбирает N envelope-ов из одного фрагмента.
  Включено по умолчанию, отключается через
  `ChannelConfig.wireBatchingEnabled(false)` если для latency важнее
  отсутствие добавочной copy-pass в sender.

## Производительность: что ещё можно

- **Zero-copy callback API** (`callZeroCopy(buf -> writeInto)`). Сейчас
  caller encode-ит в pooled `TxFrame`, потом sender делает copy в log
  buffer через `tryClaim`. Итого одна копия. Убрать эту копию можно
  только если caller пишет прямо в `tryClaim` slot, но тогда он становится
  producer-ом Publication — теряется смысл sender-треда. Компромисс:
  отдать caller-у "batch-aware" callback, который пишет в slot только
  когда sender его призвал. Сложнее API, выигрыш — одна копия на сообщение.
- **Affinity pinning** sender-тредов и rx-тредов к конкретным cores
  (через `ThreadFactory` + Linux `taskset`/JNI). За пределами текущего
  ядра — делается извне.
- **Метрики**: добавить `LongAdder`-based counters (accepted, dropped,
  failed, latency histogram через HdrHistogram) — тривиально, но не
  делаем в MVP.

## Известные ограничения

- **Нет auto-reconnect.** Если remote рестартанул, Aeron session потеряна,
  новые `call(...)` будут failfast по heartbeat. Автоматического пересоздания
  publication не делается. В продакшне — оборачивайте `RpcChannel` в
  supervisor-компонент, который `close()` + `node.channel(...)` при
  длительном DOWN.
- **Rx-тред единый.** Тяжёлая INLINE-логика заблокирует весь RX. Решение —
  `HandlerMode.OFFLOAD`.
- **Ошибки handler-а печатаются в `System.err`.** Подмените на SLF4J/JUL
  для продакшна (простой find/replace в `RpcChannel.invokeHandler` и
  `SenderQueue.loop`).
- **DROP_OLDEST-гонки.** Под высокой нагрузкой с DROP_OLDEST несколько
  producer-ов могут по очереди вытеснять друг друга. Сделано с retry 4x,
  потом `BackpressureException`. Для строгой fairness DROP_OLDEST вам всё
  равно не подходит — используйте `BLOCK` с достаточной ёмкостью queue.
- **LargePayloadRpcChannel не реализован.** Payload > 16 MiB требует
  отдельного компонента с chunking + flow-control — см. стаб.

## Сборка

```bash
mvn -T1C clean install
```

Артефакт: `rpc-core/target/rpc-core-1.0-SNAPSHOT.jar`.
Подключайте его в свой проект вместе с зависимостями Aeron (`aeron-all`)
и Agrona.

## Лицензия / авторство

Код учебный/прототипный — выстроено под конкретные требования: sync RPC,
low-latency, zero-alloc, extensibility. Адаптируйте и расширяйте.