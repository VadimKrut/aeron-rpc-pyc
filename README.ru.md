# aeron-rpc

Синхронный RPC поверх Aeron UDP для **low-latency Java систем**.

Даёт привычный blocking API (как HTTP), но без его накладных расходов.

---

## Зачем это нужно

Большинство высокопроизводительных решений заставляют писать сложный async-код.

Здесь цель другая:

* простой синхронный API (`call`)
* минимальная латентность
* отсутствие лишних аллокаций
* высокая производительность Aeron

---

## Когда использовать

Подходит:

* внутренние сервисы
* финтех / трейдинг
* low-latency системы

Не подходит:

* большие payload (>16MB)
* публичные API
* замена service mesh

---

## Установка

### Maven (GitVerse)

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
    <version>1.0.0-SNAPSHOT</version>
</dependency>
</dependencies>
```

---

## Требования

* Java 25+
* Maven 3.9+
* Aeron

---

## Быстрый старт

### 1. Нода

```java
RpcNode node = RpcNode.start(
        NodeConfig.builder()
                .aeronDir("/tmp/aeron")
                .build()
);
```

---

### 2. Канал

```java
RpcChannel channel = node.channel(
        ChannelConfig.builder()
                .localEndpoint("0.0.0.0:40101")
                .remoteEndpoint("localhost:40102")
                .streamId(1001)
                .build()
);
```

---

### 3. Handler

```java
channel.onRequest(
                1,
                2,
                new MyRequestCodec(), 
                new MyResponseCodec(),
                HandlerMode.OFFLOAD,
                req -> new MyResponse(req.id)
);
```

---

### 4. Старт

```java
channel.start();
```

---

### 5. Вызов

```java
MyResponse resp = channel.call(
        1, 2,
        new MyRequest(42),
        new MyRequestCodec(),
        new MyResponseCodec()
);
```

---

## Основные концепции

### RpcNode

* один MediaDriver
* несколько каналов

### RpcChannel

* отдельный sender thread
* отдельный receiver thread
* очередь MPSC

---

## Режимы handler-ов

* `INLINE` — максимально быстро, но блокирует RX thread
* `OFFLOAD` — выполняется в executor

---

## Backpressure

* `BLOCK` — ждать
* `FAIL_FAST` — сразу ошибка
* `DROP_NEW` / `DROP_OLDEST` — не для RPC

---

## Ограничения

* нет auto-reconnect
* максимум payload — 16MB
* INLINE handler может блокировать RX

---

## Roadmap

* reconnect
* метрики
* большие payload
* zero-copy API
* логирование

---

## Статус

Ранний релиз.

API может меняться.

---

## Лицензия

Apache License 2.0