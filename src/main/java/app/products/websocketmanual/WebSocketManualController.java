// FILE: src/main/java/app/products/websocketmanual/WebSocketManualController.java
package app.products.websocketmanual;

import app.core.Router;
import app.ui.UiSvg;
import app.ui.ScrollThumbRounding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javafx.application.Platform;

public class WebSocketManualController {

    // ===== Верхняя панель =====
    @FXML private TextField txtUrl;
    @FXML private Button   btnUrlEdit;
    @FXML private Button   btnOpenConfig;
    @FXML private Button   btnServerToggle;
    @FXML private Button   btnConnectToggle;
    @FXML private Button   btnClientCount;

    // ===== Центр: входящие сообщения и исходящий редактор =====
    @FXML private TextArea txtOutgoing;
    @FXML private Button   btnOpenTemplates;
    @FXML private Button   btnSend;

    //комбобоксы
    @FXML private ComboBox<String> cbClient;       // правый комбобокс (для отправки)
    @FXML private ComboBox<String> cbTemplates;
    @FXML private ComboBox<String> cbClientLeft;   // левый комбобокс (фильтр входящих)

    // список входящих сообщений (левая панель)
    @FXML private ListView<String> listIncoming;

    // список исходящих сообщений сервера (правая панель)
    @FXML private ListView<String> listOutgoingLog;

    // Кнопки управления полем исходящего сообщения / списком
    @FXML private Button   btnCollapseOutgoing;
    @FXML private Button   btnClearOutgoing;
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

    // ===== SVG-иконки (как в WebSocketController) =====
    private static final String ICON_CHEVRON_DOWN  = "/icons/chevron-down.svg";
    private static final String ICON_CHEVRON_RIGHT = "/icons/chevron-right.svg";
    private static final String ICON_TRASH         = "/icons/trash.svg";
    private static final String ICON_ARROW_DOWN    = "/icons/arrow-down.svg";

    // ===== Настройки WebSocketManual (из app-settings.json) =====
    private static final int  DEFAULT_OUTGOING_EXPANDED_ROWS   = 3;
    private static final int  DEFAULT_OUTGOING_COLLAPSED_ROWS  = 1;
    private static final boolean DEFAULT_OUTGOING_START_COLLAPSED = false;

    private int     outgoingExpandedRows   = DEFAULT_OUTGOING_EXPANDED_ROWS;
    private int     outgoingCollapsedRows  = DEFAULT_OUTGOING_COLLAPSED_ROWS;
    private boolean outgoingStartCollapsed = DEFAULT_OUTGOING_START_COLLAPSED;

    // ===== Состояние =====
    private final List<String> clientIds = new ArrayList<>();
    private final Map<String, List<String>> incomingByClient = new HashMap<>();
    private final List<String> outgoingMessages = new ArrayList<>();

    private boolean serverRunning    = false;
    private boolean clientConnected  = false;
    private boolean urlEditMode      = false;

    private boolean outgoingCollapsed = false; // поле исходящего сообщения свёрнуто/развёрнуто

    //контроллеры комбобоксов
    private ManualComboController clientComboController;      // правый
    private ManualComboController clientComboLeftController;  // левый
    private ManualComboController templatesComboController;

