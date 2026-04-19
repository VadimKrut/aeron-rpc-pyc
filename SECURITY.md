# Политика безопасности / Security Policy

## Сообщение об уязвимостях / Reporting Vulnerabilities

Если вы обнаружили уязвимость, пожалуйста, не создавайте публичный issue.

If you discover a vulnerability, please do not create a public issue.

Вместо этого:

Instead:

- свяжитесь с владельцем репозитория напрямую;
- отправьте описание проблемы через приватный канал;
- приложите минимальные шаги воспроизведения, если это безопасно;
- укажите версию библиотеки и окружение, где была обнаружена проблема.

- contact the repository owner directly;
- send the issue description through a private channel;
- include minimal reproduction steps if it is safe to do so;
- specify the library version and environment where the issue was found.

Мы постараемся:

We will try to:

- подтвердить проблему;
- оценить влияние;
- подготовить исправление;
- выпустить обновление;
- при необходимости описать mitigation для пользователей.

- confirm the issue;
- assess the impact;
- prepare a fix;
- release an update;
- describe mitigation steps for users when needed.

---

## Поддерживаемые версии / Supported Versions

На данный момент поддерживается последняя опубликованная версия проекта.

At the moment, only the latest published version of the project is supported.

Если уязвимость относится к старой версии, исправление обычно будет выпускаться в новой версии, а не backport-ом.

If a vulnerability affects an older version, the fix will usually be released in a new version rather than backported.

---

## Область ответственности / Security Scope

`rpc-core` - это транспортная RPC-библиотека для low-latency систем. Она не предоставляет встроенные механизмы:

`rpc-core` is a transport RPC library for low-latency systems. It does not provide built-in:

- аутентификации;
- авторизации;
- шифрования;
- управления секретами;
- защиты публичного периметра.

- authentication;
- authorization;
- encryption;
- secret management;
- public perimeter protection.

Эти аспекты должны реализовываться на уровне инфраструктуры, приложения или отдельного security-layer.

These aspects should be handled at the infrastructure, application, or dedicated security-layer level.

---

## Рекомендации по использованию / Usage Recommendations

- Не открывайте Aeron/RPC endpoints в публичный интернет без дополнительной защиты.
- Используйте сетевую изоляцию, firewall, VPN, mTLS или другой внешний security-layer.
- Валидируйте входящие `messageTypeId` и payload на уровне приложения.
- Ограничивайте максимальный размер payload через `ChannelConfig`.
- Не передавайте secrets в открытом виде, если транспортная сеть не защищена.

- Do not expose Aeron/RPC endpoints to the public internet without additional protection.
- Use network isolation, firewalls, VPN, mTLS, or another external security layer.
- Validate incoming `messageTypeId` values and payloads at the application level.
- Limit maximum payload size through `ChannelConfig`.
- Do not send secrets in plaintext unless the transport network is protected.

---

## Примечание / Notice

Проект предоставляется "как есть". Использование в production требует дополнительного анализа, нагрузочного тестирования
и security review со стороны пользователя.

The project is provided "as is". Production use requires additional analysis, load testing, and security review by the
user.