// FILE: src/main/java/app/products/deeplink/DeepLinkController.java
package app.products.deeplink;

import app.core.Router;
import app.ui.ScrollThumbRounding;
import app.ui.UiSvg;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class DeepLinkController {

    // === UI ===
    @FXML private TextField tfLink;
    @FXML private TextField tfAdbPath;
    @FXML private CheckBox  chkDevice;
    @FXML private Button    btnApply;

    // Кнопки с иконками
    @FXML private Button    btnEditAdb;          // edit.svg / check-edit.svg
    @FXML private Button    btnPickAdb;          // folder-exe.svg

    // консоль — как на WebSocket
    @FXML private TextArea  taLog;
    @FXML private Button    btnConsoleToggle;    // chevron right/down
    @FXML private Button    btnClearLog;         // trash
    @FXML private Label     lblConsole;
    @FXML private StackPane consolePane;
    @FXML private Button    btnScrollToBottom;   // arrow-down

    // диплинки
    @FXML private ComboBox<String> cbDeeplinks;

    // «айка» — кнопка (отдельная маленькая кнопка в UI)
    @FXML private Button btnHotkeys;

    // корень экрана
    @FXML private BorderPane root;

    // === DATA ===
    private AdbRunner runner;


    private static final String PROP_PATH = "/config/priv/deeplink/priv_cfgDeeplink.properties";
    private static final String JSON_LIST_NAME = "priv_list_deeplink.json";


    private boolean logAutoStick = true;
    private Timeline logStickGuard;

    private Timeline devicePoller;
    private Boolean lastDeviceConnected = null;

    private boolean adbEditMode = false;

    private static final PseudoClass PC_ERROR = PseudoClass.getPseudoClass("error");
    private boolean isLinkValid = false;
    private String  linkValidationError = null;
    private Boolean lastLinkValid = null;
    private String  lastApplyDisabledHint = null;

    private static ObjectWriter prettyWriter() {
        ObjectMapper om = new ObjectMapper();
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return om.writer(pp);
    }

    @FXML
    public void initialize() {
        // ADB path
        tfAdbPath.setText(normalizeWindowsDrivePath(resolveDefaultAdbPath()));
        setAdbEditable(false);

        // Enter подтверждает в режиме редактирования; вне режима — блок ввода
        tfAdbPath.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (adbEditMode && e.getCode() == KeyCode.ENTER) {
                e.consume();
                onToggleAdbEdit();
            }
            if (!adbEditMode && (e.getCode().isLetterKey() || e.getCode().isDigitKey() || e.getCode() == KeyCode.BACK_SPACE)) {
                e.consume();
            }
        });

        // Консоль
        ScrollThumbRounding.attach(taLog);
        setConsoleState(true);
        installLogAutoStick();

        // Комбобокс диплинков
        cbDeeplinks.setPromptText("Диплинки");
        cbDeeplinks.setEditable(false);
        cbDeeplinks.showingProperty().addListener((obs, was, showing) -> { if (showing) reloadDeeplinks(); });
        cbDeeplinks.setOnAction(e -> {
            String v = cbDeeplinks.getSelectionModel().getSelectedItem();
            if (v != null && !v.isBlank()) tfLink.setText(v);
        });
        cbDeeplinks.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin instanceof javafx.scene.control.skin.ComboBoxListViewSkin<?> skin) {
                var content = skin.getPopupContent();
                if (content instanceof ListView<?> lv) {
                    lv.minWidthProperty().bind(cbDeeplinks.widthProperty());
                    lv.prefWidthProperty().bind(cbDeeplinks.widthProperty());
                    lv.maxWidthProperty().bind(cbDeeplinks.widthProperty());
                }
            }
        });
        Platform.runLater(() -> {
            var skin = cbDeeplinks.getSkin();
            if (skin instanceof javafx.scene.control.skin.ComboBoxListViewSkin<?> s) {
                var content = s.getPopupContent();
                if (content instanceof ListView<?> lv) {
                    lv.minWidthProperty().bind(cbDeeplinks.widthProperty());
                    lv.prefWidthProperty().bind(cbDeeplinks.widthProperty());
                    lv.maxWidthProperty().bind(cbDeeplinks.widthProperty());
                }
            }
        });

        // Чекбокс — только чтение
        makeDeviceCheckboxReadOnly();

        // LIVE-валидация и доступность Apply
        tfLink.textProperty().addListener((obs, o, n) -> { validateAndMarkLink(n); updateApplyAvailability(); });
        tfAdbPath.textProperty().addListener((obs, o, n) -> updateApplyAvailability());
        chkDevice.selectedProperty().addListener((obs, o, n) -> updateApplyAvailability());
        validateAndMarkLink(tfLink.getText());
        updateApplyAvailability();

        // Иконки — через UiSvg (единый способ для всех экранов)
        UiSvg.setButtonSvg(btnPickAdb,        "/icons/folder-exe.svg", 18, true);
        UiSvg.setButtonSvg(btnEditAdb,        "/icons/edit.svg",       18, true);
        UiSvg.setButtonSvg(btnClearLog,       "/icons/trash.svg",      14, true);
        UiSvg.setButtonSvg(btnScrollToBottom, "/icons/arrow-down.svg", 14, true);
        setConsoleChevron(true);

        // Айка (кнопка): кликается вся рамка + SVG не перехватывает мышь
        UiSvg.setButtonSvg(btnHotkeys, "/icons/hotkeys.svg", 14, true);
        btnHotkeys.setPickOnBounds(true);
        if (btnHotkeys.getGraphic() != null) {
            btnHotkeys.getGraphic().setMouseTransparent(true);
        }

        // Хоткеи + кликовый тултип
        Hotkeys.install(
                root,
                btnHotkeys,
                Hotkeys.Actions.builder()
                        .onApply(this::onApply)
                        .onSaveCfg(this::onSaveDeeplink)
                        .onOpenList(this::onOpenCfgList)
                        .onCheckAdb(this::onCheckAdbDevice)
                        .onToggleAdbEdit(this::onToggleAdbEdit)
                        .onPickAdb(this::onPickAdb)
                        .onFocusLink(() -> { if (tfLink != null) { tfLink.requestFocus(); tfLink.end(); } })
                        .onToggleConsole(this::toggleConsole)
                        .onClearConsole(this::onClearLog)
                        .onScrollBottom(this::onScrollToBottom)
                        .build()
        );

        // Автопроверка устройства
        startDeviceWatcher();
    }

    private void makeDeviceCheckboxReadOnly() {
        chkDevice.setMouseTransparent(true);
        chkDevice.setFocusTraversable(false);
        chkDevice.addEventFilter(MouseEvent.ANY,  Event::consume);
        chkDevice.addEventFilter(KeyEvent.ANY,    Event::consume);
    }

    private void setAdbEditable(boolean editable) {
        adbEditMode = editable;
        tfAdbPath.setEditable(editable);
        if (editable) {
            Platform.runLater(() -> {
                tfAdbPath.requestFocus();
                tfAdbPath.positionCaret(tfAdbPath.getText() == null ? 0 : tfAdbPath.getText().length());
            });
        } else {
            tfAdbPath.deselect();
        }
    }

    @FXML public void goBack() { Router.get().back(); }
    @FXML public void goHome() { Router.get().home(); }

    private void onCheckAdbDevice() {
        try {
            Path adb = safeAdbPath();
            if (adb == null) { append("[ERR] adb.exe not found / invalid path"); return; }

            List<String> ver = runAdb(adb, "version");
            append("[CHECK] adb version:");
            for (String s : ver) append("  " + s);

            List<String> dev = runAdb(adb, "devices");
            append("[CHECK] adb devices:");
            for (String s : dev) append("  " + s);

            updateDeviceState();
        } catch (Exception e) {
            append("[ERR] onCheckAdbDevice: " + e.getMessage());
        }
    }

    private boolean validateAndMarkLink(String raw) {
        boolean ok = false;
        String err = null;

        String s = (raw == null) ? "" : raw.trim();
        boolean empty = s.isEmpty();

        try {
            if (empty) {
                ok = false;
                err = null;
            } else {
                URI u = URI.create(s);
                String scheme = u.getScheme();
                if (scheme == null) {
                    ok = false;
                    err = "нет схемы (до двоеточия), пример: jaminvest://...";
                } else if (!scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
                    ok = false;
                    err = "схема не по RFC (разрешены A–Z, a–z, 0–9, + . -). Получено: " + scheme;
                } else {
                    ok = true;
                }
            }
        } catch (Throwable ex) {
            ok = false;
            err = "некорректный формат URI (" + ex.getClass().getSimpleName() + ")";
        }

        isLinkValid = ok;
        linkValidationError = ok ? null : (empty ? null : err);

        boolean markError = !ok && !empty;
        if (tfLink != null)    tfLink.pseudoClassStateChanged(PC_ERROR, markError);
        if (btnApply != null)  btnApply.pseudoClassStateChanged(PC_ERROR, markError);

        if (!empty && (lastLinkValid == null || lastLinkValid != ok)) {
            lastLinkValid = ok;
            append(ok ? "[OK] Deep link валиден"
                    : "[HINT] Deep link невалиден: " + (err == null ? "неизвестная ошибка" : err));
        }
        if (empty) lastLinkValid = null;

        return ok;
    }

    @FXML
    public void onApply() {
        try {
            Path adb = safeAdbPath();
            if (adb == null) { append("[ERR] adb.exe not found / invalid path"); return; }

            String link = tfLink.getText() == null ? "" : tfLink.getText().trim();
            if (!validateAndMarkLink(link)) {
                append("[ERR] Deep link невалиден: " + (linkValidationError == null ? "схема/формат" : linkValidationError));
                return;
            }

            if (!chkDevice.isSelected()) { append("[ERR] Нет подключенного устройства (ADB)"); return; }

            runner = new AdbRunner(adb);

            var check = runner.check();
            append("[CHECK] " + check.hint());
            for (String s : check.log()) append("  " + s);
            if (!check.ok()) { append("[ERR] check failed → abort"); return; }

            var rr = runner.openDeeplink(link);
            append(rr.ok() ? "[OK] am start finished, code=" + rr.exitCode()
                    : "[ERR] am start failed, code=" + rr.exitCode());
            for (String s : rr.log()) append("  " + s);

        } catch (Exception e) {
            append("[ERR] " + e.getMessage());
        }
    }

    private void updateApplyAvailability() {
        boolean okAdb  = safeAdbPath() != null;
        boolean okUri  = validateAndMarkLink(tfLink != null ? tfLink.getText() : null);
        boolean okDev  = chkDevice.isSelected();

        boolean shouldDisable = !(okAdb && okUri && okDev);
        btnApply.setDisable(shouldDisable);

        String reason = null;
        if (!okUri) {
            reason = "[HINT] Apply недоступна: невалидный deep link — " +
                    (linkValidationError == null ? "ошибка формата/схемы" : linkValidationError);
        } else if (!okAdb) {
            reason = "[HINT] Apply недоступна: неверный путь к adb.exe";
        } else if (!okDev) {
            reason = "[HINT] Apply недоступна: устройство не подключено (ADB)";
        }

        if (shouldDisable && reason != null) {
            if (!reason.equals(lastApplyDisabledHint)) {
                lastApplyDisabledHint = reason;
                append(reason);
            }
        } else {
            lastApplyDisabledHint = null;
        }
    }

    @FXML
    public void onPickAdb() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Укажите adb.exe");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("adb.exe", "adb.exe"));
        try {
            String txt = tfAdbPath.getText() == null ? "" : tfAdbPath.getText().trim();
            if (!txt.isEmpty()) {
                Path cur = Paths.get(normalizeWindowsDrivePath(txt)).toAbsolutePath().getParent();
                if (cur != null && Files.isDirectory(cur)) fc.setInitialDirectory(cur.toFile());
            }
        } catch (Exception ignore) {}

        Window owner = tfAdbPath.getScene() != null ? tfAdbPath.getScene().getWindow() : null;
        File f = fc.showOpenDialog(owner);
        if (f != null) {
            String normalized = normalizeWindowsDrivePath(f.getAbsolutePath());
            tfAdbPath.setText(normalized);
            try {
                saveAdbPathToExternalCfg(normalized);
                append("[OK] adb.path сохранён в config/priv/deeplink/priv_cfgDeeplink.properties");
            } catch (Exception ex) {
                append("[ERR] сохранение adb.path: " + ex.getMessage());
            }
        }
    }

    @FXML
    public void onToggleAdbEdit() {
        if (!adbEditMode) {
            setAdbEditable(true);
            UiSvg.setButtonSvg(btnEditAdb, "/icons/check-edit.svg", 18, true);
            append("[INFO] Режим редактирования ADB path включён");
        } else {
            String raw = tfAdbPath.getText() == null ? "" : tfAdbPath.getText().trim();
            String normalized = normalizeWindowsDrivePath(raw);
            tfAdbPath.setText(normalized);

            try {
                saveAdbPathToExternalCfg(normalized);
                append("[OK] adb.path сохранён в config/priv/deeplink/priv_cfgDeeplink.properties");
            } catch (Exception ex) {
                append("[ERR] сохранение adb.path: " + ex.getMessage());
            }

            Path p = safeAdbPath();
            if (p == null) append("[WARN] Указанный путь к adb.exe невалиден или файла не существует");

            setAdbEditable(false);
            UiSvg.setButtonSvg(btnEditAdb, "/icons/edit.svg", 18, true);
        }
        updateApplyAvailability();
    }

    @FXML
    public void onOpenCfgList() {
        try {
            Path p = externalDeeplinkPath();
            ensureDir(p.getParent());
            if (Files.notExists(p)) {
                Files.writeString(p, "[]", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
            openFile(p);
            append("[INFO] Открываю " + p.toAbsolutePath());
        } catch (Exception e) {
            append("[ERR] onOpenCfgList: " + e.getMessage());
        }
    }

    @FXML
    public void onSaveDeeplink() {
        try {
            String link = tfLink.getText() == null ? "" : tfLink.getText().trim();
            if (link.isBlank()) { append("[ERR] Поле Deep link пустое"); return; }

            Path jsonPath = externalDeeplinkPath();
            ensureDir(jsonPath.getParent());
            if (Files.notExists(jsonPath)) {
                Files.writeString(jsonPath, "[]", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }

            ObjectMapper om = new ObjectMapper();

            String raw = "";
            try { raw = Files.readString(jsonPath, StandardCharsets.UTF_8).trim(); }
            catch (Exception ignore) {}
            if (raw.isEmpty()) raw = "[]";

            JsonNode root;
            try {
                root = om.readTree(raw);
            } catch (Exception parseEx) {
                append("[WARN] priv_list_deeplink.json повреждён, пересоздаю как массив");
                root = om.createArrayNode();
            }

            boolean added = false;
            if (root.isArray()) {
                ArrayNode arr = (ArrayNode) root;
                if (containsText(arr, link)) {
                    append("[INFO] Такой диплинк уже есть в конфиге");
                } else {
                    arr.add(link);
                    added = true;
                }
            } else if (root.has("deeplinks") && root.get("deeplinks").isArray()) {
                ArrayNode arr = (ArrayNode) root.get("deeplinks");
                if (containsText(arr, link)) {
                    append("[INFO] Такой диплинк уже есть в конфиге");
                } else {
                    arr.add(link);
                    added = true;
                }
            } else {
                ArrayNode arr = om.createArrayNode();
                arr.add(link);
                ObjectNode obj = om.createObjectNode();
                obj.set("deeplinks", arr);
                root = obj;
                added = true;
            }

            if (added) {
                Files.writeString(
                        jsonPath,
                        prettyWriter().writeValueAsString(root),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                );
                append("[OK] Диплинк добавлен в " + jsonPath.toAbsolutePath());
                reloadDeeplinks();
                cbDeeplinks.getSelectionModel().select(link);
            }

        } catch (Exception e) {
            append("[ERR] onSaveDeeplink: " + e.getMessage());
        }
    }

    private boolean containsText(ArrayNode arr, String value) {
        String needle = value == null ? "" : value.trim();
        for (JsonNode n : arr) {
            if (n != null && n.isTextual()) {
                if (needle.equals(n.asText().trim())) return true;
            }
        }
        return false;
    }

    @FXML public void onClearLog() {
        taLog.clear();
        updateScrollToBottomButton(true);
    }

    @FXML public void onScrollToBottom() {
        ScrollPane sp = getLogScrollPane();
        if (sp != null) Platform.runLater(() -> sp.setVvalue(1.0));
        updateScrollToBottomButton(true);
    }

    @FXML public void toggleConsole() {
        boolean expanded = consolePane != null && consolePane.isVisible();
        setConsoleState(!expanded);
    }

    private void setConsoleChevron(boolean expanded) {
        UiSvg.setButtonSvg(
                btnConsoleToggle,
                expanded ? "/icons/chevron-down.svg" : "/icons/chevron-right.svg",
                14, true
        );
    }

    private void setConsoleState(boolean expanded) {
        if (consolePane != null) {
            consolePane.setVisible(expanded);
            consolePane.setManaged(expanded);
        }
        setConsoleChevron(expanded);
        if (lblConsole != null) lblConsole.setOpacity(expanded ? 1.0 : 0.45);
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

            logStickGuard = new Timeline(new KeyFrame(Duration.millis(50), e -> {
                if (logAutoStick) {
                    ScrollPane s = getLogScrollPane();
                    if (s != null) s.setVvalue(1.0);
                }
            }));
            logStickGuard.setCycleCount(1);
        });
    }

    private void updateScrollToBottomButton(boolean atBottom) {
        boolean shouldShow = !atBottom;
        if (btnScrollToBottom != null) {
            btnScrollToBottom.setVisible(shouldShow);
            btnScrollToBottom.setManaged(shouldShow);
        }
    }

    private ScrollPane getLogScrollPane() {
        var n = taLog.lookup(".scroll-pane");
        return (n instanceof ScrollPane sp) ? sp : null;
    }

    private void append(String s) {
        final ScrollPane sp = getLogScrollPane();
        final String line = s;

        final Double vBefore = (sp != null) ? sp.getVvalue() : null;
        final int caretBefore = taLog.getCaretPosition();
        final int anchorBefore = taLog.getAnchor();
        final double scrollTopBefore = taLog.getScrollTop();

        if (!taLog.getText().isEmpty()) taLog.appendText("\n");
        taLog.appendText(line);

        if (sp == null) return;

        if (logAutoStick) {
            Platform.runLater(() -> sp.setVvalue(1.0));
            if (logStickGuard != null) logStickGuard.playFromStart();
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
    }

    private void reloadDeeplinks() {
        try {
            Path jsonPath = externalDeeplinkPath();
            ensureDir(jsonPath.getParent());
            if (Files.notExists(jsonPath)) {
                Files.writeString(jsonPath, "[]", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }

            String text = Files.readString(jsonPath, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) text = "[]";

            ObjectMapper om = new ObjectMapper();
            JsonNode root;
            try {
                root = om.readTree(text);
            } catch (Exception ex) {
                append("[WARN] priv_list_deeplink.json не читается: " + ex.getMessage());
                root = om.createArrayNode();
            }

            List<String> vals = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode n : root) if (n.isTextual()) vals.add(n.asText());
            } else if (root.has("deeplinks") && root.get("deeplinks").isArray()) {
                for (JsonNode n : root.get("deeplinks")) if (n.isTextual()) vals.add(n.asText());
            }

            cbDeeplinks.getItems().setAll(vals);
        } catch (Exception e) {
            append("[ERR] reloadDeeplinks: " + e.getMessage());
        }
    }

    private Path externalDeeplinkPath() {
        // Приватная версия: config/priv/deeplink/priv_list_deeplink.json
        return Paths.get("config", "priv", "deeplink", JSON_LIST_NAME);
    }

    private void openFile(Path file) throws IOException {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.toFile());
                return;
            }
        } catch (Exception ignore) {}

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", file.toAbsolutePath().toString()).start();
            } else {
                new ProcessBuilder("xdg-open", file.toAbsolutePath().toString()).start();
            }
        } catch (Exception e) {
            append("[INFO] Путь к файлу: " + file.toAbsolutePath());
        }
    }

    private static String normalizeWindowsDrivePath(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "");
        s = s.replaceFirst("^([A-Za-z])\\\\:", "$1:");
        s = s.replaceAll("\\\\{2,}", "\\\\");
        return s;
    }

    private Path safeAdbPath() {
        String raw = tfAdbPath.getText() == null ? "" : tfAdbPath.getText();
        String norm = normalizeWindowsDrivePath(raw);
        if (norm == null || norm.isBlank()) return null;
        try {
            Path p = Paths.get(norm);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception ex) {
            append("[ERR] invalid adb path: " + norm + " (" + ex.getMessage() + ")");
            return null;
        }
    }

    private String resolveDefaultAdbPath() {
        try {
            Path external = externalCfgPath();
            if (Files.exists(external)) {
                String line = Files.readString(external, StandardCharsets.UTF_8);
                String v = parseAdbPathFromProperties(line);
                if (v != null && !v.isBlank()) return normalizeWindowsDrivePath(v);
            }
        } catch (Exception ignore) {}

        String sdk = System.getenv("ANDROID_SDK_ROOT");
        if (sdk == null || sdk.isBlank()) sdk = System.getenv("ANDROID_HOME");
        if (sdk != null && !sdk.isBlank()) {
            return normalizeWindowsDrivePath(
                    Paths.get(sdk, "platform-tools", "adb.exe").toString()
            );
        }

        return normalizeWindowsDrivePath(
                "C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe"
        );
    }

    private void saveAdbPathToExternalCfg(String adbPath) throws IOException {
        Path cfg = externalCfgPath();
        ensureDir(cfg.getParent());

        if (Files.notExists(cfg)) {
            Files.writeString(
                    cfg,
                    "# created by app" + System.lineSeparator() +
                            "adb.path=\"\"" + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        }

        String normalized = normalizeWindowsDrivePath(adbPath);
        String content =
                "# путь можно хранить с кавычками — код их убирает автоматически" + System.lineSeparator() +
                        "adb.path=\"" + normalized + "\"" + System.lineSeparator();

        Files.writeString(
                cfg,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private Path externalCfgPath() {
        // Приватная версия: config/priv/deeplink/priv_cfgDeeplink.properties
        return Paths.get("config", "priv", "deeplink", "priv_cfgDeeplink.properties");
    }

    private void ensureDir(Path dir) throws IOException {
        if (dir != null && Files.notExists(dir)) Files.createDirectories(dir);
    }

    private String parseAdbPathFromProperties(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\R");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (!line.startsWith("adb.path")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String v = line.substring(eq + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        }
        return null;
    }

    private void startDeviceWatcher() {
        Platform.runLater(this::updateDeviceState);
        if (devicePoller != null) devicePoller.stop();
        devicePoller = new Timeline(new KeyFrame(Duration.seconds(2), e -> updateDeviceState()));
        devicePoller.setCycleCount(Timeline.INDEFINITE);
        devicePoller.play();
    }

    private void updateDeviceState() {
        try {
            Path adb = safeAdbPath();
            boolean connected = false;
            String reason = null;

            if (adb == null) {
                reason = "adb.exe not found / invalid path";
            } else {
                List<String> out = runAdb(adb, "devices", "-l");
                for (String line : out) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("List of devices attached")) continue;
                    if (s.matches("^[\\w\\-_.:]+\\s+device(\\s.*)?$")) { connected = true; break; }
                }
                if (!connected && !out.isEmpty()) {
                    reason = String.join(" | ", out);
                }
            }

            final boolean newState = connected;

            if (lastDeviceConnected == null || !lastDeviceConnected.equals(newState)) {
                lastDeviceConnected = newState;
                append(newState
                        ? "[ADB] device connected"
                        : "[ADB] no device (or unauthorized/offline)" + (reason != null ? " — " + reason : ""));
            }

            Platform.runLater(() -> {
                if (chkDevice != null) chkDevice.setSelected(newState);
                updateApplyAvailability();
            });
        } catch (Exception e) {
            append("[ERR] updateDeviceState: " + e.getMessage());
        }
    }

    private List<String> runAdb(Path adb, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(adb.toAbsolutePath().toString());
        for (String a : args) cmd.add(a);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        p.waitFor();
        return lines;
    }
}
