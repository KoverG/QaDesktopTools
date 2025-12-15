// FILE: src/main/java/app/products/websocketmanual/WebSocketManualController.java
package app.products.websocketmanual;

import app.core.Router;
import app.ui.UiSvg;
import app.ui.ScrollThumbRounding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.core.node.AppShutdown;
import app.core.node.NodeServerLauncher;

public class WebSocketManualController {

    // ===== Верхняя панель =====
    @FXML private TextField txtUrl;
    @FXML private Button   btnUrlEdit;
    @FXML private Button   btnOpenConfig;
    @FXML private Button   btnServerToggle;
    @FXML private Button   btnConnectToggle;
    @FXML private Button   btnClientCount; // индикатор (теперь ВСЕГДА graphic + tooltip)

    // ===== Центр: входящие сообщения и исходящий редактор =====
    @FXML private TextArea txtOutgoing;
    @FXML private Button   btnOpenTemplates;
    @FXML private Button   btnSend;

    // NEW: чекбокс "отправлять всем"
    @FXML private CheckBox chkSendToAll;

    // комбобоксы
    @FXML private ComboBox<String> cbClient;       // правый комбобокс (для отправки)
    @FXML private ComboBox<String> cbTemplates;
    @FXML private ComboBox<String> cbClientLeft;   // левый комбобокс (фильтр входящих)

    // список входящих сообщений (левая панель)
    @FXML private ListView<String> listIncoming;

    // список исходящих сообщений сервера (правая панель)
    @FXML private ListView<String> listOutgoingLog;
    @FXML private Button btnSendPing;

    // Кнопки управления полем исходящего сообщения / списком
    @FXML private Button   btnCollapseOutgoing;
    @FXML private Button   btnClearOutgoing;

    // NEW: сохранить текущий outgoing в templates.json
    @FXML private Button   btnSaveTemplate;

    @FXML private Button   btnScrollOutgoing;
    @FXML private Button   btnScrollIncoming;      // прокрутка списка входящих вниз
    @FXML private Button   btnScrollOutgoingLog;   // прокрутка лога исходящих вниз

    // ===== Консоль (как в WebSocket.fxml) =====
    @FXML private Button   btnConsoleToggle;
    @FXML private Label    lblConsole;
    @FXML private StackPane consolePane;
    @FXML private TextArea taLog;
    @FXML private Button   btnClearLog;
    @FXML private Button   btnScrollToBottom;

    // ===== SVG-иконки =====
    private static final String ICON_CHEVRON_DOWN   = "/icons/chevron-down.svg";
    private static final String ICON_CHEVRON_RIGHT  = "/icons/chevron-right.svg";
    private static final String ICON_TRASH          = "/icons/trash.svg";
    private static final String ICON_ARROW_DOWN     = "/icons/arrow-down.svg";
    private static final String ICON_PING           = "/icons/ping.svg";
    private static final String ICON_SAVE           = "/icons/save.svg";

    // иконка для индикатора клиентов при count=0
    private static final String ICON_CLIENTS_EMPTY  = "/icons/clients-empty.svg";

    // ===== Настройки WebSocketManual (из app-settings.json) =====
    private static final int  DEFAULT_OUTGOING_EXPANDED_ROWS   = 3;
    private static final int  DEFAULT_OUTGOING_COLLAPSED_ROWS  = 1;
    private static final boolean DEFAULT_OUTGOING_START_COLLAPSED = false;
    private static final int  DEFAULT_MANUAL_PORT = 8080;
    private static final int TEMPLATE_PREVIEW_LEN = 50;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private int     outgoingExpandedRows   = DEFAULT_OUTGOING_EXPANDED_ROWS;
    private int     outgoingCollapsedRows  = DEFAULT_OUTGOING_COLLAPSED_ROWS;
    private boolean outgoingStartCollapsed = DEFAULT_OUTGOING_START_COLLAPSED;

    // Текущий порт manual-сервера
    private int manualPort = DEFAULT_MANUAL_PORT;

    // ===== Состояние UI / клиентов =====
    private final List<String> clientIds = new ArrayList<>();
    private final Map<String, List<String>> incomingByClient = new HashMap<>();
    private final List<String> outgoingMessages = new ArrayList<>();

    private boolean serverRunning    = false;
    private boolean clientConnected  = false;
    private boolean urlEditMode      = false;

    private boolean outgoingCollapsed = false; // поле исходящего сообщения свёрнуто/развёрнуто

    // контроллеры комбобоксов
    private ManualComboController clientComboController;      // правый
    private ManualComboController clientComboLeftController;  // левый
    private ManualComboController templatesComboController;

    // ===== Node-сервер WebSocketManual =====
    private NodeServerLauncher manualNode;
    private Path manualServerDir;
    private Path manualStateFile;

    // общий mapper для настроек и clients.json
    private final ObjectMapper mapper = new ObjectMapper();

    // ===== НОВОЕ: индикатор клиентов ВСЕГДА через graphic =====
    private StackPane clientCountGraphic;
    private Label clientCountLabel;
    private Node clientCountEmptyIcon;

    // ===== НОВОЕ: templates.json (outgoing templates) =====
    private Path templatesFile;
    private final List<String> templates = new ArrayList<>();

    private WatchService templatesWatch;
    private Thread templatesWatchThread;
    private volatile boolean templatesWatchRunning = false;

