# Инструкции для ИИ

Закон проекта — `CONSTITUTION.md`. Эта страница говорит, как его применять в коде. Устройство трафика — `docs/architecture.md`. Человеческая сборка и сценарий использования — `README.md`.

Перед правкой пути пакета прочитай архитектуру. Перед правкой, которая меняет обещание из конституции, обнови статью в той же правке.

## Карта

| Путь | Роль |
| --- | --- |
| `core/src/main/kotlin/dev/httpvpn/net/Packets.kt` | Разбор и сборка IPv4, TCP, UDP, ICMP |
| `core/src/main/kotlin/dev/httpvpn/net/Seq.kt` | Арифметика sequence number |
| `core/src/main/kotlin/dev/httpvpn/net/TcpSession.kt` | Один TCP-поток телефона |
| `core/src/main/kotlin/dev/httpvpn/net/TunEngine.kt` | Демультиплексор пакетов TUN |
| `core/src/main/kotlin/dev/httpvpn/net/HttpCodec.kt` | `CONNECT`, заголовки, тело ответа |
| `core/src/main/kotlin/dev/httpvpn/net/DnsRelay.kt` | DoH, затем DNS-over-TCP, кэш |
| `core/src/main/kotlin/dev/httpvpn/net/Routes.kt` | Префиксы «публичный интернет» |
| `core/src/main/kotlin/dev/httpvpn/net/Tunnel.kt` | `Tunnel`, `TunnelFactory`, `TcpDial` |
| `app/src/main/java/dev/httpvpn/HttpVpnService.kt` | TUN, сокеты мимо VPN, уведомление |
| `app/src/main/java/dev/httpvpn/MainActivity.kt` | Экран |
| `app/src/main/java/dev/httpvpn/Prefs.kt` | Настройки прокси |
| `app/src/main/java/dev/httpvpn/VpnRuntime.kt` | Снимок состояния для UI |
| `app/build.gradle.kts` | `versionCode`, `versionName`, имя APK |

## Как менять

Поведение протокола живёт в `:core`. Android-оболочка только поднимает TUN, открывает сокет к прокси и показывает состояние.

Новый обмен с прокси для обычного TCP идёт через `HttpCodec.establish`. DNS-сокет тоже получает уже установленный туннель: его открывает `TcpDial`, который реализует `HttpVpnService`.

Сокет к прокси создаётся в сервисе. В ядро не переноси `protect`, `VpnService` и `ConnectivityManager`.

Пользовательские строки добавляй в `app/src/main/res/values/strings.xml` или в сообщения статуса по-русски. Пароль в лог не попадает.

Правка `app/` или `core/` поднимает версию по статье 8 конституции.

## Проверки

Юнит-тесты ядра не требуют Android SDK:

```bat
gradlew.bat :core:test
```

Сборка APK требует SDK и `local.properties`:

```bat
gradlew.bat :app:assembleDebug
```

Готовый файл: `app/build/outputs/apk/debug/simple-http-proxy-<versionName>.apk`.

После изменения `Packets`, `Seq`, `TcpSession`, `HttpCodec`, `DnsRelay` или `Routes` тесты обязательны. Не удаляй проверку, чтобы тест стал зелёным.

## Чего не делать без прямой просьбы

- Не проксировать UDP кроме DNS и не отвечать на него иначе, чем ICMP port unreachable.
- Не добавлять IPv6 в TUN, SOCKS, WireGuard и перехват TLS.
- Не заменять `TcpSession` записью сырых байтов из TUN в сокет.
- Не отключать exclude приложения, `protect` и RST на адрес прокси.
- Не логировать пароль и `Proxy-Authorization`.
- Не подключать библиотеки в `:core`, кроме тестового JUnit.
- Не менять версию, если не трогались `app/` и `core/`.

## Правила Cursor

| Файл | Когда действует |
| --- | --- |
| `.cursor/rules/constitution.mdc` | Всегда: короткий пересказ закона |
| `.cursor/rules/app-version.mdc` | Всегда: подъём версии |
| `.cursor/rules/core-protocol.mdc` | Правки `core/**/*.kt` |
| `.cursor/rules/android-shell.mdc` | Правки `app/**/*.kt`, `app/**/*.java`, `app/**/*.xml` |
