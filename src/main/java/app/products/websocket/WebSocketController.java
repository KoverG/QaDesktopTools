// FILE: src/main/java/app/products/websocket/WebSocketController.java
package app.products.websocket;

import app.core.Router;
import app.ui.UiSvg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.Socket;

import app.core.node.NodeServerLauncher;
import app.core.node.AppShutdown;

public class WebSocketController {

    // ===== Node server =====
    private NodeServerLauncher node;

    // ===== Agg Fill (только включённые кнопки) =====
    @FXML public void onAggFillBidUp()   { sendCmd("quoteAggBidUp"); }
    @FXML public void onAggFillBidDown() { sendCmd("quoteAggBidDown"); }
    @FXML public void onAggFillAskUp()   { sendCmd("quoteAggAskUp"); }
    @FXML public void onAggFillAskDown() { sendCmd("quoteAggAskDown"); }
    @FXML public void onAggFillBidAll()   { sendCmd("quoteAggBidAll"); }
    @FXML public void onAggFillAskAll()   { sendCmd("quoteAggAskAll"); }
    @FXML public void onAggFillBothUp()   { sendCmd("quoteAggBothUp"); }
    @FXML public void onAggFillBothDown() { sendCmd("quoteAggBothDown"); }
    @FXML public void onAggFillBothAll()  { sendCmd("quoteAggBothAll"); }

    // ===== Agg Clear =====
    @FXML public void onAggClearBidTop()   { sendCmd("quoteAggClearBidTop"); }
    @FXML public void onAggClearBidBot()   { sendCmd("quoteAggClearBidBot"); }
    @FXML public void onAggClearAskTop()   { sendCmd("quoteAggClearAskTop"); }
    @FXML public void onAggClearAskBot()   { sendCmd("quoteAggClearAskBot"); }
    @FXML public void onAggClearBothTop()  { sendCmd("quoteAggClearBothTop"); }
    @FXML public void onAggClearBothBot()  { sendCmd("quoteAggClearBothBot"); }
    @FXML public void onAggClearBidAll()   { sendCmd("quoteAggClearBidAll"); }
    @FXML public void onAggClearAskAll()   { sendCmd("quoteAggClearAskAll"); }
    @FXML public void onAggClearBothAll()  { sendCmd("quoteAggClearBothAll"); }

    // ===== Основные UI-элементы =====
    @FXML private TextField tfUrl;
    @FXML private ComboBox<String> cbScenario;
    @FXML private Label lbBanner;
    @FXML private TextArea taLog;

    @FXML private Button btnConnectToggle;
    @FXML private Button btnServerToggle;
    @FXML private Button btnScrollToBottom;

    // [NEW] кнопка Upd
    @FXML private Button btnSaveUpd;

    // ==== Collapsible cards ====
    @FXML private Button btnCardClean;
    @FXML private Button btnCardFill;
    @FXML private Button btnCardManual;

    @FXML private VBox contentClean;
    @FXML private VBox contentFill;
    @FXML private VBox contentManual;

    // ==== Консоль ====
    @FXML private StackPane consolePane;
    @FXML private Button   btnConsoleToggle;
    @FXML private Label    lblConsole;
    @FXML private Button   btnClearLog;

    // ====== Навигация ======
    @FXML public void goBack() { Router.get().back(); }
    @FXML public void goHome() { Router.get().home(); }

    @FXML private Button btnManualSend;

    // ===== Локальные поля =====
    private final ObjectMapper mapper = new ObjectMapper();
    private ControlClient client;

    private Path serverDir;
    private Path messagesConfigPath;

    private String lastScenarioPrinted = null;
    private boolean setScenarioSentOnce = false;
    private Timeline statusPoller;

    private final List<String> recentlyClosedClientIds = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean disconnectInProgress = false;

    private boolean scenarioAlignedPrinted = false;
    private int lastClientsActive = 0;

    private boolean logAutoStick = true;
    private boolean scenarioInitInProgress = false;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private String ts() { return LocalTime.now().format(TS_FMT); }

    private void fx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    // ===== SVG-иконки =====
    private static final String ICON_CHEVRON_DOWN  = "/icons/chevron-down.svg";
    private static final String ICON_CHEVRON_RIGHT = "/icons/chevron-right.svg";
    private static final String ICON_TRASH         = "/icons/trash.svg";
    private static final String ICON_ARROW_DOWN    = "/icons/arrow-down.svg";

    // ===== РУЧНОЙ ВВОД =====
    @FXML private ComboBox<String> cbManualSide;   // Bid / Ask / Bid_Ask
    @FXML private VBox             blockBid;
    @FXML private VBox             blockAsk;
    @FXML private TextField        tfBidPrice;
    @FXML private TextField        tfBidVolume;
    @FXML private TextField        tfAskPrice;
    @FXML private TextField        tfAskVolume;
    @FXML private CheckBox         chkEditCurrentLevel;

