# ws-desktop-manager

Настольное приложение (JavaFX + Maven) с двумя утилитами:

- **WebSocket** — локальный стенд биржевого WebSocket-сервера (Node.js).  
  Используется для тестирования котировок, стаканов и сценариев, описанных в JSON-конфигах.

- **DeepLinks** — инструмент для запуска Android-диплинков через ADB с удобным списком ссылок и хоткеями.

## Технологии
- Java 23 + JavaFX 23.0.2
- Maven
- Node.js WebSocket-сервер

## Как запустить
```bash
# Запуск десктоп-приложения
mvn javafx:run

# (Опционально) Запуск локального WebSocket-сервера
node node-server/server.js
