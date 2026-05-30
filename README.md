# 📱 TGFS (Telegram File System) — Android Client

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-green.svg)](https://developer.android.com/jetpack/compose)
[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://developer.android.com/)

**TGFS Mobile** — это современное Android-приложение, которое превращает ваш личный Telegram-аккаунт или чат с ботом в **безлимитное, зашифрованное облачное хранилище**. Проект разработан на базе Clean Architecture и Jetpack Compose, обеспечивая полную конфиденциальность и совместимость с десктопной экосистемой TGFS Core (Python CLI).

---

## 🌟 Основные возможности

*   **🗂 Потоковое разделение файлов:** Большие файлы автоматически нарезаются на оптимизированные чанки по 48 МБ без перегрузки оперативной памяти (RAM) устройства.
*   **🔐 Бескомпромиссная безопасность (E2E):** Все файлы шифруются на стороне клиента перед отправкой. Используется алгоритм **AES-GCM-256**. Ключ шифрования генерируется на базе вашего мастер-пароля через **PBKDF2WithHmacSHA256** (100,000 итераций) с уникальной солью и IV для каждого чанка.
*   **🔄 Умная синхронизация метаданных:** Структура папок и метаданные файлов хранятся в локальной базе данных SQLite (Room). Перед каждым бэкапом база принудительно синхронизирует WAL-логи (`PRAGMA wal_checkpoint`), упаковывается, шифруется и автоматически обновляет актуальный бэкап в вашем Telegram.
*   **📲 Интеграция с Android OS:** Возможность делиться файлами из любых сторонних приложений (галерея, проводник) напрямую в TGFS через системное меню "Поделиться" (`ReceiveShareActivity`).
*   **🎨 Современный UI/UX:** Интерфейс полностью построен на Jetpack Compose с поддержкой Material Design 3, динамических тем, удобной боковой панели навигации и кастомных диалоговых окон.

---

## 🛠 Технологический стек

*   **Язык:** Kotlin (Coroutines, Flow)
*   **Архитектура:** Clean Architecture + MVVM (Data, Domain, Presentation layers)
*   **UI-фреймворк:** Jetpack Compose (Material 3)
*   **База данных:** Room OS (SQLite) с поддержкой WAL-чекпоинтов
*   **Асинхронные задачи:** WorkManager (для стабильной фоновой загрузки тяжелых чанков)
*   **Сетевой стек:** OkHttp3 & Retrofit (Telegram Bot API / REST-клиент)
*   **Криптография:** Java Cryptography Architecture (JCA), AES-GCM-256, PBKDF2

---

## 🏗 Архитектура проекта

Приложение следует строгим принципам чистой архитектуры:
```text
📂 app/src/main/java/com/zomurb/tgfs
│
├── 📁 core          # Общие утилиты, DI-модули, темы оформления
├── 📁 crypto        # Модуль шифрования (PBKDF2, AES-GCM, генераторы соли и IV)
├── 📁 data          # Репозитории, Room DB, DAO, API-клиенты (OkHttp/Retrofit)
├── 📁 domain        # Бизнес-логика, Use Cases, сущности (классы моделей данных)
└── 📁 presentation  # Jetpack Compose UI (Экран Хранилища, Настройки, Воркеры)
```

---

## 🚀 Быстрый старт

Для использования приложения вам понадобятся:
* Токен Telegram-бота и ваш `Chat ID` (можно получить у `@userinfobot`).

### Установка

Вы можете скачать готовый к установке APK-файл со страницы [релизов](https://github.com/zomurb/tgfs-app/releases) и установить его на ваше устройство под управлением Android (требуется Android 8.0 или выше).

---

## 🔒 Безопасность и Конфиденциальность

Приложение работает по принципу **Zero-Knowledge**. Ваш мастер-пароль и сгенерированные ключи шифрования **никогда** не передаются по сети и не сохраняются в открытом виде. Telegram видит только поток зашифрованных байт, расшифровать которые без вашего пароля и соли невозможно даже теоретически.

---

## 🤝 Вклад в развитие (Contributing)

Мы рады любому вкладу в развитие проекта! Если вы нашли баг или хотите предложить новую фичу:

1. Форкните репозиторий.
2. Создайте свою ветку фичи (`git checkout -b feature/AmazingFeature`).
3. Закоммитьте изменения (`git commit -m 'Add some AmazingFeature'`).
4. Отправьте ветку в ваш форк (`git push origin feature/AmazingFeature`).
5. Откройте Pull Request.

---

## 📄 Лицензия

Этот проект распространяется под лицензией MIT. Подробности см. в файле [LICENSE](LICENSE).

## 🧑💻 Разработчик

* **GitHub:** [@zomurb](https://github.com/zomurb)