    // ===== Инициализация =====
    @FXML
    public void initialize() {
        // 1) Загружаем конфиг для WebSocketManual
        loadWebSocketManualSettings();

        txtUrl.setText("ws://localhost:8080/control");
        updateClientCountLabel();
        updateServerButtonsUi();

        // селектор клиентов (правый комбобокс — для отправки)
        if (cbClient != null) {
            clientComboController = new ManualComboController(cbClient, () -> clientIds);
            clientComboController.refresh(); // стартовое состояние (пустой список)
        }

        // селектор клиентов (левый комбобокс — для просмотра/фильтра)
        if (cbClientLeft != null) {
            clientComboLeftController = new ManualComboController(cbClientLeft, () -> clientIds);
            clientComboLeftController.refresh();

            // при смене клиента обновляем список входящих
            cbClientLeft.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
                refreshIncomingListView();
            });
        }

        // селектор шаблонов (пока пустой список — чистая верстка)
        if (cbTemplates != null) {
            templatesComboController = new ManualComboController(cbTemplates, List::of);
            templatesComboController.refresh();
        }

        // дальше всё как было: URL, иконки, консоль, исходящее поле и т.д.
        setUrlEditable(false);
        UiSvg.setButtonSvg(btnUrlEdit, "/icons/edit.svg", 18, true);

        UiSvg.setButtonSvg(btnClearLog,       ICON_TRASH,      14, true);
        UiSvg.setButtonSvg(btnScrollToBottom, ICON_ARROW_DOWN, 14, true);

        setConsoleState(true);

        if (btnClearOutgoing != null) {
            UiSvg.setButtonSvg(btnClearOutgoing, ICON_TRASH, 14, true);
        }
        if (btnScrollOutgoing != null) {
            UiSvg.setButtonSvg(btnScrollOutgoing, ICON_ARROW_DOWN, 14, true);
            btnScrollOutgoing.setVisible(false);
            btnScrollOutgoing.setManaged(false);
        }

        // НОВОЕ: иконка и начальное состояние кнопки прокрутки входящих
        if (btnScrollIncoming != null) {
            UiSvg.setButtonSvg(btnScrollIncoming, ICON_ARROW_DOWN, 14, true);
            btnScrollIncoming.setVisible(false);
            btnScrollIncoming.setManaged(false);
        }

        // НОВОЕ: иконка и начальное состояние кнопки прокрутки лога исходящих
        if (btnScrollOutgoingLog != null) {
            UiSvg.setButtonSvg(btnScrollOutgoingLog, ICON_ARROW_DOWN, 14, true);
            btnScrollOutgoingLog.setVisible(false);
            btnScrollOutgoingLog.setManaged(false);
        }

        // скруглённые скроллы
        ScrollThumbRounding.attach(txtOutgoing);
        initOutgoingScrollWatcher();

        // ВАЖНО: применяем высоту поля исходящего сообщения после layout-а
        Platform.runLater(this::applyOutgoingInitialStateFromSettings);

        logInfo("WebSocket Manual: UI инициализирован.");
    }


    // ===== Загрузка настроек из app-settings.json =====

    private void loadWebSocketManualSettings() {
        try (InputStream is = getClass().getResourceAsStream("/config/app-settings.json")) {
            if (is == null) {
                // ресурсов нет — остаёмся на дефолтах
                System.err.println("[WARN] app-settings.json not found, using defaults for WebSocketManual.");
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            JsonNode wsManual = root.path("WebSocketManual");
            JsonNode outgoing = wsManual.path("outgoing");

            if (outgoing.isObject()) {
                int expanded = outgoing.path("prefRowCountExpanded")
                        .asInt(DEFAULT_OUTGOING_EXPANDED_ROWS);
                if (expanded > 0) {
                    outgoingExpandedRows = expanded;
                }

                int collapsed = outgoing.path("prefRowCountCollapsed")
                        .asInt(DEFAULT_OUTGOING_COLLAPSED_ROWS);
                if (collapsed > 0) {
                    outgoingCollapsedRows = collapsed;
                }

                outgoingStartCollapsed = outgoing.path("startCollapsed")
                        .asBoolean(DEFAULT_OUTGOING_START_COLLAPSED);
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to read app-settings.json: " + e.getMessage());
            // В случае ошибки остаёмся на дефолтных значениях
        }
    }

    /**
     * Обновляет число строк и даёт JavaFX самому пересчитать высоту.
     * Важно: сбрасываем min/pref/max в "используй pref/computed".
     */
    private void updateOutgoingTextAreaHeight(int rows) {
        if (txtOutgoing == null) return;

        // Кол-во строк (влияет на prefHeight внутри TextArea)
        txtOutgoing.setPrefRowCount(rows);

        // Сбрасываем явные высоты, которые могли быть выставлены ранее
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

        // сразу выставляем доступность кнопки "Отправить"
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
    public void onToggleUrlEdit() {
        if (!urlEditMode) {
            // включаем режим редактирования
            setUrlEditable(true);
            UiSvg.setButtonSvg(btnUrlEdit, "/icons/check-edit.svg", 18, true);
            logInfo("Режим редактирования URL сервера включён");
        } else {
            // выключаем режим, фиксируем значение
            String raw = txtUrl.getText() == null ? "" : txtUrl.getText().trim();
            txtUrl.setText(raw);

            setUrlEditable(false);
            UiSvg.setButtonSvg(btnUrlEdit, "/icons/edit.svg", 18, true);
            logInfo("Режим редактирования URL сервера выключен: " + raw);
        }
    }

    // ===== Навигация =====
    @FXML public void goBack() { Router.get().back(); }
    @FXML public void goHome() { Router.get().home(); }

    // ===== Кнопка-счётчик клиентов =====
    @FXML
    private void onShowClients() {
        if (clientIds.isEmpty()) {
            logInfo("Нет подключённых клиентов.");
            Alert empty = new Alert(Alert.AlertType.INFORMATION);
            empty.setTitle("Подключённые клиенты");
            empty.setHeaderText(null);
            empty.setContentText("Нет подключённых клиентов.");
            if (btnClientCount.getScene() != null) {
                empty.initOwner(btnClientCount.getScene().getWindow());
            }
            empty.showAndWait();
            return;
        }

        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Подключённые клиенты");
        dlg.setHeaderText("Всего: " + clientIds.size());
        if (btnClientCount.getScene() != null) {
            dlg.initOwner(btnClientCount.getScene().getWindow());
        }
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<String> listView =
                new ListView<>(FXCollections.observableArrayList(clientIds));
        listView.setPrefSize(320, 240);

        dlg.getDialogPane().setContent(listView);
        dlg.showAndWait();
    }

    // ===== Конфиг / шаблоны =====
    @FXML
    private void onOpenConfig() {
        logInfo("Открытие конфига WebSocket Manual (TODO).");
    }

    @FXML
    private void onOpenTemplates() {
        logInfo("Открытие файла шаблонов ответов (TODO).");
    }

    // ===== Управление сервером / соединением (пока заглушки) =====
    @FXML
    private void onToggleServer() {
        serverRunning = !serverRunning;
        logInfo(serverRunning ? "Сервер: START (TODO)" : "Сервер: STOP (TODO)");
        updateServerButtonsUi();
    }

    @FXML
    private void onToggleConnect() {
        clientConnected = !clientConnected;
        logInfo(clientConnected ? "Клиент: CONNECT (TODO)" : "Клиент: DISCONNECT (TODO)");
        updateServerButtonsUi();
    }

    private void updateServerButtonsUi() {
        if (btnServerToggle != null) {
            btnServerToggle.setText(serverRunning ? "Server Off" : "Start server");
        }
        if (btnConnectToggle != null) {
            btnConnectToggle.setText(clientConnected ? "Disconnect" : "Connect");
            btnConnectToggle.setDisable(!serverRunning);
        }
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

        if (clientComboController == null) {
            logWarn("Комбобокс клиентов не инициализирован.");
            return;
        }

        String clientId = clientComboController.getSelectedValue();
        if (clientId == null || clientId.isBlank()) {
            logWarn("Не выбран клиент для отправки.");
            return;
        }

        // TODO: реальная отправка через NodeServer
        logInfo("Отправка клиенту [" + clientId + "]: " + trim(payload));

        // Лог в правой панели (аналог истории входящих)
        logOutgoing("[" + clientId + "] " + payload);
    }

    // ===== Поле исходящего сообщения: сворачивание, очистка и прокрутка =====

    @FXML
    private void onToggleOutgoingCollapse() {
        // переключаем высоту по значениям из конфига + меняем иконку (как у консоли)
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
            // логика кнопки прокрутки исходящего
            if (btnScrollOutgoing != null) {
                int lines = txtOutgoing.getParagraphs().size();
                boolean need = lines > 6; // чуть длиннее видимой области — показываем кнопку
                btnScrollOutgoing.setVisible(need);
                btnScrollOutgoing.setManaged(need);
            }

            // логика доступности кнопки "Отправить"
            updateSendButtonState();
        });

        // стартовое состояние
        updateSendButtonState();
    }

    private void updateSendButtonState() {
        if (btnSend == null || txtOutgoing == null) return;

        String text = txtOutgoing.getText();
        boolean empty = (text == null || text.isBlank());
        btnSend.setDisable(empty);
    }

    // ===== Кнопка прокрутки входящих вниз =====
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
        // Если консоль не размечена в FXML — просто логируем в stdout и выходим
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

    // ===== Клиенты (для нод-сервера) =====
    public void onClientConnected(String clientId) {
        clientIds.add(clientId);
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
        updateClientCountLabel();

        if (clientComboController != null) {
            clientComboController.refresh();
        }
        if (clientComboLeftController != null) {
            clientComboLeftController.refresh();
        }

        logInfo("Клиент отключен: " + clientId);
    }

    public void onClientMessage(String clientId, String message) {
        String formatted = formatMsg(message);

        // добавляем в список сообщений этого клиента
        List<String> bucket = incomingByClient.computeIfAbsent(clientId, id -> new ArrayList<>());
        bucket.add(formatted);

        // обновляем визуальный список
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
            // нет выбора — показываем ВСЕ сообщения со всех клиентов с префиксом
            for (Map.Entry<String, List<String>> e : incomingByClient.entrySet()) {
                String clientId = e.getKey();
                for (String msg : e.getValue()) {
                    viewData.add("[" + clientId + "] " + msg);
                }
            }
        } else {
            // выбран конкретный клиент — показываем только его сообщения
            List<String> bucket = incomingByClient.get(selectedClient);
            if (bucket != null) {
                viewData.addAll(bucket);
            }
        }

        listIncoming.getItems().setAll(viewData);

        // логика кнопки прокрутки вниз для входящих
        if (btnScrollIncoming != null) {
            boolean need = viewData.size() > 20; // например, если сообщений больше 20
            btnScrollIncoming.setVisible(need);
            btnScrollIncoming.setManaged(need);
        }
    }

    private void updateClientCountLabel() {
        int count = clientIds.size();
        btnClientCount.setText(String.valueOf(count));

        if (count == 0) {
            btnClientCount.setTooltip(new Tooltip("Нет подключённых"));
        } else {
            btnClientCount.setTooltip(new Tooltip(
                    "Подключены: " + String.join(", ", clientIds)
            ));
        }
    }

    // ===== Лог исходящих сообщений сервера (правая панель) =====

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
            // авто-прокрутка к последнему сообщению
            listOutgoingLog.scrollTo(size - 1);
        }

        if (btnScrollOutgoingLog != null) {
            boolean need = size > 20; // показываем кнопку, если сообщений много
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

    private String formatMsg(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        return raw.length() > 500 ? raw.substring(0, 500) + "..." : raw;
    }
}
