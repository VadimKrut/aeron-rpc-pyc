# aeron-rpc

Синхронный request/response RPC поверх Aeron UDP для low-latency Java-систем.

Synchronous request/response RPC over Aeron UDP for low-latency Java systems.

Библиотека дает простой blocking API в стиле обычного `call()`, но внутри ориентирована на throughput, минимальные аллокации и предсказуемую задержку.

The library provides a simple blocking `call()` API while keeping the internals optimized for throughput, minimal allocations, and predictable latency.

---

## Зачем это нужно / Why This Exists

Многие high-performance транспорты заставляют писать async/reactive код даже там, где бизнес-логика естественно выглядит как request/response.

Many high-performance transports force async/reactive code even when the business flow is naturally request/response.

Проект стремится дать:

This project aims to provide:

* **Синхронный API / Synchronous API** - простая модель `call()`.
* **Низкую задержку / Low latency** - ориентир на десятки микросекунд на localhost.
* **Aeron transport** - UDP без HTTP-overhead.
* **Подключаемую сериализацию / Pluggable serialization** - SBE, Kryo, Protobuf или raw bytes.

---

## Когда использовать / When to Use

Хорошо подходит:

Good fit:

* внутренние microservices с жесткими требованиями к latency;
* trading / fintech / market-data системы;
* request/response сценарии, где важна простота API;
* локальные или private-network сервисы, где инфраструктура контролируется пользователем.

* internal microservices with strict latency requirements;
* trading, fintech, and market-data systems;
* request/response flows where API simplicity matters;
* local or private-network services where infrastructure is controlled by the user.

Не лучший выбор:

Not a good fit:

* передача больших payload-ов как основная задача;
* публичные интернет-API без отдельного security-layer;
* замена service mesh;
* сценарии, где HTTP/gRPC удобнее важнее, чем latency.

* large payload transfer as the main workload;
* public internet APIs without a separate security layer;
* service mesh replacement;
* cases where HTTP/gRPC convenience matters more than latency.

---

## Установка / Installation

### Maven через GitVerse / Maven via GitVerse

```xml
<repositories>
    <repository>
        <id>gitverse</id>
        <url>https://gitverse.ru/api/packages/VadimKrut/maven/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>ru.pathcreator.pyc</groupId>
        <artifactId>aeron-rpc</artifactId>
        <version>0.0.4</version>
    </dependency>
</dependencies>
```

### Maven через GitHub Packages / Maven via GitHub Packages

GitHub Packages требует авторизацию даже для скачивания Maven-пакетов. Добавьте GitHub token с правом `read:packages` в Maven `settings.xml`.

GitHub Packages requires authentication even for consuming Maven packages. Add a GitHub token with `read:packages` to Maven `settings.xml`.

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_GITHUB_TOKEN</password>
        </server>
    </servers>
</settings>
```

Затем добавьте GitHub Packages repository и dependency.

Then add the GitHub Packages repository and dependency.

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/vadimkrut/aeron-rpc-pyc</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>ru.pathcreator.pyc</groupId>
        <artifactId>aeron-rpc</artifactId>
        <version>0.0.4</version>
    </dependency>
</dependencies>
```

---

## Требования / Requirements

* Java 25+
* Maven 3.9+
* Aeron-compatible runtime environment

---

## Быстрый старт / Quick Start

### 1. Создать node / Create a node

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/aeron")
                .build()
);
```

### 2. Создать channel / Create a channel

```java
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("0.0.0.0:40101")
                .remoteEndpoint("localhost:40102")
                .streamId(1001)
                .build()
);
```

### 3. Зарегистрировать handler / Register a handler

```java
channel.onRequest(
        1,
        2,
        new MyRequestCodec(),
        new MyResponseCodec(),
        req -> new MyResponse(req.id())
);
```

### 4. Запустить channel / Start the channel

```java
channel.start();
```

### 5. Выполнить вызов / Make a call

```java
MyResponse response = channel.call(
        1,
        2,
        new MyRequest(42),
        new MyRequestCodec(),
        new MyResponseCodec()
);
```

---

## Основные понятия / Core Concepts

### RpcNode

`RpcNode` владеет Aeron client, optional embedded MediaDriver, общим offload executor и списком открытых каналов.

`RpcNode` owns the Aeron client, optional embedded MediaDriver, shared offload executor, and all opened channels.

### RpcChannel

`RpcChannel` - один двунаправленный RPC-канал: одна `Publication`, одна `Subscription`, один RX thread.

`RpcChannel` is one bidirectional RPC channel: one `Publication`, one `Subscription`, and one RX thread.

Вызовы из нескольких потоков безопасны. Ответы сопоставляются с запросами по transport correlation id, поэтому concurrent callers не получают чужие replies.

Calls from multiple threads are safe. Responses are matched to requests by transport correlation id, so concurrent callers do not receive each other's replies.

### MessageCodec

`MessageCodec` - пользовательский слой сериализации.

`MessageCodec` is the user-defined serialization layer.

```java
interface MessageCodec<T> {
    int encode(T message, MutableDirectBuffer buffer, int offset);