    @FXML
    public void initialize() {
        append("[DEBUG] initialize() start");
        btnServerToggle.getStyleClass().add("btn-server");

        resetSessionState();

        serverDir = Paths.get(System.getProperty("user.dir"), "node-server");
        messagesConfigPath = resolveMessagesConfigPath(serverDir);
        node = new NodeServerLauncher(serverDir, this::append);

        tfUrl.setPromptText("введите адрес сервера");
        tfUrl.setText(resolveDefaultUrl());
        append("[DEBUG] resolved control URL: " + tfUrl.getText());

        tfUrl.setOnAction(e -> persistControlUrl(tfUrl.getText()));
        tfUrl.focusedProperty().addListener((obs, was, is) -> { if (!is) persistControlUrl(tfUrl.getText()); });

        // ==== СЦЕНАРИИ ====
        cbScenario.getItems().clear();
        cbScenario.setPromptText("Выберите сценарий");

        // Оставляем форс default, как и было
        try {
            node.setCurrentScenario("default");
            append("[DEBUG] app start → ws-config.current_scenario=default");
        } catch (Exception e) {
            append("[DEBUG] failed to force default at app start: " + e.getMessage());
        }

        // загружаем список сценариев из локального setting.json
        loadScenariosFromLocalConfig();

        app.ui.ScrollThumbRounding.attach(taLog);

        taLog.textProperty().addListener((obs, oldV, newV) -> {
            if (newV == null || newV.isEmpty()) {
                logAutoStick = true;
                updateScrollToBottomButton(true);
            }
        });

        installLogAutoStick();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { node.stopIfRunning(); } catch (Exception ignore) {}
        }));

        updateServerToggleText();
        updateServerButtonsVisual();
        updateConnectToggleText();

        setCardState(contentClean,  true);
        setCardState(contentFill,   true);
        setCardState(contentManual, true);

        // SVG для кнопок консоли
        UiSvg.setButtonSvg(btnClearLog,       ICON_TRASH,      14, true);
        UiSvg.setButtonSvg(btnScrollToBottom, ICON_ARROW_DOWN, 14, true);

        // Консоль по умолчанию развёрнута + правильная chevron-иконка
        setConsoleState(true);

        if (cbManualSide != null && cbManualSide.getItems().isEmpty()) {
            cbManualSide.getItems().setAll("Bid", "Ask", "Bid_Ask");
            cbManualSide.setValue(null);
            cbManualSide.setPromptText("Выберите тип");
        }
        showManualModeNone();

        append("[DEBUG] initialize() done]");
    }

    private void resetSessionState() {
        lastScenarioPrinted = null;
        setScenarioSentOnce = false;
        stopStatusPoller();
        disconnectInProgress = false;
        scenarioAlignedPrinted = false;
        lastClientsActive = 0;
        scenarioInitInProgress = false;
        append("[DEBUG] resetSessionState()");
    }

    // ===== Загрузка сценариев из локального setting.json =====
    private void loadScenariosFromLocalConfig() {
        try {
            if (!Files.exists(messagesConfigPath)) {
                append("setting.json не найден, сценарии недоступны");
                return;
            }
            String json = Files.readString(messagesConfigPath, StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(json);

            JsonNode scenariosNode = root.path("scenarios");
            if (!scenariosNode.isObject()) {
                append("В setting.json нет объекта 'scenarios'");
                return;
            }

            List<String> list = new ArrayList<>();
            scenariosNode.fieldNames().forEachRemaining(list::add);

            String current = root.path("current_scenario").asText(null);

            scenarioInitInProgress = true;
            cbScenario.getItems().setAll(list);
            if (current != null && !current.isBlank() && list.contains(current)) {
                cbScenario.getSelectionModel().select(current);
            } else if (!list.isEmpty()) {
                cbScenario.getSelectionModel().select(0);
            }
            scenarioInitInProgress = false;

            append("[DEBUG] scenarios loaded from setting.json: " + list);
            if (current != null && !current.isBlank()) {
                append("[DEBUG] current_scenario from setting.json: " + current);
            }
        } catch (Exception e) {
            scenarioInitInProgress = false;
            append("Не удалось загрузить сценарии из setting.json: " + e.getMessage());
        }
    }

    // Собирает полный control URL из url + urlControlPath + urlControlParams
    private String buildControlUrlFromConfig(JsonNode n) {
        String baseUrl = n.path("url").asText("ws://localhost:8080");
        String path = n.path("urlControlPath").asText("");
        StringBuilder sb = new StringBuilder();

        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.replaceAll("/+$", "");
            sb.append(trimmed);
        }

        if (path != null && !path.isBlank()) {
            String p = path.trim();
            if (!p.startsWith("/")) p = "/" + p;
            sb.append(p);
        }

        String url = sb.toString();

        JsonNode params = n.path("urlControlParams");
        if (params.isObject() && params.size() > 0) {
            StringBuilder withParams = new StringBuilder(url);
            String prefix = url.contains("?") ? "&" : "?";

            Iterator<Map.Entry<String, JsonNode>> it = params.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                if (key == null || key.isBlank()) continue;
                String value = e.getValue().asText("");

                try {
                    withParams.append(prefix)
                            .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                            .append('=')
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                } catch (Exception ignore) {
                    // не должны сюда попасть с UTF-8
                }
                prefix = "&";
            }
            url = withParams.toString();
        }

        return url;
    }

    private String resolveDefaultUrl() {
        try {
            if (Files.exists(messagesConfigPath)) {
                String json = Files.readString(messagesConfigPath, StandardCharsets.UTF_8);
                JsonNode n = mapper.readTree(json);

                // 1) Новый формат: url + urlControlPath/urlControlParams
                boolean hasPath = n.path("urlControlPath").isTextual()
                        && !n.path("urlControlPath").asText().isBlank();
                boolean hasParams = n.path("urlControlParams").isObject()
                        && n.path("urlControlParams").size() > 0;

                if (hasPath || hasParams) {
                    String full = buildControlUrlFromConfig(n);
                    if (full != null && !full.isBlank()) return full;
                }

                // 2) Старый формат: controlUrl
                String controlUrl = n.path("controlUrl").asText(null);
                if (controlUrl != null && !controlUrl.isBlank()) return controlUrl;

                // 3) Фолбэк: просто url
                String baseUrl = n.path("url").asText(null);
                if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;
            }
        } catch (Exception e) {
            append("Не удалось прочитать setting.json: " + e.getMessage());
        }
        // По умолчанию сразу как control-сокет
        return "ws://localhost:8080/control?control=1";
    }

    private void persistControlUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) return;
        try {
            ObjectNode root;
            if (Files.exists(messagesConfigPath)) {
                String json = Files.readString(messagesConfigPath, StandardCharsets.UTF_8);
                JsonNode parsed = mapper.readTree(json);
                root = (parsed.isObject()) ? (ObjectNode) parsed : mapper.createObjectNode();
            } else {
                root = mapper.createObjectNode();
            }

            try {
                URI uri = URI.create(url);

                String scheme = uri.getScheme();
                String host   = uri.getHost();
                int    port   = uri.getPort();
                String path   = uri.getPath();
                String query  = uri.getQuery();

                String base;
                if (host != null) {
                    StringBuilder b = new StringBuilder();
                    if (scheme != null && !scheme.isBlank()) {
                        b.append(scheme).append("://");
                    } else {
                        b.append("ws://");
                    }
                    b.append(host);
                    if (port != -1) {
                        b.append(":").append(port);
                    }
                    base = b.toString();
                } else {
                    // fallback: если что-то странное — сохраняем как есть
                    base = url;
                }

                // Базовый URL сервера
                root.put("url", base);

                // Путь control-канала
                if (path != null && !path.isBlank() && !"/".equals(path)) {
                    root.put("urlControlPath", path);
                } else {
                    root.remove("urlControlPath");
                }

                // Параметры control-канала
                if (query != null && !query.isBlank()) {
                    ObjectNode params = mapper.createObjectNode();
                    String[] pairs = query.split("&");
                    for (String pair : pairs) {
                        if (pair.isEmpty()) continue;
                        String[] kv = pair.split("=", 2);
                        String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                        String v = kv.length > 1
                                ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                                : "";
                        if (!k.isBlank()) {
                            params.put(k, v);
                        }
                    }
                    root.set("urlControlParams", params);
                } else {
                    root.remove("urlControlParams");
                }
            } catch (Exception parseEx) {
                // если не распарсили URI — ведём себя почти как раньше
                root.put("url", url);
                root.remove("urlControlPath");
                root.remove("urlControlParams");
            }

            // Для обратной совместимости и логов — полный URL
            root.put("controlUrl", url);

            Files.writeString(
                    messagesConfigPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            );
            append("[DEBUG] controlUrl saved → " + url);
        } catch (Exception e) {
            showBanner("Не удалось сохранить URL: " + e.getMessage());
        }
    }

    private String configuredServerName() { return resolveConfiguredUrlForLog(); }

    // ===== Проверка порта и запуск с ожиданием =====

    private boolean isPortInUse(int port) {
        try (Socket socket = new Socket("localhost", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private int resolveControlPort() {
        String url = null;
        if (tfUrl != null) {
            url = tfUrl.getText();
        }
        if (url == null || url.isBlank()) {
            url = resolveConfiguredUrlForLog();
        }

        try {
            URI uri = URI.create(url);
            int port = uri.getPort();
            if (port > 0) {
                return port;
            }
            // Если порт не указан явно — по умолчанию 8080 (наш локальный стенд)
            return 8080;
        } catch (Exception e) {
            return 8080;
        }
    }

    /**
     * Асинхронно:
     * 1) гасит все зарегистрированные Node-сервера через AppShutdown.runAll();
     * 2) ждёт, пока control-порт освободится;
     * 3) стартует node.startIfNeeded(), если порт свободен.
     */
    private void startExchangeServerWithPortWait() {
        if (btnServerToggle != null) {
            btnServerToggle.setDisable(true);
        }

        final int port = resolveControlPort();

        Thread t = new Thread(() -> {
            final int maxAttempts = 40;    // ~4 секунды при шаге 100 мс
            final long sleepMs    = 100L;

            // 1) Гасим все наши Node-процессы
            try {
                append("[DEBUG] startExchangeServerWithPortWait(): calling AppShutdown.runAll()");
                AppShutdown.runAll();
            } catch (Exception e) {
                append("[DEBUG] AppShutdown.runAll() failed: " + e.getMessage());
            }

            // 2) Ждём, пока порт освободится
            boolean portFree = false;
            for (int i = 0; i < maxAttempts; i++) {
                if (!isPortInUse(port)) {
                    portFree = true;
                    break;
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            final boolean finalPortFree = portFree;

            // 3) Возвращаемся в FX-поток и либо стартуем сервер, либо пишем WARN
            Platform.runLater(() -> {
                try {
                    if (finalPortFree) {
                        append("[DEBUG] control port " + port + " is free, starting node...");
                        persistControlUrl(tfUrl.getText());
                        node.startIfNeeded();
                        append("Локальный сервер " + resolveConfiguredUrlForLog() + " запущен.");
                    } else {
                        append("[WARN] Порт " + port + " так и не освободился. " +
                                "Скорее всего, его занимает внешний процесс или другой WebSocket-сервер. " +
                                "Остановите его вручную и попробуйте ещё раз.");
                    }
                } catch (Exception e) {
                    append("Ошибка запуска сервера: " + e.getMessage());
                    showBanner("Ошибка запуска сервера: " + e.getMessage());
                } finally {
                    if (btnServerToggle != null) {
                        btnServerToggle.setDisable(false);
                    }
                    updateServerToggleText();
                    updateServerButtonsVisual();
                    append("[DEBUG] startExchangeServerWithPortWait(): done");
                }
            });
        }, "ws-exchange-server-port-wait");

        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onToggleServer() {
        try {
            if (node.isRunning()) {
                // ===== ВЕТКА "Server Off" =====
                append("[DEBUG] onToggleServer(): stopping...");
                fx(() -> {
                    btnServerToggle.setDisable(true);
                    btnServerToggle.setText("Start server");
                });

                CompletableFuture<Void> closeFuture = CompletableFuture.completedFuture(null);
                if (client != null && client.isOpen()) {
                    try { client.sendControl("acceptOff"); } catch (Throwable ignore) {}
                    try { client.sendControl("closeAll"); } catch (Throwable ignore) {}
                    closeFuture = client.close().orTimeout(1500, TimeUnit.MILLISECONDS);
                }

                closeFuture.whenComplete((v, ex) -> {
                    fx(() -> {
                        client = null;
                        resetSessionState();
                        updateConnectToggleText();
                    });
                    try {
                        // Гасим все Node-сервера централизованно
                        AppShutdown.runAll();
                        fx(() -> append("Локальный сервер остановлен."));
                    } catch (Exception e) {
                        fx(() -> {
                            append("Ошибка остановки сервера: " + e.getMessage());
                            showBanner("Ошибка остановки сервера: " + e.getMessage());
                        });
                    } finally {
                        fx(() -> {
                            btnServerToggle.setDisable(false);
                            updateServerToggleText();
                            updateServerButtonsVisual();
                        });
                        append("[DEBUG] onToggleServer(): stop complete");
                    }
                });
            } else {
                // ===== ВЕТКА "Start server" =====
                append("[DEBUG] onToggleServer(): starting (async with port wait)...");
                // Стартуем асинхронный процесс: AppShutdown + ожидание порта + node.startIfNeeded()
                startExchangeServerWithPortWait();
            }
        } catch (Exception e) {
            fx(() -> {
                append("Ошибка управления сервером: " + e.getMessage());
                showBanner("Ошибка управления сервером: " + e.getMessage());
            });
        }
    }

    private void updateServerToggleText() {
        fx(() -> {
            if (btnServerToggle != null) {
                btnServerToggle.setText(node != null && node.isRunning() ? "Server Off" : "Start server");
            }
        });
    }

    private void updateServerButtonsVisual() {
        fx(() -> {
            boolean running = node != null && node.isRunning();
            toggleClass(btnConnectToggle, "ws-inactive", !running);
            append("[DEBUG] updateServerButtonsVisual(): running=" + running);
        });
    }

    private static void toggleClass(Control c, String cls, boolean add) {
        if (c == null) return;
        var sc = c.getStyleClass();
        if (add) { if (!sc.contains(cls)) sc.add(cls); }
        else sc.remove(cls);
    }

    @FXML
    public void onToggleConnect() {
        if (client != null && client.isOpen()) onDisconnect();
        else onConnect();
    }

    private void updateConnectToggleText() {
        fx(() -> {
            String text = (client != null && client.isOpen()) ? "Disconnect" : "Connect";
            if (btnConnectToggle != null) btnConnectToggle.setText(text);
        });
    }

    private void onConnect() {
        append("[DEBUG] onConnect()");
        if (node == null || !node.isRunning()) {
            String msg = "Сначала запустите сервер (Start server).";
            append(msg);
            showBanner(msg);
            return;
        }
        if (client != null && client.isOpen()) {
            append("[DEBUG] onConnect(): already open, skip");
            return;
        }

        recentlyClosedClientIds.clear();
        resetSessionState();

        persistControlUrl(tfUrl.getText());

        final String url = tfUrl.getText();

        // Пытаемся подгрузить split-протокол
        WsProtocol proto = tryLoadSplitProtocol(serverDir);
        client = (proto != null)
                ? new ControlClient(proto, this::onWsMessage)
                : new ControlClient(this::onWsMessage);

        client.connect(url)
                .thenRun(() -> {
                    append("[DEBUG] control connected");
                    if (proto == null) {
                        append("[DEBUG] sending ControlHello/GetConfig/GetStatus + acceptOn (legacy)");
                        client.sendRaw("{\"t\":\"ControlHello\"}");
                        client.sendGetConfig();
                        client.sendGetStatus();
                    }
                    client.sendControl("acceptOn");
                    fx(() -> {
                        append("Подключение к серверу: " + configuredServerName() + " установлено");
                        startStatusPoller();
                        hideBanner();
                        updateConnectToggleText();
                    });
                })
                .exceptionally(ex -> {
                    showBanner("Не удалось подключиться: " + ex.getMessage());
                    return null;
                });
    }

    private void onDisconnect() {
        append("[DEBUG] onDisconnect()");
        if (node == null || !node.isRunning()) {
            String msg = "Сервер выключен. Сначала запустите сервер (Start server).";
            append(msg);
            showBanner(msg);
            return;
        }

        disconnectInProgress = true;

        if (client != null && client.isOpen()) {
            try { client.sendControl("acceptOff"); } catch (Throwable ignore) {}
            try { client.sendControl("closeUsers"); } catch (Throwable ignore) {}
        }
        var cf = (client != null) ? client.closeAllAndWait() : CompletableFuture.completedFuture(null);
        cf.orTimeout(2500, TimeUnit.MILLISECONDS)
                .whenComplete((v, ex) -> fx(() -> {
                    if (disconnectInProgress) {
                        append("Соединение с сервером " + configuredServerName() + " завершено.");
                    }
                    client = null;
                    resetSessionState();
                    disconnectInProgress = false;
                    updateConnectToggleText();
                }));
    }

    private void startStatusPoller() {
        stopStatusPoller();
        statusPoller = new Timeline(new KeyFrame(Duration.millis(1500), e -> {
            if (client != null && client.isOpen()) client.sendGetStatus();
        }));
        statusPoller.setCycleCount(Timeline.INDEFINITE);
        statusPoller.play();
        append("[DEBUG] startStatusPoller()");
    }
    private void stopStatusPoller() {
        if (statusPoller != null) {
            statusPoller.stop();
            statusPoller = null;
            append("[DEBUG] stopStatusPoller()");
        }
    }

    // ===== РУЧНОЙ ВВОД: отправка =====
    @FXML
    public void onManualSend() {
        try {
            if (client == null || !client.isOpen()) {
                showBanner("Нет соединения с сервером. Нажмите Connect.");
                return;
            }
            if (cbManualSide == null) {
                showBanner("Не выбран тип заявки (Bid / Ask / Bid_Ask).");
                return;
            }
            String side = cbManualSide.getValue();
            if (side == null || side.isBlank()) {
                showBanner("Не выбран тип заявки (Bid / Ask / Bid_Ask).");
                return;
            }

            List<Map<String, Object>> ops = new ArrayList<>();

            if ("Bid".equals(side) || "Bid_Ask".equals(side)) {
                double p = parseDouble(tfBidPrice, "Bid/Price");
                long   v = parseLong(tfBidVolume, "Bid/Volume");
                if (p > 0 && v > 0) {
                    Map<String, Object> op = new HashMap<>();
                    op.put("side", "bid");
                    op.put("price", p);
                    op.put("volume", v);
                    ops.add(op);
                }
            }
            if ("Ask".equals(side) || "Bid_Ask".equals(side)) {
                double p = parseDouble(tfAskPrice, "Ask/Price");
                long   v = parseLong(tfAskVolume, "Ask/Volume");
                if (p > 0 && v > 0) {
                    Map<String, Object> op = new HashMap<>();
                    op.put("side", "ask");
                    op.put("price", p);
                    op.put("volume", v);
                    ops.add(op);
                }
            }

            boolean replaceCurrent = (chkEditCurrentLevel != null && chkEditCurrentLevel.isSelected());

            if (ops.isEmpty()) {
                showBanner("Нет данных для отправки");
                return;
            }

            client.sendManualQuote(ops, replaceCurrent);
            append("> manualQuote sent with " + ops.size() + " operations");
            hideBanner();
        } catch (Exception ex) {
            showBanner("Ошибка отправки: " + ex.getMessage());
        }
    }

    private double parseDouble(TextField tf, String name) {
        try {
            String s = tf == null ? null : tf.getText();
            double v = (s == null) ? 0 : Double.parseDouble(s.trim().replace(',', '.'));
            if (v <= 0) { showBanner("Неверное значение: " + name); }
            return v;
        } catch (Exception e) {
            showBanner("Неверный формат: " + name);
            return 0;
        }
    }
    private long parseLong(TextField tf, String name) {
        try {
            String s = tf == null ? null : tf.getText();
            long v = (s == null) ? 0 : Long.parseLong(s.trim());
            if (v <= 0) { showBanner("Неверное значение: " + name); }
            return v;
        } catch (Exception e) {
            showBanner("Неверный формат: " + name);
            return 0;
        }
    }

    // ===== Изменение сценария =====
    @FXML
    public void onScenarioChanged() {
        // не дёргаем сервер во время первичной загрузки сценариев из setting.json
        if (scenarioInitInProgress) {
            return;
        }

        final String desired = cbScenario.getValue();
        if (desired == null || desired.isBlank()) return;

        append("[DEBUG] onScenarioChanged(): desired=" + desired);

        try {
            if (node != null) node.setCurrentScenario(desired);
            append("[DEBUG] setting.json patched with scenario=" + desired);
        } catch (Exception e) {
            append("Не удалось сохранить кейс в setting.json: " + e.getMessage());
        }

        final boolean running   = (node != null && node.isRunning());
        final boolean connected = (client != null && client.isOpen());

        if (!running) {
            append("Кейс изменен на " + desired);
            append("[DEBUG] scenario change logged (server not running)");
            return;
        }
        if (!connected) {
            append("Кейс изменен на " + desired);
            append("[DEBUG] scenario change logged (control not connected)");
            return;
        }

        append("[DEBUG] sending SetScenario=" + desired + " (control connected)");
        client.setScenario(desired);
        append("Кейс изменен на " + desired);
        updateConnectToggleText();
    }

    // ====== Кнопка Upd → серверу: сохранить текущий стакан как SubscribeResp_upd ======
    @FXML
    public void onSaveUpd() {
        if (client == null || !client.isOpen()) {
            showBanner("Нет соединения с сервером. Нажмите Connect.");
            return;
        }
        client.sendControl("saveSubscribeUpd");
        append("> saveSubscribeUpd");
        showBanner("Текущее состояние стакана сохранено в SubscribeResp_upd");
    }

    // ===== Пульт: базовые команды =====
    private void sendCmd(String c) {
        if (client != null) client.sendControl(c);
        append("> " + c);
        append("[DEBUG] control cmd sent: " + c);
    }
    @FXML public void onClearBid()  { sendCmd("quoteClearBid"); }
    @FXML public void onClearAsk()  { sendCmd("quoteClearAsk"); }
    @FXML public void onClearAll()  { sendCmd("quoteClearAll"); }
    @FXML public void onBidUp()     { sendCmd("quoteAddBidTop1"); }
    @FXML public void onBidDown()   { sendCmd("quoteAddBidBottom1"); }
    @FXML public void onAskUp()     { sendCmd("quoteAddAskTop1"); }
    @FXML public void onAskDown()   { sendCmd("quoteAddAskBottom1"); }
    @FXML public void onBothUp()    { sendCmd("quoteAddBothTop1"); }
    @FXML public void onBothDown()  { sendCmd("quoteAddBothBottom1"); }
    @FXML public void onAddBoth()   { sendCmd("quoteAddBoth"); }
    @FXML public void onClearBidUp()    { sendCmd("quoteDelBidTop1"); }
    @FXML public void onClearBidDown()  { sendCmd("quoteDelBidBottom1"); }
    @FXML public void onClearAskUp()    { sendCmd("quoteDelAskTop1"); }
    @FXML public void onClearAskDown()  { sendCmd("quoteDelAskBottom1"); }
    @FXML public void onClearBothUp()   { sendCmd("quoteDelBothTop1"); }
    @FXML public void onClearBothDown() { sendCmd("quoteDelBothBottom1"); }
    @FXML public void onOneShot()   { sendCmd("quoteUpdate"); }

    @FXML public void onOpenConfig() {
        try {
            Path p = messagesConfigPath;
            if (!Files.exists(p)) {
                showBanner("Файл не найден: " + p.toAbsolutePath());
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(p.toFile());
                append("[DEBUG] opening setting.json in OS]");
            } else {
                showBanner("Операция не поддерживается на этой платформе");
            }
        } catch (IOException e) {
            showBanner("Не удалось открыть файл: " + e.getMessage());
        }
    }

    private Path resolveMessagesConfigPath(Path serverDir) {
        return serverDir.resolve("setting").resolve("setting.json");
    }

    private WsProtocol tryLoadSplitProtocol(Path serverDir) {
        try {
            Path settings = serverDir.resolve("setting");

            // system-файл обязателен
            Path protoSystem = settings
                    .resolve("private")
                    .resolve("protocol")
                    .resolve("priv_client-system-protocol.json");

            // project-файл опционален (JsonSplitProtocol сам обработает)
            if (Files.exists(protoSystem)) {
                Map<String, Object> defaults = Map.of(
                        "clientId", UUID.randomUUID().toString().substring(0, 8),
                        "instrument", "default_instrument"
                );
                return new JsonSplitProtocol(settings, defaults);
            } else {
                append("[WARN] split protocol system file missing, fallback to legacy");
            }
        } catch (Throwable e) {
            append("[WARN] Failed to load split protocol: " + e.getMessage());
        }
        return null;
    }

    @FXML public void toggleCardClean()  { toggleCard(contentClean); }
    @FXML public void toggleCardFill()   { toggleCard(contentFill); }
    @FXML public void toggleCardManual() { toggleCard(contentManual); }

    private void toggleCard(VBox content) {
        if (content == null) return;
        boolean expanded = !content.isVisible();
        setCardState(content, expanded);
    }

    private void setCardState(VBox content, boolean expanded) {
        content.setVisible(expanded);
        content.setManaged(expanded);

        if (content.getParent() instanceof VBox section && section.getParent() instanceof HBox) {
            HBox.setHgrow(section, expanded ? Priority.ALWAYS : Priority.NEVER);
            section.setMinWidth(Region.USE_COMPUTED_SIZE);
            section.setPrefWidth(Region.USE_COMPUTED_SIZE);
            section.setMaxWidth(Region.USE_COMPUTED_SIZE);
        }
    }

    private void setConsoleState(boolean expanded) {
        if (consolePane != null) {
            consolePane.setVisible(expanded);
            consolePane.setManaged(expanded);
        }
        UiSvg.setButtonSvg(
                btnConsoleToggle,
                expanded ? ICON_CHEVRON_DOWN : ICON_CHEVRON_RIGHT,
                14, true
        );
        if (lblConsole != null) {
            lblConsole.setOpacity(expanded ? 1.0 : 0.45);
        }
    }

    @FXML
    public void toggleConsole() {
        boolean expanded = consolePane != null && consolePane.isVisible();
        setConsoleState(!expanded);
    }

    @FXML
    public void onManualSideChanged() {
        if (cbManualSide == null) return;
        String v = cbManualSide.getValue();
        if (v == null || v.isBlank()) {
            showManualModeNone();
            return;
        }
        switch (v) {
            case "Bid"     -> showManualModeSingle("Bid");
            case "Ask"     -> showManualModeSingle("Ask");
            case "Bid_Ask" -> showManualModeBoth();
            default        -> showManualModeNone();
        }
        setNodeVisible(btnManualSend, true);
    }

    // === ЛОГ/КОНСОЛЬ ===
    @FXML
    public void onClearLog() {
        if (taLog != null) {
            taLog.clear();
        }
        updateScrollToBottomButton(true);
    }

    @FXML
    public void onScrollToBottom() {
        ScrollPane sp = getLogScrollPane();
        if (sp != null) {
            Platform.runLater(() -> sp.setVvalue(1.0));
        }
        updateScrollToBottomButton(true);
    }

    @FXML
    public void onScrollLogToBottom() {
        onScrollToBottom();
    }

    private void onWsMessage(String raw) {
        fx(() -> {
            try {
                String s = raw == null ? "" : raw.trim();
                if (s.isEmpty() || (s.charAt(0) != '{' && s.charAt(0) != '[')) {
                    append(s);
                    return;
                }

                JsonNode n = mapper.readTree(s);
                String t = n.path("t").asText();
                switch (t) {
                    case "Status" -> {
                        final String serverScn = n.path("scenario").asText(null);
                        lastClientsActive = n.path("clientsActive").asInt(0);
                        append("[DEBUG] Status: scenario=" + serverScn + ", clientsActive=" + lastClientsActive);
                    }
                    case "Config" -> {
                        // сценарии теперь берём только из setting.json, Config — чисто для логов
                        append("[DEBUG] Config received (scenarios managed from setting.json)");
                    }
                    case "ScenarioSet" -> {
                        final String applied = n.path("scenario").asText(null);
                        append("[DEBUG] ScenarioSet received: " + applied);
                        updateConnectToggleText();
                    }
                    case "ClientsClosed" -> {
                        append("[DEBUG] ClientsClosed received");
                        recentlyClosedClientIds.clear();
                        if (n.has("clients") && n.get("clients").isArray()) {
                            for (JsonNode id : n.get("clients")) {
                                recentlyClosedClientIds.add(id.asText());
                            }
                        }
                    }
                    case "Log" -> {
                        String m = n.path("message").asText(null);
                        if (m != null) append(m);
                    }
                    case "Error" -> showBanner("Ошибка: " + n.path("message").asText());
                    default -> { /* ignore */ }
                }
            } catch (Exception ex) {
                append("[DEBUG] onWsMessage parse error: " + ex.getMessage());
            }
        });
    }

    private void showBanner(String text) {
        if (lbBanner != null) { lbBanner.setText(text); lbBanner.setVisible(true); lbBanner.setManaged(true); }
    }
    private void hideBanner() {
        if (lbBanner != null) { lbBanner.setVisible(false); lbBanner.setManaged(false); }
    }

    private ScrollPane getLogScrollPane() {
        var n = taLog.lookup(".scroll-pane");
        return (n instanceof ScrollPane sp) ? sp : null;
    }

    private void updateScrollToBottomButton(boolean atBottom) {
        boolean shouldShow = !atBottom;
        if (btnScrollToBottom != null) {
            btnScrollToBottom.setVisible(shouldShow);
            btnScrollToBottom.setManaged(shouldShow);
        }
    }

    private void installLogAutoStick() {
        Platform.runLater(() -> {
            ScrollPane sp = getLogScrollPane();
            if (sp == null) return;

            logAutoStick = true;
            updateScrollToBottomButton(true);

            sp.vvalueProperty().addListener((obs, oldV, newV) -> {
                double v = newV.doubleValue();
                boolean atBottom = v >= 0.98;
                logAutoStick = atBottom;
                updateScrollToBottomButton(atBottom);
            });
        });
    }

    private void append(String text) {
        fx(() -> {
            final ScrollPane sp = getLogScrollPane();
            final String line = "[" + ts() + "] " + text;

            final Double vBefore = (sp != null) ? sp.getVvalue() : null;
            final int caretBefore = taLog.getCaretPosition();
            final int anchorBefore = taLog.getAnchor();
            final double scrollTopBefore = taLog.getScrollTop();

            taLog.appendText(line + System.lineSeparator());

            if (sp == null) return;

            if (logAutoStick) {
                Platform.runLater(() -> sp.setVvalue(1.0));
            } else {
                taLog.positionCaret(caretBefore);
                taLog.selectRange(anchorBefore, caretBefore);
                taLog.setScrollTop(scrollTopBefore);
                if (vBefore != null) {
                    Platform.runLater(() -> {
                        sp.setVvalue(vBefore);
                        Platform.runLater(() -> sp.setVvalue(vBefore));
                    });
                }
            }
            updateScrollToBottomButton(logAutoStick);
        });
    }

    private String resolveConfiguredUrlForLog() {
        try {
            if (Files.exists(messagesConfigPath)) {
                String json = Files.readString(messagesConfigPath, StandardCharsets.UTF_8);
                JsonNode n = mapper.readTree(json);

                boolean hasPath = n.path("urlControlPath").isTextual()
                        && !n.path("urlControlPath").asText().isBlank();
                boolean hasParams = n.path("urlControlParams").isObject()
                        && n.path("urlControlParams").size() > 0;

                if (hasPath || hasParams) {
                    String full = buildControlUrlFromConfig(n);
                    if (full != null && !full.isBlank()) return full;
                }

                String controlUrl = n.path("controlUrl").asText(null);
                if (controlUrl != null && !controlUrl.isBlank()) return controlUrl;

                String baseUrl = n.path("url").asText(null);
                if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;
            }
        } catch (Exception ignore) { }
        return "ws://localhost:8080/control?control=1";
    }

    private void showManualModeNone() {
        setNodeVisible(blockBid, false);
        setNodeVisible(blockAsk, false);
        if (tfBidPrice  != null) tfBidPrice.clear();
        if (tfBidVolume != null) tfBidVolume.clear();
        if (tfAskPrice  != null) tfAskPrice.clear();
        if (tfAskVolume != null) tfAskVolume.clear();
    }

    private void showManualModeSingle(String side) {
        boolean bid = "Bid".equals(side);
        setNodeVisible(blockBid, bid);
        setNodeVisible(blockAsk, !bid);

        if (bid) {
            if (tfAskPrice  != null) tfAskPrice.clear();
            if (tfAskVolume != null) tfAskVolume.clear();
        } else {
            if (tfBidPrice  != null) tfBidPrice.clear();
            if (tfBidVolume != null) tfBidVolume.clear();
        }
    }

    private void showManualModeBoth() {
        setNodeVisible(blockBid, true);
        setNodeVisible(blockAsk, true);
    }

    private void setNodeVisible(Node n, boolean visible) {
        if (n == null) return;
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