    // ===== Инициализация =====
    @FXML
    public void initialize() {
        loadWebSocketManualSettings();
        initManualNodeLauncher();
        clearClientsJsonOnStartup();

        manualPort = readManualPortFromConfig();
        txtUrl.setText("ws://localhost:" + manualPort);

        if (isPortInUse(manualPort)) {
            logInfo("Порт " + manualPort + " уже занят. Возможно, запущен основной WebSocket-сервер.");
        }

        reloadClientsFromJson();
        updateServerButtonsUi();

        if (cbClient != null) {
            clientComboController = new ManualComboController(cbClient, () -> clientIds);
            clientComboController.refresh();
        }

        // NEW: реакция на чекбокс "всем" (не ломает остальное)
        if (chkSendToAll != null) {
            chkSendToAll.setSelected(false);
            chkSendToAll.selectedProperty().addListener((obs, oldV, newV) -> {
                if (cbClient != null) {
                    cbClient.setDisable(Boolean.TRUE.equals(newV));
                }
                updateSendButtonState();
            });
            // на старте тоже применим (на всякий)
            if (cbClient != null) {
                cbClient.setDisable(chkSendToAll.isSelected());
            }
        }

        if (cbClientLeft != null) {
            clientComboLeftController = new ManualComboController(cbClientLeft, () -> clientIds);
            clientComboLeftController.refresh();

            cbClientLeft.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
                refreshIncomingListView();
            });
        }

        // ===== Templates: загрузка + отображение (20 символов) + hot reload =====
        if (cbTemplates != null) {
            initTemplatesFilePath();
            loadTemplatesFromJson();

            templatesComboController = new ManualComboController(cbTemplates, () -> templates);
            templatesComboController.refresh();

            installTemplatesCellFactory();

            cbTemplates.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
                if (newV != null && txtOutgoing != null) {
                    txtOutgoing.setText(newV);
                    txtOutgoing.positionCaret(newV.length());
                }
            });

            startTemplatesWatcher();
        }

        setUrlEditable(false);
        UiSvg.setButtonSvg(btnUrlEdit, "/icons/edit.svg", 18, true);
        UiSvg.setButtonSvg(btnOpenTemplates, "/icons/folder-exe.svg", 16, true);

        UiSvg.setButtonSvg(btnClearLog,       ICON_TRASH,      14, true);
        UiSvg.setButtonSvg(btnScrollToBottom, ICON_ARROW_DOWN, 14, true);

        setConsoleState(true);

        btnOpenTemplates.setPickOnBounds(true);
        if (btnOpenTemplates.getGraphic() != null) {
            btnOpenTemplates.getGraphic().setMouseTransparent(true);
        }

        if (btnSaveTemplate != null) {
            UiSvg.setButtonSvg(btnSaveTemplate, ICON_SAVE, 14, true);
        }
        if (btnClearOutgoing != null) {
            UiSvg.setButtonSvg(btnClearOutgoing, ICON_TRASH, 14, true);
        }
        if (btnSendPing != null) {
            UiSvg.setButtonSvg(btnSendPing, ICON_PING, 22, true);
        }


        if (btnScrollOutgoing != null) {
            UiSvg.setButtonSvg(btnScrollOutgoing, ICON_ARROW_DOWN, 14, true);
            btnScrollOutgoing.setVisible(false);
            btnScrollOutgoing.setManaged(false);
        }

        if (btnScrollIncoming != null) {
            UiSvg.setButtonSvg(btnScrollIncoming, ICON_ARROW_DOWN, 14, true);
            btnScrollIncoming.setVisible(false);
            btnScrollIncoming.setManaged(false);
        }

        if (btnScrollOutgoingLog != null) {
            UiSvg.setButtonSvg(btnScrollOutgoingLog, ICON_ARROW_DOWN, 14, true);
            btnScrollOutgoingLog.setVisible(false);
            btnScrollOutgoingLog.setManaged(false);
        }

        ScrollThumbRounding.attach(txtOutgoing);
        initOutgoingScrollWatcher();

        Platform.runLater(this::applyOutgoingInitialStateFromSettings);

        // ===== ВАЖНО: графика индикатора клиентов готовится один раз =====
        initClientCountIndicatorGraphic();
        updateClientCountLabel();

        logInfo("WebSocket Manual: UI инициализирован.");
    }

    // ===== индикатор клиентов: графика один раз =====
    private void initClientCountIndicatorGraphic() {
        if (btnClientCount == null) return;
        if (clientCountGraphic != null) return;

        // Создаем контейнер для графики (иконка или текст)
        clientCountGraphic = new StackPane();
        clientCountGraphic.setAlignment(Pos.CENTER);

        // Лейбл для цифры (это НЕ text кнопки, это текст внутри graphic)
        clientCountLabel = new Label("");
        clientCountLabel.setAlignment(Pos.CENTER);
        clientCountLabel.setMouseTransparent(true); // чтобы клики шли в кнопку
        clientCountLabel.getStyleClass().add("chip-indicator-label");

        // Иконку создаем через UiSvg на ВРЕМЕННОЙ кнопке, чтобы не трогать btnClientCount
        Button tmp = new Button();
        UiSvg.setButtonSvg(tmp, ICON_CLIENTS_EMPTY, 14, true);
        clientCountEmptyIcon = tmp.getGraphic();
        if (clientCountEmptyIcon != null) {
            clientCountEmptyIcon.setMouseTransparent(true);
        }

        // Собираем: иконка + label (потом будем включать/выключать visible)
        if (clientCountEmptyIcon != null) clientCountGraphic.getChildren().add(clientCountEmptyIcon);
        clientCountGraphic.getChildren().add(clientCountLabel);

        // Кнопка: всегда только graphic
        btnClientCount.setText("");
        btnClientCount.setGraphic(clientCountGraphic);
        btnClientCount.setGraphicTextGap(0);
        btnClientCount.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    private void initManualNodeLauncher() {
        if (manualNode != null) return;

        manualServerDir = Paths.get(System.getProperty("user.dir"), "node-server-manual");
        manualStateFile = manualServerDir.resolve("clients.json");

        manualNode = new NodeServerLauncher(manualServerDir, this::onNodeLogLine);

        // ВАЖНО: инициализируем графику до первого update
        initClientCountIndicatorGraphic();
        updateClientCountLabel();
    }

    private void onNodeLogLine(String line) {
        String time = LocalTime.now().format(TIME_FMT);
        String withTime = time + " " + line;

        appendToLog(withTime);

        if (!line.startsWith("[MANUAL]")) return;

        if (line.startsWith("[MANUAL] CLIENT_CONNECTED")) {
            String id = extractId(line);
            if (id != null && !id.isBlank()) {
                onClientConnected(id);
                reloadClientsFromJson();
            }
        } else if (line.startsWith("[MANUAL] CLIENT_DISCONNECTED")) {
            String id = extractId(line);
            if (id != null && !id.isBlank()) {
                onClientDisconnected(id);
                reloadClientsFromJson();
            }
        } else if (line.startsWith("[MANUAL] CLIENT_MESSAGE")) {
            String id = extractId(line);
            String payload = extractPayload(line);
            if (id != null && payload != null) {
                onClientMessage(id, payload);
            }
        } else if (line.startsWith("[MANUAL] CLIENT_PONG")) {
            String id = extractId(line);
            String payload = extractPayload(line);
            if (id != null && payload != null) {
                // пойдёт в incomingPane ровно как обычное сообщение
                onClientMessage(id, payload);
            }
        } else if (line.startsWith("[MANUAL] SERVER_SEND")) {
            String id = extractId(line);
            String payload = extractPayload(line);
            if (id != null && payload != null) {
                logOutgoing("[" + id + "] " + payload);
            }
        }
    }

    private String extractId(String line) {
        int idx = line.indexOf("id=");
        if (idx < 0) return null;
        int start = idx + 3;
        int end = line.indexOf(' ', start);
        if (end < 0) end = line.length();
        return line.substring(start, end).trim();
    }

    private String extractPayload(String line) {
        int idx = line.indexOf("payload=");
        if (idx < 0) return null;
        return line.substring(idx + "payload=".length()).trim();
    }

    private void loadWebSocketManualSettings() {
        try (InputStream is = getClass().getResourceAsStream("/config/app-settings.json")) {
            if (is == null) {
                System.err.println("[WARN] app-settings.json not found, using defaults for WebSocketManual.");
                return;
            }

            JsonNode root = mapper.readTree(is);
            JsonNode wsManual = root.path("WebSocketManual");
            JsonNode outgoing = wsManual.path("outgoing");

            if (outgoing.isObject()) {
                int expanded = outgoing.path("prefRowCountExpanded")
                        .asInt(DEFAULT_OUTGOING_EXPANDED_ROWS);
                if (expanded > 0) outgoingExpandedRows = expanded;

                int collapsed = outgoing.path("prefRowCountCollapsed")
                        .asInt(DEFAULT_OUTGOING_COLLAPSED_ROWS);
                if (collapsed > 0) outgoingCollapsedRows = collapsed;

                outgoingStartCollapsed = outgoing.path("startCollapsed")
                        .asBoolean(DEFAULT_OUTGOING_START_COLLAPSED);
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to read app-settings.json: " + e.getMessage());
        }
    }

    private Path getManualSettingFile() {
        if (manualServerDir == null) {
            manualServerDir = Paths.get(System.getProperty("user.dir"), "node-server-manual");
        }
        return manualServerDir.resolve("setting").resolve("setting.json");
    }

    private int readManualPortFromConfig() {
        Path settingFile = getManualSettingFile();
        try {
            if (!Files.exists(settingFile)) {
                Files.createDirectories(settingFile.getParent());
                ObjectNode root = mapper.createObjectNode();
                root.put("port", DEFAULT_MANUAL_PORT);
                mapper.writerWithDefaultPrettyPrinter().writeValue(settingFile.toFile(), root);
                return DEFAULT_MANUAL_PORT;
            }
            JsonNode root = mapper.readTree(settingFile.toFile());
            int p = root.path("port").asInt(DEFAULT_MANUAL_PORT);
            return p > 0 ? p : DEFAULT_MANUAL_PORT;
        } catch (Exception e) {
            logWarn("Не удалось прочитать порт из setting.json, используется дефолт: " + e.getMessage());
            return DEFAULT_MANUAL_PORT;
        }
    }

    private void updateManualPortInConfig(int newPort) {
        if (newPort <= 0) return;
        Path settingFile = getManualSettingFile();
        try {
            Files.createDirectories(settingFile.getParent());
            ObjectNode root;
            if (Files.exists(settingFile)) {
                JsonNode existing = mapper.readTree(settingFile.toFile());
                if (existing instanceof ObjectNode) {
                    root = (ObjectNode) existing;
                } else {
                    root = mapper.createObjectNode();
                }
            } else {
                root = mapper.createObjectNode();
            }
            root.put("port", newPort);
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingFile.toFile(), root);
            manualPort = newPort;
            logInfo("Порт сервера сохранён в setting.json: " + newPort);
        } catch (Exception e) {
            logWarn("Не удалось сохранить порт в setting.json: " + e.getMessage());
        }
    }

    private void applyPortFromUrlIfPossible(String url) {
        if (url == null || url.isBlank()) return;
        try {
            URI uri = new URI(url);
            int port = uri.getPort();
            if (port > 0) {
                updateManualPortInConfig(port);
            }
        } catch (Exception e) {
            logWarn("Не удалось разобрать порт из URL: " + e.getMessage());
        }
    }

    private boolean isPortInUse(int port) {
        try (Socket socket = new Socket("localhost", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void updateOutgoingTextAreaHeight(int rows) {
        if (txtOutgoing == null) return;

        txtOutgoing.setPrefRowCount(rows);
        txtOutgoing.setMinHeight(Region.USE_PREF_SIZE);
        txtOutgoing.setPrefHeight(Region.USE_COMPUTED_SIZE);
        txtOutgoing.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private void applyOutgoingInitialStateFromSettings() {
        if (txtOutgoing == null || btnCollapseOutgoing == null) {
            return;
        }

        if (outgoingStartCollapsed) {
            outgoingCollapsed = true;
            updateOutgoingTextAreaHeight(outgoingCollapsedRows);
            UiSvg.setButtonSvg(btnCollapseOutgoing, ICON_CHEVRON_RIGHT, 14, true);
        } else {
            outgoingCollapsed = false;
            updateOutgoingTextAreaHeight(outgoingExpandedRows);
            UiSvg.setButtonSvg(btnCollapseOutgoing, ICON_CHEVRON_DOWN, 14, true);
        }

        updateSendButtonState();
    }

    private void setUrlEditable(boolean editable) {
        urlEditMode = editable;
        txtUrl.setEditable(editable);

        if (editable) {
            Platform.runLater(() -> {
                txtUrl.requestFocus();
                txtUrl.positionCaret(
                        txtUrl.getText() == null ? 0 : txtUrl.getText().length()
                );
            });
        } else {
            txtUrl.deselect();
        }
    }

    @FXML
    private void noop() {
        // кнопка используется только как индикатор
    }

    @FXML
    public void onToggleUrlEdit() {
        if (!urlEditMode) {
            setUrlEditable(true);
            UiSvg.setButtonSvg(btnUrlEdit, "/icons/check-edit.svg", 18, true);
            logInfo("Режим редактирования URL сервера включён");
        } else {
            String raw = txtUrl.getText() == null ? "" : txtUrl.getText().trim();
            txtUrl.setText(raw);

            setUrlEditable(false);
            UiSvg.setButtonSvg(btnUrlEdit, "/icons/edit.svg", 18, true);
            logInfo("Режим редактирования URL сервера выключен: " + raw);

            applyPortFromUrlIfPossible(raw);
        }
    }

    @FXML public void goBack() { Router.get().back(); }
    @FXML public void goHome() { Router.get().home(); }

    @FXML
    private void onOpenConfig() {
        try {
            Path settingFile = getManualSettingFile();
            Files.createDirectories(settingFile.getParent());

            if (!Files.exists(settingFile)) {
                ObjectNode root = mapper.createObjectNode();
                root.put("port", DEFAULT_MANUAL_PORT);
                mapper.writerWithDefaultPrettyPrinter().writeValue(settingFile.toFile(), root);
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(settingFile.toFile());
                logInfo("Открыт конфиг: " + settingFile.toAbsolutePath());
            } else {
                logWarn("Desktop API не поддерживается, не могу открыть setting.json");
            }
        } catch (Exception e) {
            logWarn("Не удалось открыть setting.json: " + e.getMessage());
        }
    }

    @FXML
    private void onOpenTemplates() {
        try {
            initTemplatesFilePath();
            ensureTemplatesFileExists();

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(templatesFile.toFile());
                logInfo("Открыт файл шаблонов: " + templatesFile.toAbsolutePath());
            } else {
                logWarn("Desktop API не поддерживается, не могу открыть templates.json");
            }
        } catch (Exception e) {
            logWarn("Не удалось открыть templates.json: " + e.getMessage());
        }
    }

    @FXML
    private void onToggleServer() {
        if (manualNode == null) {
            initManualNodeLauncher();
        }

        try {
            if (manualNode.isRunning()) {
                logInfo("Остановка локального Node-сервера WebSocketManual...");
                AppShutdown.runAll();
                serverRunning = false;
                updateServerButtonsUi();
            } else {
                logInfo("Остановка всех Node-серверов перед запуском WebSocketManual...");
                AppShutdown.runAll();
                startManualServerWithPortWait();
            }
        } catch (Exception e) {
            logWarn("Ошибка управления Node-сервером WebSocketManual: " + e.getMessage());
            updateServerButtonsUi();
        }
    }

    private void startManualServerWithPortWait() {
        if (btnServerToggle != null) {
            btnServerToggle.setDisable(true);
        }

        Thread t = new Thread(() -> {
            final int maxAttempts = 40;
            final long sleepMs    = 100L;

            boolean portFree = false;

            for (int i = 0; i < maxAttempts; i++) {
                if (!isPortInUse(manualPort)) {
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

            Platform.runLater(() -> {
                try {
                    if (finalPortFree) {
                        logInfo("Порт " + manualPort + " освобождён. Запускаем локальный Node-сервер WebSocketManual...");
                        manualNode.startIfNeeded();
                        serverRunning = true;
                    } else {
                        logWarn(
                                "Порт " + manualPort + " так и не освободился. " +
                                        "Скорее всего, его занимает внешний процесс или основной WebSocket-сервер. " +
                                        "Остановите его вручную и попробуйте ещё раз."
                        );
                        serverRunning = manualNode.isRunning();
                    }
                } catch (IOException e) {
                    logWarn("Не удалось запустить Node-сервер WebSocketManual: " + e.getMessage());
                    serverRunning = false;
                } finally {
                    if (btnServerToggle != null) {
                        btnServerToggle.setDisable(false);
                    }
                    updateServerButtonsUi();
                }
            });
        }, "manual-server-port-wait");

        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onToggleConnect() {
        if (manualNode == null || !manualNode.isRunning()) {
            logWarn("Сначала запустите сервер (Start server).");
            return;
        }

        clientConnected = !clientConnected;
        sendAcceptCommand(clientConnected);

        if (clientConnected) {
            logInfo("Приём клиентов включён.");
            reloadClientsFromJson();
        } else {
            logInfo("Приём клиентов выключен.");
        }

        updateServerButtonsUi();
    }

    private void updateServerButtonsUi() {
        serverRunning = (manualNode != null && manualNode.isRunning());

        if (btnServerToggle != null) {
            btnServerToggle.setText(serverRunning ? "Server Off" : "Start server");
        }
        if (btnConnectToggle != null) {
            btnConnectToggle.setText(clientConnected ? "Disconnect" : "Connect");
            btnConnectToggle.setDisable(!serverRunning);
        }

        updateClientCountLabel();
        updateSendButtonState();
    }

    private void clearClientsJsonOnStartup() {
        if (manualStateFile == null) return;

        try {
            Files.createDirectories(manualStateFile.getParent());

            ObjectNode root = mapper.createObjectNode();
            root.putArray("clients");

            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(manualStateFile.toFile(), root);
        } catch (Exception e) {
            logWarn("Не удалось очистить clients.json на старте: " + e.getMessage());
        }
    }

    private void sendAcceptCommand(boolean value) {
        if (manualNode == null || !manualNode.isRunning()) {
            return;
        }
        String cmd = value ? "ACCEPT on" : "ACCEPT off";
        manualNode.sendCommandLine(cmd);
    }

    private void reloadClientsFromJson() {
        // сохраняем текущее состояние на случай race
        List<String> oldIds = new ArrayList<>(clientIds);

        List<String> parsed = new ArrayList<>();

        if (manualStateFile != null && Files.exists(manualStateFile)) {
            try {
                JsonNode root = mapper.readTree(manualStateFile.toFile());
                JsonNode arr = root.path("clients");
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        String id = null;
                        if (n.isTextual()) {
                            id = n.asText();
                        } else if (n.isObject()) {
                            id = n.path("id").asText(null);
                        }
                        if (id != null && !id.isBlank() && !parsed.contains(id)) {
                            parsed.add(id);
                        }
                    }
                }
            } catch (Exception e) {
                logWarn("Не удалось прочитать clients.json: " + e.getMessage());
            }
        }

        // защита от race: если файл пустой, но в памяти уже есть клиенты — не затираем
        if (parsed.isEmpty() && !oldIds.isEmpty()) {
            clientIds.clear();
            clientIds.addAll(oldIds);
            logInfo("clients.json ещё не обновился — оставляю in-memory список клиентов (" + clientIds.size() + ").");
        } else {
            clientIds.clear();
            clientIds.addAll(parsed);
        }

        updateClientCountLabel();
        updateSendButtonState();

        if (clientComboController != null) clientComboController.refresh();
        if (clientComboLeftController != null) clientComboLeftController.refresh();
    }

    // ===== Отправка =====

    @FXML
    private void onSend() {
        String payload = txtOutgoing.getText();
        if (payload == null || payload.isBlank()) {
            logWarn("Пустое сообщение — отменено.");
            updateSendButtonState();
            return;
        }

        // одинаковые проверки как и у ping (чтобы логика не расходилась с UI)
        if (manualNode == null || !manualNode.isRunning()) {
            logWarn("Сервер WebSocketManual не запущен. Нажмите 'Start server'.");
            updateSendButtonState();
            return;
        }
        if (!clientConnected) {
            logWarn("Приём клиентов выключен (Connect). Сообщение не отправлено.");
            updateSendButtonState();
            return;
        }

        boolean sendToAll = (chkSendToAll != null && chkSendToAll.isSelected());

        String compactPayload = payload
                .replaceAll("\\s+", " ")
                .trim();

        if (sendToAll) {
            // отправка всем
            if (clientIds.isEmpty()) {
                logWarn("Нет клиентов для отправки.");
                updateSendButtonState();
                return;
            }

            for (String id : clientIds) {
                if (id == null || id.isBlank()) continue;
                manualNode.sendCommandLine("SEND id=" + id + " " + compactPayload);
            }

            logInfo("Отправка всем клиентам (" + clientIds.size() + "): " + trim(payload));

            // NEW: автоочистка исходящего текста по настройке
            if (shouldAutoClearOutgoingAfterSend()) {
                txtOutgoing.clear();
                updateSendButtonState();
            }
            return;
        }

        // старое поведение — одному выбранному
        if (clientComboController == null) {
            logWarn("Комбобокс клиентов не инициализирован.");
            return;
        }

        String clientId = clientComboController.getSelectedValue();
        if (clientId == null || clientId.isBlank()) {
            logWarn("Не выбран клиент для отправки.");
            return;
        }

        String commandLine = "SEND id=" + clientId + " " + compactPayload;

        manualNode.sendCommandLine(commandLine);
        logInfo("Отправка клиенту [" + clientId + "]: " + trim(payload));

        // NEW: автоочистка исходящего текста по настройке
        if (shouldAutoClearOutgoingAfterSend()) {
            txtOutgoing.clear();
            updateSendButtonState();
        }
    }

    @FXML
    private void onSendPing() {
        if (manualNode == null || !manualNode.isRunning()) {
            logWarn("Сервер WebSocketManual не запущен. Нажмите 'Start server'.");
            updateSendButtonState();
            return;
        }
        if (!clientConnected) {
            logWarn("Приём клиентов выключен (Connect). Ping не отправлен.");
            updateSendButtonState();
            return;
        }

        boolean sendToAll = (chkSendToAll != null && chkSendToAll.isSelected());

        if (sendToAll) {
            if (clientIds.isEmpty()) {
                logWarn("Нет клиентов для ping.");
                updateSendButtonState();
                return;
            }

            for (String id : clientIds) {
                if (id == null || id.isBlank()) continue;
                manualNode.sendCommandLine("PING id=" + id);
            }

            logOutgoing("> WebSocket Ping sent (ALL)");
            String time = LocalTime.now().format(TIME_FMT);
            appendToLog(time + " > WebSocket Ping sent (ALL)");
            return;
        }

        // старое поведение — ping одному выбранному
        if (clientComboController == null) {
            logWarn("Комбобокс клиентов не инициализирован.");
            updateSendButtonState();
            return;
        }

        String clientId = clientComboController.getSelectedValue();
        if (clientId == null || clientId.isBlank()) {
            logWarn("Не выбран клиент для ping.");
            updateSendButtonState();
            return;
        }

        manualNode.sendCommandLine("PING id=" + clientId);

        logOutgoing("> WebSocket Ping sent");
        String time = LocalTime.now().format(TIME_FMT);
        appendToLog(time + " > WebSocket Ping sent");
    }

    // ===== Настройки отправки =====

    /**
     * Автоочистка поля исходящего сообщения после успешной отправки.
     * Читаем из:
     *  1) node-server/setting/setting.json
     *  2) (fallback) node-server-manual/setting/setting.json
     *
     * Поддерживаем как плоский ключ "autoClearOutgoingAfterSend",
     * так и вложенный "WebSocketManual.autoClearOutgoingAfterSend".
     */
    private boolean shouldAutoClearOutgoingAfterSend() {
        final String key = "autoClearOutgoingAfterSend";

        // 1) основной server (как ты попросил)
        Path mainCfg = Paths.get(System.getProperty("user.dir"), "node-server", "setting", "setting.json");
        Boolean v1 = readBoolSetting(mainCfg, key);
        if (v1 != null) return v1;

        // 2) fallback: manual server config
        if (manualServerDir != null) {
            Path manualCfg = manualServerDir.resolve("setting").resolve("setting.json");
            Boolean v2 = readBoolSetting(manualCfg, key);
            if (v2 != null) return v2;
        }

        return false;
    }

    private Boolean readBoolSetting(Path file, String key) {
        try {
            if (file == null || !Files.exists(file)) return null;

            JsonNode root = mapper.readTree(file.toFile());

            // плоский ключ
            if (root.has(key)) return root.path(key).asBoolean(false);

            // вложенный ключ: WebSocketManual.<key>
            JsonNode wsManual = root.path("WebSocketManual");
            if (wsManual.isObject() && wsManual.has(key)) {
                return wsManual.path(key).asBoolean(false);
            }
        } catch (Exception ignore) {
            // тихо, настройки — не критичны
        }
        return null;
    }

    // ===== Поле исходящего сообщения =====

    @FXML
    private void onToggleOutgoingCollapse() {
        outgoingCollapsed = !outgoingCollapsed;

        if (outgoingCollapsed) {
            updateOutgoingTextAreaHeight(outgoingCollapsedRows);
            UiSvg.setButtonSvg(btnCollapseOutgoing, ICON_CHEVRON_RIGHT, 14, true);
        } else {
            updateOutgoingTextAreaHeight(outgoingExpandedRows);
            UiSvg.setButtonSvg(btnCollapseOutgoing, ICON_CHEVRON_DOWN, 14, true);
        }
    }

    @FXML
    private void onClearOutgoing() {
        txtOutgoing.clear();
        updateSendButtonState();
    }

    @FXML
    private void onScrollOutgoingToBottom() {
        String text = txtOutgoing.getText();
        int len = text == null ? 0 : text.length();
        txtOutgoing.positionCaret(len);
        txtOutgoing.setScrollTop(Double.MAX_VALUE);

        if (btnScrollOutgoing != null) {
            btnScrollOutgoing.setVisible(false);
            btnScrollOutgoing.setManaged(false);
        }
    }

    private void initOutgoingScrollWatcher() {
        if (txtOutgoing == null) return;

        txtOutgoing.textProperty().addListener((obs, oldV, newV) -> {
            if (btnScrollOutgoing != null) {
                int lines = txtOutgoing.getParagraphs().size();
                boolean need = lines > 6;
                btnScrollOutgoing.setVisible(need);
                btnScrollOutgoing.setManaged(need);
            }
            updateSendButtonState();
        });

        updateSendButtonState();
    }

    private void updateSendButtonState() {
        if (txtOutgoing == null) return;

        boolean serverOk = (manualNode != null && manualNode.isRunning());
        boolean acceptOk = clientConnected;

        boolean sendToAll = (chkSendToAll != null && chkSendToAll.isSelected());

        String clientId = null;
        if (clientComboController != null) {
            clientId = clientComboController.getSelectedValue();
        }
        boolean hasClient = (clientId != null && !clientId.isBlank());

        // NEW: если "всем", то таргет — наличие хотя бы одного клиента
        boolean hasTarget = sendToAll ? !clientIds.isEmpty() : hasClient;

        String text = txtOutgoing.getText();
        boolean empty = (text == null || text.isBlank());

        boolean canSend = serverOk && acceptOk && hasTarget;

        if (btnSend != null) {
            btnSend.setDisable(empty || !canSend);
        }

        // NEW: ping только в одного (и только когда НЕ sendToAll)
        if (btnSendPing != null) {
            boolean hasPingTarget = sendToAll ? !clientIds.isEmpty() : hasClient;
            boolean canPing = serverOk && acceptOk && hasPingTarget;
            btnSendPing.setDisable(!canPing);
        }
    }

    @FXML
    private void onScrollIncomingToBottom() {
        if (listIncoming == null) return;
        int size = listIncoming.getItems().size();
        if (size == 0) return;

        listIncoming.scrollTo(size - 1);

        if (btnScrollIncoming != null) {
            btnScrollIncoming.setVisible(false);
            btnScrollIncoming.setManaged(false);
        }
    }

    // ===== Консоль =====

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
    private void toggleConsole() {
        boolean expanded = consolePane != null && consolePane.isVisible();
        setConsoleState(!expanded);
    }

    @FXML
    private void onClearLog() {
        if (taLog != null) {
            taLog.clear();
        }
    }

    @FXML
    private void onScrollLogToBottom() {
        if (taLog != null) {
            taLog.setScrollTop(Double.MAX_VALUE);
        }
        if (btnScrollToBottom != null) {
            btnScrollToBottom.setVisible(false);
            btnScrollToBottom.setManaged(false);
        }
    }

    private void appendToLog(String line) {
        if (taLog == null) {
            System.out.println(line);
            return;
        }

        if (taLog.getText().isEmpty()) {
            taLog.appendText(line);
        } else {
            taLog.appendText("\n" + line);
        }

        if (btnScrollToBottom != null && taLog.getParagraphs().size() > 20) {
            btnScrollToBottom.setVisible(true);
            btnScrollToBottom.setManaged(true);
        }

        taLog.setScrollTop(Double.MAX_VALUE);
    }

    private void logInfo(String msg) { appendToLog("[INFO] " + msg); }
    private void logWarn(String msg) { appendToLog("[WARN] " + msg); }

    private String trim(String s) {
        s = s.replace("\n", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ===== Клиенты =====

    public void onClientConnected(String clientId) {
        if (!clientIds.contains(clientId)) {
            clientIds.add(clientId);
        }
        updateClientCountLabel();

        if (clientComboController != null) {
            clientComboController.refresh();
        }
        if (clientComboLeftController != null) {
            clientComboLeftController.refresh();
        }

        logInfo("Клиент подключен: " + clientId);
    }

    public void onClientDisconnected(String clientId) {
        clientIds.remove(clientId);
        incomingByClient.remove(clientId);

        updateClientCountLabel();

        if (clientComboController != null) {
            clientComboController.refresh();
        }
        if (clientComboLeftController != null) {
            clientComboLeftController.refresh();
        }

        refreshIncomingListView();
        logInfo("Клиент отключен: " + clientId);
    }

    public void onClientMessage(String clientId, String message) {
        String formatted = formatMsg(message);

        List<String> bucket = incomingByClient.computeIfAbsent(clientId, id -> new ArrayList<>());
        bucket.add(formatted);

        refreshIncomingListView();
        logInfo("Сообщение от [" + clientId + "].");
    }

    private void refreshIncomingListView() {
        if (listIncoming == null) return;

        List<String> viewData = new ArrayList<>();

        String selectedClient = null;
        if (clientComboLeftController != null) {
            selectedClient = clientComboLeftController.getSelectedValue();
        }

        if (selectedClient == null || selectedClient.isBlank()) {
            for (Map.Entry<String, List<String>> e : incomingByClient.entrySet()) {
                String clientId = e.getKey();
                for (String msg : e.getValue()) {
                    viewData.add("[" + clientId + "] " + msg);
                }
            }
        } else {
            List<String> bucket = incomingByClient.get(selectedClient);
            if (bucket != null) {
                viewData.addAll(bucket);
            }
        }

        listIncoming.getItems().setAll(viewData);

        if (btnScrollIncoming != null) {
            boolean need = viewData.size() > 20;
            btnScrollIncoming.setVisible(need);
            btnScrollIncoming.setManaged(need);
        }
    }

    /**
     * btnClientCount — индикатор (ВСЕГДА graphic):
     * - count == 0: показываем SVG (clients-empty.svg)
     * - count > 0 : показываем Label с числом
     * + tooltip всегда
     */
    private void updateClientCountLabel() {
        if (btnClientCount == null) return;

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::updateClientCountLabel);
            return;
        }

        // tooltip всегда
        Tooltip tt = new Tooltip(buildClientsTooltip(clientIds, 5));
        tt.setWrapText(true);
        tt.setMaxWidth(420);
        btnClientCount.setTooltip(tt);

        // гарантируем, что graphic инициализирован
        if (clientCountGraphic == null) {
            initClientCountIndicatorGraphic();
        }

        int count = clientIds.size();

        // кнопка всегда без текста, всегда graphic-only
        btnClientCount.setText("");
        btnClientCount.setGraphic(clientCountGraphic);
        btnClientCount.setGraphicTextGap(0);
        btnClientCount.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        if (count <= 0) {
            // показать SVG, скрыть label
            if (clientCountEmptyIcon != null) clientCountEmptyIcon.setVisible(true);
            if (clientCountLabel != null) {
                clientCountLabel.setVisible(false);
                clientCountLabel.setText("");
            }
        } else {
            // показать label, скрыть SVG
            if (clientCountEmptyIcon != null) clientCountEmptyIcon.setVisible(false);
            if (clientCountLabel != null) {
                clientCountLabel.setVisible(true);
                clientCountLabel.setText(String.valueOf(count));
            }
        }
    }

    private String buildClientsTooltip(List<String> ids, int limit) {
        if (ids == null || ids.isEmpty()) {
            return "Нет подключённых";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Подключены:\n");

        int n = Math.min(limit, ids.size());
        for (int i = 0; i < n; i++) {
            sb.append("• ").append(ids.get(i)).append("\n");
        }

        if (ids.size() > limit) {
            sb.append("• ...");
        } else {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
        }

        return sb.toString();
    }

    // ===== Лог исходящих сообщений сервера =====

    public void logOutgoing(String msg) {
        String formatted = formatMsg(msg);
        outgoingMessages.add(formatted);
        refreshOutgoingLogView();
    }

    private void refreshOutgoingLogView() {
        if (listOutgoingLog == null) return;

        listOutgoingLog.getItems().setAll(outgoingMessages);

        int size = outgoingMessages.size();
        if (size > 0) {
            listOutgoingLog.scrollTo(size - 1);
        }

        if (btnScrollOutgoingLog != null) {
            boolean need = size > 20;
            btnScrollOutgoingLog.setVisible(need);
            btnScrollOutgoingLog.setManaged(need);
        }
    }

    @FXML
    private void onScrollOutgoingLogToBottom() {
        if (listOutgoingLog == null) return;

        int size = listOutgoingLog.getItems().size();
        if (size == 0) return;

        listOutgoingLog.scrollTo(size - 1);

        if (btnScrollOutgoingLog != null) {
            btnScrollOutgoingLog.setVisible(false);
            btnScrollOutgoingLog.setManaged(false);
        }
    }

    // ===== Templates: save current outgoing -> templates.json (with de-dup) =====

    @FXML
    private void onSaveTemplate() {
        if (txtOutgoing == null) return;

        String raw = txtOutgoing.getText();
        String normalized = normalizeTemplate(raw);

        if (normalized.isBlank()) {
            logWarn("Шаблон пустой — сохранять нечего.");
            return;
        }

        try {
            initTemplatesFilePath();
            ensureTemplatesFileExists();

            // всегда перечитываем, чтобы не потерять изменения, сделанные через внешний редактор
            loadTemplatesFromJson();

            boolean exists = templates.contains(normalized);
            if (exists) {
                logInfo("Шаблон уже есть в templates.json — дубликат не добавляю.");
                selectTemplateInCombo(normalized);
                return;
            }

            templates.add(normalized);
            writeTemplatesToJson();
            refreshTemplatesComboOnFx();
            selectTemplateInCombo(normalized);

            logInfo("Шаблон сохранён в templates.json.");
        } catch (Exception e) {
            logWarn("Не удалось сохранить шаблон в templates.json: " + e.getMessage());
        }
    }


    //111111111111111

    private String normalizeTemplate(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (t.isBlank()) return "";

        // Если это валидный JSON — канонизируем в компактную строку без лишних пробелов.
        try {
            JsonNode n = mapper.readTree(t);
            return mapper.writeValueAsString(n);
        } catch (Exception ignore) {
            // Если это не JSON — оставляем как есть (но тогда при записи уйдёт строкой, с экранированием).
            return t;
        }
    }

    private void writeTemplatesToJson() throws IOException {
        initTemplatesFilePath();
        ensureTemplatesFileExists();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"templates\":[\n");

        boolean first = true;
        for (String t : templates) {
            if (t == null) continue;
            String n = normalizeTemplate(t);
            if (n.isBlank()) continue;

            if (!first) sb.append(",\n");

            // JSON (object/array/number/bool/null) пишем как JSON-узел в одну строку
            // Иначе (любой текст) пишем как JSON-строку
            try {
                JsonNode node = mapper.readTree(n);
                String oneLine = mapper.writeValueAsString(node);
                sb.append(oneLine);
            } catch (Exception ex) {
                // это не JSON → сохраняем как строку
                sb.append(mapper.writeValueAsString(n));
            }

            first = false;
        }

        sb.append("\n]}");

        Files.writeString(
                templatesFile,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }


    // ===== Templates helpers =====

    private void initTemplatesFilePath() {
        if (templatesFile != null) return;

        if (manualServerDir == null) {
            manualServerDir = Paths.get(System.getProperty("user.dir"), "node-server-manual");
        }
        templatesFile = manualServerDir.resolve("setting").resolve("templates.json");
    }

    private void ensureTemplatesFileExists() throws IOException {
        Path dir = templatesFile.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        if (!Files.exists(templatesFile)) {
            String minimal = "{\n  \"templates\": []\n}\n";
            Files.writeString(
                    templatesFile,
                    minimal,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        }
    }

    private void loadTemplatesFromJson() {
        try {
            initTemplatesFilePath();
            ensureTemplatesFileExists();

            String json = Files.readString(templatesFile, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                templates.clear();
                return; // FIX: не трогаем UI здесь
            }

            JsonNode root = mapper.readTree(json);
            List<String> loaded = new ArrayList<>();

            // Поддерживаем форматы:
            // 1) { "templates": [ "...", {...}, ... ] }
            // 2) [ "...", {...}, ... ]
            JsonNode arr;
            if (root.isArray()) {
                arr = root;
            } else if (root.isObject()) {
                arr = root.path("templates");
            } else {
                arr = null;
            }

            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n == null || n.isNull()) continue;

                    if (n.isTextual()) {
                        String s = normalizeTemplate(n.asText());
                        if (!s.isBlank()) loaded.add(s);
                    } else {
                        // объект/массив/число/boolean — превращаем в компактный JSON
                        String s = normalizeTemplate(mapper.writeValueAsString(n));
                        if (!s.isBlank()) loaded.add(s);
                    }
                }
            }

            templates.clear();
            templates.addAll(loaded);

            // FIX: никакого refreshTemplatesComboOnFx() тут
        } catch (Exception e) {
            logWarn("Не удалось прочитать templates.json: " + e.getMessage());
        }
    }

    private void refreshTemplatesComboOnFx() {
        Platform.runLater(() -> {
            if (templatesComboController != null) {
                templatesComboController.refresh();
            } else if (cbTemplates != null) {
                cbTemplates.getItems().setAll(templates);
            }
        });
    }


    private void selectTemplateInCombo(String normalizedTemplate) {
        if (cbTemplates == null) return;

        Platform.runLater(() -> {
            for (String item : cbTemplates.getItems()) {
                if (normalizeTemplate(item).equals(normalizedTemplate)) {
                    cbTemplates.getSelectionModel().select(item);
                    break;
                }
            }
        });
    }

    private void installTemplatesCellFactory() {
        if (cbTemplates == null) return;

        cbTemplates.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(truncTemplate(item));
                }
            }
        });

        cbTemplates.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(truncTemplate(item));
            }
        });
    }

    private String truncTemplate(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ").trim();
        if (t.length() <= TEMPLATE_PREVIEW_LEN) return t;
        return t.substring(0, TEMPLATE_PREVIEW_LEN) + "...";
    }

    private void startTemplatesWatcher() {
        if (templatesWatchRunning) return;

        try {
            initTemplatesFilePath();
            ensureTemplatesFileExists();

            templatesWatch = FileSystems.getDefault().newWatchService();
            Path dir = templatesFile.getParent();
            if (dir == null) return;

            dir.register(
                    templatesWatch,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );

            templatesWatchRunning = true;

            templatesWatchThread = new Thread(() -> {
                while (templatesWatchRunning) {
                    WatchKey key;
                    try {
                        key = templatesWatch.take();
                    } catch (Exception e) {
                        break;
                    }

                    boolean touched = false;

                    for (WatchEvent<?> evt : key.pollEvents()) {
                        Kind<?> kind = evt.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                        Object ctx = evt.context();
                        if (ctx instanceof Path p) {
                            if (p.getFileName().toString().equalsIgnoreCase(templatesFile.getFileName().toString())) {
                                touched = true;
                            }
                        }
                    }

                    try { key.reset(); } catch (Exception ignore) {}

                    if (touched) {
                        // редакторы часто пишут файл в несколько проходов — чуть подождём
                        try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                        loadTemplatesFromJson();
                        // FIX: UI обновляем здесь, а не внутри loadTemplatesFromJson()
                        refreshTemplatesComboOnFx();
                    }
                }
            }, "ws-manual-templates-watch");

            templatesWatchThread.setDaemon(true);
            templatesWatchThread.start();

            // гарантированно выключаем watcher при закрытии приложения
            AppShutdown.register(this::stopTemplatesWatcher);

        } catch (Exception e) {
            logWarn("Не удалось запустить watcher templates.json: " + e.getMessage());
        }
    }

    private void stopTemplatesWatcher() {
        templatesWatchRunning = false;
        try { if (templatesWatch != null) templatesWatch.close(); } catch (Exception ignored) {}
        templatesWatch = null;
    }

    private String formatMsg(String raw) {
        if (raw == null) raw = "";
        raw = raw.trim();
        if (raw.length() > 500) {
            raw = raw.substring(0, 500) + "...";
        }

        String time = LocalTime.now().format(TIME_FMT);
        return time + " " + raw;
    }

    // ===== ниже у тебя в проекте ещё есть методы (selectTemplateInCombo, etc.) =====
    // Я их НЕ трогал — оставь как было в твоём файле.
}