    T decode(DirectBuffer buffer, int offset, int length);
}
```

### RawRequestHandler

`RawRequestHandler` - zero-alloc server path: handler получает raw bytes и заранее выделенный response buffer.

`RawRequestHandler` is a zero-alloc server path: the handler receives raw bytes and a preallocated response buffer.

---

## Конфигурация / Configuration

Главные настройки: где выполняется handler, как idle-ит RX thread и что делать при backpressure.

The main choices are where the handler runs, how the RX thread idles, and what happens under backpressure.

### Handler execution

| Setting | Где выполняется / Where it runs | Когда использовать / Use when |
|---------|----------------------------------|-------------------------------|
| default, no `offloadExecutor` | virtual thread | handler делает I/O и не должен блокировать RX thread / handler does I/O and should not block RX |
| `.offloadExecutor(myPool)` | custom `ExecutorService` | CPU-heavy handlers или свой scheduler / CPU-heavy handlers or custom scheduling |
| `.offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)` | прямо в RX thread / directly in RX thread | ultra-fast handlers, ACK, in-memory lookup |

`DIRECT_EXECUTOR` убирает payload copy и executor dispatch, но медленный handler блокирует прием сообщений.

`DIRECT_EXECUTOR` skips payload copy and executor dispatch, but a slow handler blocks receive processing.

### RX idle strategy

| Strategy | CPU при idle / Idle CPU | Задержка после idle / Latency after idle | Когда использовать / Use when |
|----------|--------------------------|-------------------------------------------|-------------------------------|
| `YIELDING` | около одного ядра / about one core | минимальная / minimal | low-latency default |
| `BUSY_SPIN` | одно ядро постоянно / one full core | минимальная, fastest hot loop / minimal, fastest hot loop | maximum latency tuning |
| `BACKOFF` | низкая / low | может быть выше / can be higher | mostly-idle channels, CPU-sensitive hosts |

### Backpressure

| Policy | Поведение / Behavior | Когда использовать / Use when |
|--------|----------------------|-------------------------------|
| `BLOCK` | caller ждет accept или `offerTimeout` / caller waits until accepted or timeout | обычный RPC / normal RPC |
| `FAIL_FAST` | сразу `BackpressureException` / immediate `BackpressureException` | caller имеет retry/fallback |

---

## Рецепты / Recipes

### Минимальная задержка / Minimum latency

```java
ChannelConfig.builder()
        .localEndpoint("...")
        .remoteEndpoint("...")
        .streamId(1001)
        .offloadExecutor(ChannelConfig.DIRECT_EXECUTOR)
        .rxIdleStrategy(IdleStrategyKind.BUSY_SPIN)
        .build();
```

### Обычный I/O handler / Typical I/O handler

```java
ChannelConfig.builder()
        .localEndpoint("...")
        .remoteEndpoint("...")
        .streamId(1002)
        .rxIdleStrategy(IdleStrategyKind.YIELDING)
        .build();
```

### Mostly-idle channel

```java
ChannelConfig.builder()
        .localEndpoint("...")
        .remoteEndpoint("...")
        .streamId(1003)
        .rxIdleStrategy(IdleStrategyKind.BACKOFF)
        .build();
