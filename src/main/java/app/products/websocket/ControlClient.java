// FILE: src/main/java/app/products/websocket/ControlClient.java
package app.products.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ControlClient implements WebSocket.Listener {

    private final Consumer<String> onMessage;
    private final WsProtocol protocol; // может быть null → старый режим
    private volatile WebSocket ws;

    // На каждое подключение — свой future завершения (завершается в onClose/onError)
    private volatile CompletableFuture<Void> closedFuture;

    // Признак, что канал реально открыт (onOpen прошёл)
    private final AtomicBoolean open = new AtomicBoolean(false);

    /** Старый режим (без протокола): полная обратная совместимость. */
    public ControlClient(Consumer<String> onMessage) {
        this(null, onMessage);
    }

    /** Новый режим: с протоколом (если не null). */
    public ControlClient(WsProtocol protocol, Consumer<String> onMessage) {
        this.protocol = protocol;
        this.onMessage = onMessage;
    }

    private void log(String s) { if (onMessage != null) onMessage.accept(s); }
    public boolean isOpen() { return open.get(); }

    /** Подключение к WS */
    public CompletableFuture<Void> connect(String baseUrl) {
        String url = (protocol != null) ? protocol.decorateUrl(baseUrl)
                : (baseUrl.contains("?") ? (baseUrl + "&control=1") : (baseUrl + "?control=1"));

        log("WS connect start → " + url);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(url), this)
                .thenAccept(w -> {
                    this.ws = w;
                    this.closedFuture = new CompletableFuture<>();
                    log("WS connected ok");
                })
                .thenRun(() -> {
                    if (protocol != null) {
                        // onOpen из priv_client-protocol.json
                        List<String> hello = protocol.initialHandshakeMessages();
                        if (hello != null) hello.forEach(this::sendRaw);
                    } else {
                        // старый режим
                        sendRaw("{\"t\":\"ControlHello\"}");
                        sendGetStatus();
                    }
                });
    }

    /** закрыть только текущую сессию */
    public CompletableFuture<Void> close() {
        WebSocket cur = this.ws;
        if (cur == null) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> waiter = (this.closedFuture != null && !this.closedFuture.isDone())
                ? this.closedFuture
                : (this.closedFuture = new CompletableFuture<>());

        try {
            log("WS sendClose(1000)");
            return cur.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                    .thenRun(() -> log("WS sendClose → sent"))
                    .thenCompose(v -> waiter)
                    .orTimeout(1500, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        try { cur.abort(); log("WS abort() [timeout]"); } catch (Throwable ignore) {}
                        if (!waiter.isDone()) waiter.complete(null);
                        return null;
                    })
                    .whenComplete((v, ex) -> {
                        this.ws = null;
                        open.set(false);
                    });
        } catch (Throwable t) {
            try { cur.abort(); log("WS abort() [exception]"); } catch (Throwable ignore) {}
            this.ws = null;
            open.set(false);
            if (!waiter.isDone()) waiter.complete(null);
            return waiter;
        }
    }

    /** Массовое закрытие (поведение как было) */
    public CompletableFuture<Void> closeAllAndWait() {
        WebSocket cur = this.ws;
        if (cur == null) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> waiter = (this.closedFuture != null && !this.closedFuture.isDone())
                ? this.closedFuture
                : (this.closedFuture = new CompletableFuture<>());

        // 1) попробуем попросить сервер закрыть всех
        try {
            sendControl("closeAll");
        } catch (Throwable ignore) {}

        // 2) ждём закрытие
        return waiter.orTimeout(1500, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    // 3) закрываем сами
                    try {
                        log("WS sendClose(1000) [fallback]");
                        cur.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                    } catch (Throwable ignore) {}
                    return null;
                })
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    // 4) жёстко рвём
                    try { cur.abort(); log("WS abort() [fallback]"); } catch (Throwable ignore) {}
                    return null;
                })
                .whenComplete((v, ex) -> {
                    this.ws = null;
                    open.set(false);
                    if (!waiter.isDone()) waiter.complete(null);
                });
    }

    // ===== API для контроллера (с сохранением прежних имён) =====

    public void sendRaw(String json) {
        WebSocket w = ws;
        if (w != null) w.sendText(json, true);
    }

    public void sendControl(String cmd) {
        if (protocol != null) {
            // Используем протокол для построения сообщения
            sendRaw(protocol.build("control", Map.of("cmd", cmd)));
        } else {
            sendRaw("{\"t\":\"Control\",\"cmd\":\"" + escape(cmd) + "\"}");
        }
    }

    public void setScenario(String scenario) {
        if (protocol != null) {
            sendRaw(protocol.build("scenario.set", Map.of("scenario", scenario)));
        } else {
            sendRaw("{\"t\":\"SetScenario\",\"scenario\":\"" + escape(scenario) + "\"}");
        }
    }

    public void sendGetStatus() {
        if (protocol != null) {
            sendRaw(protocol.build("status.get", Map.of()));
        } else {
            sendRaw("{\"t\":\"GetStatus\"}");
        }
    }

    public void sendGetConfig() {
        if (protocol != null) {
            sendRaw(protocol.build("config.get", Map.of()));
        } else {
            sendRaw("{\"t\":\"GetConfig\"}");
        }
    }

    // Новый метод для отправки manual quote через протокол
    public void sendManualQuote(List<Map<String, Object>> ops, boolean replaceCurrent) {
        if (protocol != null) {
            sendRaw(protocol.build("manual.quote", Map.of(
                    "ops", ops,
                    "replaceCurrent", replaceCurrent
            )));
        } else {
            // Старый режим - формируем JSON вручную
            StringBuilder sb = new StringBuilder();
            sb.append("{\"t\":\"Control\",\"cmd\":\"manualQuote\",\"ops\":[");
            for (int i = 0; i < ops.size(); i++) {
                Map<String, Object> op = ops.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"side\":\"").append(op.get("side")).append("\",");
                sb.append("\"price\":").append(op.get("price")).append(",");
                sb.append("\"volume\":").append(op.get("volume")).append("}");
            }
            sb.append("],\"replaceCurrent\":").append(replaceCurrent).append("}");
            sendRaw(sb.toString());
        }
    }

    private String escape(String s) { return s == null ? "" : s.replace("\"", "\\\""); }

    // ===== WebSocket.Listener =====

    @Override public void onOpen(WebSocket webSocket) {
        open.set(true);
        log("WS onOpen");
        webSocket.request(1);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        String t = data.toString();
        if (protocol != null) protocol.onInbound(t, onMessage);
        else if (onMessage != null) onMessage.accept(t);
        webSocket.request(1);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        String msg = (error == null || error.getMessage() == null) ? "unknown" : error.getMessage();
        if (onMessage != null) onMessage.accept("{\"t\":\"Error\",\"message\":\"" + msg.replace("\"","\\\"") + "\"}");
        CompletableFuture<Void> f = this.closedFuture;
        if (f != null && !f.isDone()) f.complete(null);
        open.set(false);
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log("WS onClose: code=" + statusCode + ", reason=" + reason);
        CompletableFuture<Void> f = this.closedFuture;
        if (f != null && !f.isDone()) f.complete(null);
        this.ws = null;
        open.set(false);
        return null;
    }
}