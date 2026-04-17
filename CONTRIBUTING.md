# Участие в проекте / Contributing

Спасибо за интерес к проекту.

Thank you for your interest in the project.

`aeron-rpc` - это low-latency RPC-библиотека поверх Aeron. Главные приоритеты проекта: минимальная задержка, предсказуемое поведение, стабильная производительность и поддерживаемый код.

`aeron-rpc` is a low-latency RPC library built on top of Aeron. The main priorities are low latency, predictable behavior, stable performance, and maintainable code.

---

## Основные принципы / Core Principles

### 1. Производительность важна, но не любой ценой / Performance Matters, But Not at Any Cost

- Изменения должны сохранять или улучшать latency, throughput и предсказуемость.
- Код должен оставаться читаемым и поддерживаемым.
- Оптимизация должна быть оправдана измеримым выигрышем или понятным снижением риска.

- Changes should preserve or improve latency, throughput, and predictability.
- Code should remain readable and maintainable.
- Optimization should be justified by measurable gains or clear risk reduction.

### 2. Простота важнее лишних абстракций / Simplicity Over Unnecessary Abstractions

- Используйте простые структуры данных и прямой control flow.
- Не добавляйте абстракции только ради архитектурной красоты.
- В hot path избегайте лишних уровней вызовов, аллокаций и динамического поведения.

- Prefer simple data structures and direct control flow.
- Do not add abstractions only for architectural neatness.
- Avoid extra call layers, allocations, and dynamic behavior in the hot path.

### 3. Баланс между ООП и производительностью / Balance OOP and Performance

ООП допустимо, пока оно не мешает latency, JIT inline и контролю аллокаций. Если абстракция ухудшает hot path, ее нужно упростить.

OOP is welcome as long as it does not hurt latency, JIT inlining, or allocation control. If an abstraction makes the hot path worse, simplify it.

Принцип:

Principle:

> Сначала простой и понятный код, затем точечная оптимизация hot path.
>
> Start with simple and understandable code, then optimize the hot path deliberately.

### 4. Стиль, близкий к Aeron / Aeron-Style Code

Проект следует духу Aeron:

This project follows the spirit of Aeron:

- минимум магии;
- предсказуемый код для JIT;
- явная работа с буферами, памятью и потоками;
- понятные границы ответственности;
- осторожное отношение к lock-ам и контекстным переключениям.

- minimal magic;
- JIT-friendly predictable code;
- explicit work with buffers, memory, and threads;
- clear ownership boundaries;
- careful use of locks and context switches.

### 5. JIT-friendly код / JIT-Friendly Code

Предпочтительно:

Prefer:

- `final` там, где это уместно;
- `static` helper-методы, когда состояние не требуется;
- простые ветвления;
- линейный код в критичных местах;
- primitive collections, если они уже используются в проекте.

- `final` where appropriate;
- `static` helper methods when no state is needed;
- simple branches;
- linear code in critical paths;
- primitive collections when they are already used by the project.

Избегайте:

Avoid:

- сложных иерархий;
- избыточного полиморфизма;
- reflection в hot path;
- dynamic proxies;
- Stream API и `Optional` в критичных местах.

- deep hierarchies;
- excessive polymorphism;
- reflection in the hot path;
- dynamic proxies;
- Stream API and `Optional` in critical paths.

### 6. Контроль аллокаций / Allocation Control

- Минимизируйте аллокации в hot path.
- Используйте переиспользуемые буферы и pools там, где это уже соответствует дизайну проекта.
- Любая новая аллокация в критичном участке должна быть осознанной.

- Minimize allocations in the hot path.
- Use reusable buffers and pools where they match the existing design.
- Every new allocation in a critical path should be intentional.

### 7. Предсказуемость / Predictability

- Избегайте скрытого поведения.
- Ошибки должны быть явными и диагностируемыми.
- Конкурентный код должен быть простым для анализа.
- Если меняется поведение потоков, ожиданий или backpressure, объясните это в PR.

- Avoid hidden behavior.
- Errors should be explicit and diagnosable.
- Concurrent code should be easy to reason about.
- If a change affects threading, waiting, or backpressure behavior, explain it in the PR.

---

## Требования к Pull Request / Pull Request Requirements

Перед отправкой PR:

Before submitting a PR:

- объясните цель изменения;
- опишите влияние на latency, throughput или allocation profile, если оно есть;
- добавьте unit-тесты для новой логики;
- добавьте или обновите Javadoc для публичного API;
- для performance-sensitive изменений приложите JMH benchmark или объясните, почему он не нужен.

- explain the purpose of the change;
- describe the impact on latency, throughput, or allocation profile when relevant;
- add unit tests for new logic;
- add or update Javadoc for public API;
- for performance-sensitive changes, include a JMH benchmark or explain why it is not needed.

---

## Документация / Documentation

Публичный API должен быть документирован Javadoc-комментариями. Предпочтительный стиль проекта - двуязычный: русский основной текст и краткое английское пояснение.

Public API should be documented with Javadoc comments. The preferred project style is bilingual: Russian primary text with a concise English explanation.

---

## Стиль коммитов / Commit Style

Коммиты должны быть короткими и понятными. Хорошие примеры:

Commits should be short and clear. Good examples:

```text
Add GitHub Packages publishing
Document ChannelConfig builder methods
Fix pending call cleanup on timeout
```

---

## Итог / Summary

Главный принцип:

Main principle:

> Мы пишем быстрый, предсказуемый и поддерживаемый код. Если приходится выбирать, приоритет за latency, но без превращения кода в хаос.
>
> We write fast, predictable, and maintainable code. When trade-offs are required, latency matters, but not at the cost of turning the code into chaos.