```

---

## Тесты и документация / Tests and Documentation

Запустить unit tests:

Run unit tests:

```bash
mvn test
```

Запустить полный verification lifecycle, включая tests, sources jar и Javadoc jar:

Run the full verification lifecycle, including tests, sources jar, and Javadoc jar:

```bash
mvn clean verify
```

Сгенерировать Javadoc:

Generate Javadoc:

```bash
mvn javadoc:javadoc
```

Javadoc будет создан в:

Javadoc is written to:

```text
target/reports/apidocs
```

На Java 25 для Agrona требуется доступ к internal API. Для unit tests это уже настроено в Maven Surefire.

On Java 25, Agrona requires access to internal APIs. This is already configured for unit tests through Maven Surefire.

```text
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
```

---

## Benchmarks

Убедитесь, что Maven использует JDK 25.

Make sure Maven runs on JDK 25.

```bash
mvn -version
```

На Windows PowerShell, если Maven использует старый JDK, задайте `JAVA_HOME` для текущей сессии.

On Windows PowerShell, if Maven uses an older JDK, set `JAVA_HOME` for the current session.

```powershell
$env:JAVA_HOME = "PATH_TO_JDK_25"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

Собрать JMH benchmark jar:

Build the JMH benchmark jar:

```bash
mvn -Pbenchmarks -DskipTests clean package
```

Запустить все RPC benchmarks:

Run all RPC benchmarks:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark
```

Запустить один сценарий:

Run one focused scenario:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark.oneChannelEightThreads
```

Ограничить параметры:

Limit parameters:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark.oneChannelEightThreads -p handlerMode=DIRECT -p payloadSize=256 -p idleStrategy=YIELDING
```

Сравнить RX idle strategies:

Compare RX idle strategies:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark.oneChannelEightThreads -p idleStrategy=YIELDING,BUSY_SPIN,BACKOFF
```

Сохранить результат в CSV:

Save results to CSV:

```bash
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -jar target/aeron-rpc-benchmarks.jar RpcChannelBenchmark -rf csv -rff target/jmh-rpc.csv
```

В WSL/Linux используйте `/` в путях, например `target/aeron-rpc-benchmarks.jar`.

In WSL/Linux, use `/` in paths, for example `target/aeron-rpc-benchmarks.jar`.

### Benchmark parameters

| Parameter | Values | Meaning |
|-----------|--------|---------|
| `handlerMode` | `DIRECT`, `OFFLOAD` | where server handlers run |
| `payloadSize` | `16`, `256`, `1024` | request and response payload size |
| `idleStrategy` | `YIELDING`, `BUSY_SPIN`, `BACKOFF` | RX thread idle strategy |

### Reference benchmark results

Это пример локального запуска в WSL на Java 25. Это ориентир, а не переносимая гарантия: результаты зависят от CPU, OS scheduler, power mode, MediaDriver placement и filesystem.

These are example local results from a WSL run on Java 25. Treat them as a reference point, not as a portable guarantee: results depend on CPU, OS scheduler, power mode, MediaDriver placement, and filesystem.

`Score` - throughput в operations per second. `Error` - не ошибка теста, а статистический confidence interval. Большой `Error` означает шумный результат.

`Score` is throughput in operations per second. `Error` is not a test failure; it is the statistical confidence interval. A large `Error` means the result was noisy.

| Benchmark | Handler | RX idle | Payload | Score, ops/s | Error, ops/s |
|-----------|---------|---------|---------|--------------|--------------|
| `fourChannelsEightThreadsEach` | DIRECT | YIELDING | 16 B | 61,407.683 | 9,502.823 |
| `fourChannelsEightThreadsEach` | DIRECT | YIELDING | 256 B | 49,200.071 | 56,955.723 |
| `fourChannelsEightThreadsEach` | DIRECT | YIELDING | 1024 B | 64,005.432 | 36,969.464 |
| `fourChannelsEightThreadsEach` | DIRECT | BUSY_SPIN | 16 B | 77,975.351 | 10,021.085 |
| `fourChannelsEightThreadsEach` | DIRECT | BUSY_SPIN | 256 B | 88,761.459 | 107,865.061 |
| `fourChannelsEightThreadsEach` | DIRECT | BUSY_SPIN | 1024 B | 85,725.096 | 38,059.795 |
| `fourChannelsEightThreadsEach` | DIRECT | BACKOFF | 16 B | 54,572.523 | 9,958.987 |
| `fourChannelsEightThreadsEach` | DIRECT | BACKOFF | 256 B | 53,701.850 | 32,721.614 |
| `fourChannelsEightThreadsEach` | DIRECT | BACKOFF | 1024 B | 64,984.427 | 22,871.002 |
| `fourChannelsEightThreadsEach` | OFFLOAD | YIELDING | 16 B | 47,850.989 | 24,732.797 |
| `fourChannelsEightThreadsEach` | OFFLOAD | YIELDING | 256 B | 38,428.156 | 8,847.332 |
| `fourChannelsEightThreadsEach` | OFFLOAD | YIELDING | 1024 B | 49,100.813 | 53,337.230 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BUSY_SPIN | 16 B | 62,579.518 | 15,203.081 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BUSY_SPIN | 256 B | 44,528.867 | 37,385.968 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BUSY_SPIN | 1024 B | 61,105.251 | 76,631.469 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BACKOFF | 16 B | 43,451.862 | 9,035.131 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BACKOFF | 256 B | 38,047.466 | 6,368.076 |
| `fourChannelsEightThreadsEach` | OFFLOAD | BACKOFF | 1024 B | 51,211.319 | 47,061.843 |
| `oneChannelEightThreads` | DIRECT | YIELDING | 16 B | 454,729.955 | 244,878.842 |
| `oneChannelEightThreads` | DIRECT | YIELDING | 256 B | 230,722.594 | 12,815.495 |
| `oneChannelEightThreads` | DIRECT | YIELDING | 1024 B | 236,397.916 | 43,727.005 |
| `oneChannelEightThreads` | DIRECT | BUSY_SPIN | 16 B | 282,136.430 | 39,527.653 |
| `oneChannelEightThreads` | DIRECT | BUSY_SPIN | 256 B | 298,127.380 | 64,247.462 |
| `oneChannelEightThreads` | DIRECT | BUSY_SPIN | 1024 B | 254,938.200 | 13,770.398 |
| `oneChannelEightThreads` | DIRECT | BACKOFF | 16 B | 83,531.929 | 21,939.100 |
| `oneChannelEightThreads` | DIRECT | BACKOFF | 256 B | 97,467.152 | 39,229.694 |
| `oneChannelEightThreads` | DIRECT | BACKOFF | 1024 B | 145,545.016 | 35,216.105 |
| `oneChannelEightThreads` | OFFLOAD | YIELDING | 16 B | 145,612.182 | 27,664.428 |
| `oneChannelEightThreads` | OFFLOAD | YIELDING | 256 B | 140,335.127 | 21,444.443 |
| `oneChannelEightThreads` | OFFLOAD | YIELDING | 1024 B | 127,947.144 | 22,790.599 |
| `oneChannelEightThreads` | OFFLOAD | BUSY_SPIN | 16 B | 137,504.890 | 41,220.196 |
| `oneChannelEightThreads` | OFFLOAD | BUSY_SPIN | 256 B | 133,542.345 | 32,919.160 |
| `oneChannelEightThreads` | OFFLOAD | BUSY_SPIN | 1024 B | 135,113.223 | 28,632.751 |
| `oneChannelEightThreads` | OFFLOAD | BACKOFF | 16 B | 49,856.387 | 4,628.959 |
| `oneChannelEightThreads` | OFFLOAD | BACKOFF | 256 B | 50,740.938 | 2,239.948 |
| `oneChannelEightThreads` | OFFLOAD | BACKOFF | 1024 B | 49,546.680 | 879.996 |
| `oneChannelOneThread` | DIRECT | YIELDING | 16 B | 186,904.075 | 6,926.592 |
| `oneChannelOneThread` | DIRECT | YIELDING | 256 B | 182,500.107 | 6,742.407 |
| `oneChannelOneThread` | DIRECT | YIELDING | 1024 B | 168,516.496 | 12,630.781 |
| `oneChannelOneThread` | DIRECT | BUSY_SPIN | 16 B | 189,722.932 | 2,501.386 |
| `oneChannelOneThread` | DIRECT | BUSY_SPIN | 256 B | 184,005.769 | 3,727.608 |
| `oneChannelOneThread` | DIRECT | BUSY_SPIN | 1024 B | 167,798.554 | 2,143.439 |
| `oneChannelOneThread` | DIRECT | BACKOFF | 16 B | 19,800.224 | 2,735.737 |
| `oneChannelOneThread` | DIRECT | BACKOFF | 256 B | 20,936.170 | 20,567.851 |
| `oneChannelOneThread` | DIRECT | BACKOFF | 1024 B | 16,980.122 | 4,493.010 |
| `oneChannelOneThread` | OFFLOAD | YIELDING | 16 B | 139,926.312 | 19,629.895 |
| `oneChannelOneThread` | OFFLOAD | YIELDING | 256 B | 141,598.614 | 16,001.812 |
| `oneChannelOneThread` | OFFLOAD | YIELDING | 1024 B | 125,358.303 | 13,997.795 |
| `oneChannelOneThread` | OFFLOAD | BUSY_SPIN | 16 B | 143,182.508 | 23,719.307 |
| `oneChannelOneThread` | OFFLOAD | BUSY_SPIN | 256 B | 139,550.293 | 8,069.328 |
| `oneChannelOneThread` | OFFLOAD | BUSY_SPIN | 1024 B | 124,440.943 | 20,101.422 |
| `oneChannelOneThread` | OFFLOAD | BACKOFF | 16 B | 10,474.733 | 738.089 |
| `oneChannelOneThread` | OFFLOAD | BACKOFF | 256 B | 10,522.227 | 738.932 |
| `oneChannelOneThread` | OFFLOAD | BACKOFF | 1024 B | 10,484.348 | 586.815 |

### Как читать результаты / How to Read the Results

* `DIRECT` лучше для очень быстрых handlers, потому что убирает offload queueing и payload copy.
* `OFFLOAD` безопаснее для blocking или slow handlers, потому что защищает RX thread от пользовательского кода.
* `YIELDING` - хороший low-latency default.
* `BUSY_SPIN` может быть немного быстрее в hot loop, но постоянно расходует CPU.
* `BACKOFF` экономит CPU при idle, но может снизить throughput и увеличить latency после idle.
* Больше каналов не всегда означает больше throughput в одном процессе: RX threads, embedded MediaDriver и OS scheduler могут стать bottleneck.

* `DIRECT` is best for very fast handlers because it avoids offload queueing and payload copy.
* `OFFLOAD` is safer for blocking or slow handlers because it protects the RX thread from user code.
* `YIELDING` is a good low-latency default.
* `BUSY_SPIN` can be slightly faster in hot loops, but it burns CPU continuously.
* `BACKOFF` saves CPU while idle, but can reduce throughput and add latency after idle periods.
* More channels do not always mean more throughput in one process: RX threads, embedded MediaDriver, and the OS scheduler can become bottlenecks.

### I/O handler recommendation

Для I/O-bound handlers используйте `OFFLOAD + YIELDING`.

For I/O-bound handlers, use `OFFLOAD + YIELDING`.

`OFFLOAD` не дает blocking user code остановить RX thread, а `YIELDING` остается хорошим low-latency default для receive loop.

`OFFLOAD` keeps blocking user code away from the RX thread, while `YIELDING` remains a good low-latency default for the receive loop.

По benchmark-ам на 1024-byte payload ожидаемый transport overhead:

Based on the 1024-byte benchmark results, the expected transport overhead is approximately:

* `~8 us` на round-trip с одним caller thread / per round-trip with one caller thread;
* `~60-65 us` average round-trip latency с восемью concurrent caller threads / with eight concurrent caller threads.

Реальная работа с database, HTTP, filesystem или сетью добавляется сверху.

Real database, HTTP, filesystem, or network work should be added on top.

---

## Дизайн производительности / Performance Design

* Нет sender thread: caller напрямую вызывает `tryClaim` на `ConcurrentPublication`.
* `tryClaim` fast-path для сообщений `<= maxPayloadLength`; `offer` fallback для больших сообщений.
* Thread-local direct staging buffers.
* Pooled `PendingCall`, `OffloadTask` и copy buffers.
* Primitive maps без boxing.
* 3-phase sync wait: spin, yield, park.
* MediaDriver в `SHARED` threading mode.

* No sender thread: caller invokes `tryClaim` directly on a `ConcurrentPublication`.
* `tryClaim` fast path for messages `<= maxPayloadLength`; `offer` fallback for larger messages.
* Thread-local direct staging buffers.
* Pooled `PendingCall`, `OffloadTask`, and copy buffers.
* Primitive maps without boxing.
* 3-phase sync wait: spin, yield, park.
* MediaDriver uses `SHARED` threading mode.

---

## Ограничения / Limitations

* Нет auto-reconnect; channel fail-fast через heartbeat.
* Default max payload: 16 MiB.
* `DIRECT_EXECUTOR` блокирует RX thread на время handler-а.
* Библиотека не предоставляет authentication, authorization или encryption; это зона ответственности приложения или инфраструктуры.

* No auto-reconnect; the channel fails fast through heartbeat.
* Default max payload: 16 MiB.
* `DIRECT_EXECUTOR` blocks the RX thread while the handler runs.
* The library does not provide authentication, authorization, or encryption; these belong to the application or infrastructure layer.

---

## Лицензия / License

Apache License 2.0
