package app.products.deeplink;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Централизованный биндинг горячих клавиш + тултип «горячих клавиш» для бейдж-кнопки. */
public final class Hotkeys {

    private Hotkeys() {}

    // =========================
    //   ГЛОБАЛЬНЫЕ НАСТРОЙКИ
    // =========================
    /** Поведение скрытия тултипа. */
    public enum HideBehavior {
        /** Тултип живёт до повторного клика или внешних событий (клик вне, потеря фокуса). */
        CLICK_ONLY,
        /** Тултип скрывается при уходе курсора с кнопки (с настраиваемой задержкой). */
        HIDE_ON_MOUSE_EXIT
    }

    // ==== РЕДАКТИРУЙ ТОЛЬКО ЭТО — централизованные настройки тултипа ====

    // true  → тултип скрывается при уходе курсора с кнопки
    // false → тултип скрывается только по клику по кнопке (или клику вне)
    private static boolean CLOSE_ON_MOUSE_EXIT = false;

    // Задержка перед скрытием при уходе мыши (в миллисекундах)
    private static int DEFAULT_HIDE_DELAY_MS = 140;

    // Отступ тултипа от угла кнопки при показе
    private static double DEFAULT_OFFSET_PX = 12;

    // Максимальная ширина тултипа
    private static double DEFAULT_MAX_WIDTH = 420;

    // Дополнительные автоскрытия
    private static boolean DEFAULT_AUTOHIDE_SCENE_CLICK = true; // клик вне кнопки — прячет тултип
    private static boolean DEFAULT_AUTOHIDE_WIN_BLUR    = true; // потеря фокуса окна — прячет тултип

    // ---- внутреннее сопоставление флага с типом поведения ----
    private static HideBehavior DEFAULT_HIDE_BEHAVIOR =
            CLOSE_ON_MOUSE_EXIT ? HideBehavior.HIDE_ON_MOUSE_EXIT : HideBehavior.CLICK_ONLY;


    // Глобальный активный конфиг (можно менять в рантайме через set*/configure()).
    private static volatile Config GLOBAL = new Config(
            DEFAULT_HIDE_BEHAVIOR,
            DEFAULT_HIDE_DELAY_MS,
            DEFAULT_OFFSET_PX,
            DEFAULT_MAX_WIDTH,
            DEFAULT_AUTOHIDE_SCENE_CLICK,
            DEFAULT_AUTOHIDE_WIN_BLUR
    );

    /** Централизованный конфиг. */
    public static final class Config {
        public final HideBehavior hideBehavior;
        public final int hideDelayMillis;
        public final double offsetPx;
        public final double maxWidth;
        public final boolean autoHideOnSceneClick;
        public final boolean autoHideOnWindowBlur;

        public Config(HideBehavior hideBehavior, int hideDelayMillis, double offsetPx, double maxWidth,
                      boolean autoHideOnSceneClick, boolean autoHideOnWindowBlur) {
            this.hideBehavior = hideBehavior;
            this.hideDelayMillis = hideDelayMillis;
            this.offsetPx = offsetPx;
            this.maxWidth = maxWidth;
            this.autoHideOnSceneClick = autoHideOnSceneClick;
            this.autoHideOnWindowBlur = autoHideOnWindowBlur;
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private HideBehavior hideBehavior = DEFAULT_HIDE_BEHAVIOR;
            private int hideDelayMillis = DEFAULT_HIDE_DELAY_MS;
            private double offsetPx = DEFAULT_OFFSET_PX;
            private double maxWidth = DEFAULT_MAX_WIDTH;
            private boolean autoHideOnSceneClick = DEFAULT_AUTOHIDE_SCENE_CLICK;
            private boolean autoHideOnWindowBlur = DEFAULT_AUTOHIDE_WIN_BLUR;

            public Builder hideBehavior(HideBehavior v){ this.hideBehavior=v; return this; }
            public Builder hideDelayMillis(int v){ this.hideDelayMillis=v; return this; }
            public Builder offsetPx(double v){ this.offsetPx=v; return this; }
            public Builder maxWidth(double v){ this.maxWidth=v; return this; }
            public Builder autoHideOnSceneClick(boolean v){ this.autoHideOnSceneClick=v; return this; }
            public Builder autoHideOnWindowBlur(boolean v){ this.autoHideOnWindowBlur=v; return this; }
            public Config build(){
                return new Config(hideBehavior, hideDelayMillis, offsetPx, maxWidth, autoHideOnSceneClick, autoHideOnWindowBlur);
            }
        }
    }

    // ===== Runtime-API для централизованного управления (необязательно) =====
    public static void configure(Config cfg){ GLOBAL = Objects.requireNonNull(cfg); }
    public static void setHideBehavior(HideBehavior v){ GLOBAL = new Config(v, GLOBAL.hideDelayMillis, GLOBAL.offsetPx, GLOBAL.maxWidth, GLOBAL.autoHideOnSceneClick, GLOBAL.autoHideOnWindowBlur); }
    public static void setHideDelayMillis(int ms){ GLOBAL = new Config(GLOBAL.hideBehavior, ms, GLOBAL.offsetPx, GLOBAL.maxWidth, GLOBAL.autoHideOnSceneClick, GLOBAL.autoHideOnWindowBlur); }
    public static void setOffsetPx(double px){ GLOBAL = new Config(GLOBAL.hideBehavior, GLOBAL.hideDelayMillis, px, GLOBAL.maxWidth, GLOBAL.autoHideOnSceneClick, GLOBAL.autoHideOnWindowBlur); }
    public static void setMaxWidth(double w){ GLOBAL = new Config(GLOBAL.hideBehavior, GLOBAL.hideDelayMillis, GLOBAL.offsetPx, w, GLOBAL.autoHideOnSceneClick, GLOBAL.autoHideOnWindowBlur); }
    public static void setAutoHideOnSceneClick(boolean v){ GLOBAL = new Config(GLOBAL.hideBehavior, GLOBAL.hideDelayMillis, GLOBAL.offsetPx, GLOBAL.maxWidth, v, GLOBAL.autoHideOnWindowBlur); }
    public static void setAutoHideOnWindowBlur(boolean v){ GLOBAL = new Config(GLOBAL.hideBehavior, GLOBAL.hideDelayMillis, GLOBAL.offsetPx, GLOBAL.maxWidth, GLOBAL.autoHideOnSceneClick, v); }

    // =========================
    //        ACTIONS
    // =========================
    public static final class Actions {
        private final Runnable onApply, onSaveCfg, onOpenList, onCheckAdb,
                onToggleAdbEdit, onPickAdb, onFocusLink, onToggleConsole,
                onClearConsole, onScrollBottom;

        private Actions(Builder b) {
            this.onApply=b.onApply; this.onSaveCfg=b.onSaveCfg; this.onOpenList=b.onOpenList;
            this.onCheckAdb=b.onCheckAdb; this.onToggleAdbEdit=b.onToggleAdbEdit; this.onPickAdb=b.onPickAdb;
            this.onFocusLink=b.onFocusLink; this.onToggleConsole=b.onToggleConsole;
            this.onClearConsole=b.onClearConsole; this.onScrollBottom=b.onScrollBottom;
        }
        public static Builder builder(){ return new Builder(); }
        public static final class Builder {
            private Runnable onApply,onSaveCfg,onOpenList,onCheckAdb,onToggleAdbEdit,onPickAdb,onFocusLink,onToggleConsole,onClearConsole,onScrollBottom;
            public Builder onApply(Runnable r){onApply=r;return this;}
            public Builder onSaveCfg(Runnable r){onSaveCfg=r;return this;}
            public Builder onOpenList(Runnable r){onOpenList=r;return this;}
            public Builder onCheckAdb(Runnable r){onCheckAdb=r;return this;}
            public Builder onToggleAdbEdit(Runnable r){onToggleAdbEdit=r;return this;}
            public Builder onPickAdb(Runnable r){onPickAdb=r;return this;}
            public Builder onFocusLink(Runnable r){onFocusLink=r;return this;}
            public Builder onToggleConsole(Runnable r){onToggleConsole=r;return this;}
            public Builder onClearConsole(Runnable r){onClearConsole=r;return this;}
            public Builder onScrollBottom(Runnable r){onScrollBottom=r;return this;}
            public Actions build(){return new Actions(this);}
        }
    }

    // =========================
    //     PUBLIC API (вызовы)
    // =========================

    /** Единая точка входа: использует централизованный GLOBAL-конфиг. Контроллер не трогаем. */
    public static void install(Node root, Labeled badgeBtn, Actions a) {
        install(root, badgeBtn, a, GLOBAL);
    }

    /** Доп. перегрузки оставлены для совместимости, но не обязательны. */
    public static void install(Node root, Labeled badgeBtn, Actions a, boolean hideOnMouseExit) {
        Config cfg = Config.builder()
                .hideBehavior(hideOnMouseExit ? HideBehavior.HIDE_ON_MOUSE_EXIT : HideBehavior.CLICK_ONLY)
                .build();
        install(root, badgeBtn, a, cfg);
    }

    /** Основной рабочий метод с явным конфигом (если вдруг нужен per-call). */
    public static void install(Node root, Labeled badgeBtn, Actions a, Config cfg) {
        Objects.requireNonNull(root, "root is null");
        Objects.requireNonNull(a, "actions is null");
        Objects.requireNonNull(cfg, "config is null");

        // --- хоткеи ---
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
                    && e.getCode() == KeyCode.ENTER && a.onApply != null) {
                a.onApply.run(); e.consume(); return;
            }
            if (CTRL_S.match(e) && a.onSaveCfg != null)        { a.onSaveCfg.run();        e.consume(); return; }
            if (CTRL_O.match(e) && a.onOpenList != null)       { a.onOpenList.run();       e.consume(); return; }
            if (F5.match(e) && a.onCheckAdb != null)           { a.onCheckAdb.run();       e.consume(); return; }
            if (CTRL_E.match(e) && a.onToggleAdbEdit != null)  { a.onToggleAdbEdit.run();  e.consume(); return; }
            if (CTRL_P.match(e) && a.onPickAdb != null)        { a.onPickAdb.run();        e.consume(); return; }
            if (CTRL_L.match(e) && a.onFocusLink != null)      { a.onFocusLink.run();      e.consume(); return; }
            if (CTRL_SLASH.match(e) && a.onToggleConsole != null){ a.onToggleConsole.run();e.consume(); return; }
            if (CTRL_SHIFT_C.match(e) && a.onClearConsole != null){ a.onClearConsole.run();e.consume(); return; }
            if (CTRL_END.match(e) && a.onScrollBottom != null) { a.onScrollBottom.run();   e.consume(); }
        });

        // --- кликовый тултип/позиционирование ---
        if (badgeBtn != null) {
            final Tooltip tt = new Tooltip(buildTooltipText(a));
            tt.getStyleClass().add("hotkeys-tooltip");
            tt.setShowDelay(Duration.ZERO);
            tt.setHideDelay(Duration.ZERO);
            tt.setAutoFix(false);
            tt.setAutoHide(true);
            tt.setWrapText(true);
            tt.setMaxWidth(cfg.maxWidth);

            // показываем вручную по клику
            badgeBtn.setOnMouseClicked(e -> {
                if (tt.isShowing()) { tt.hide(); return; }

                Point2D p = badgeBtn.localToScreen(
                        badgeBtn.getLayoutBounds().getMaxX(),
                        badgeBtn.getLayoutBounds().getMaxY()
                );
                if (p != null) {
                    tt.show(badgeBtn, p.getX() + cfg.offsetPx, p.getY() + cfg.offsetPx);
                    clampTooltipWithinApp(tt, root);
                    avoidOverlapWithButton(tt, badgeBtn, root, cfg.offsetPx);
                } else {
                    Window w = badgeBtn.getScene() != null ? badgeBtn.getScene().getWindow() : null;
                    if (w != null) {
                        tt.show(badgeBtn, w.getX() + w.getWidth() - cfg.maxWidth, w.getY() + 64);
                        clampTooltipWithinApp(tt, root);
                    }
                }
            });

            // таймер скрытия (для режима HIDE_ON_MOUSE_EXIT)
            final PauseTransition hideDelay = new PauseTransition(Duration.millis(cfg.hideDelayMillis));
            hideDelay.setOnFinished(ev -> { if (tt.isShowing()) tt.hide(); });

            if (cfg.hideBehavior == HideBehavior.HIDE_ON_MOUSE_EXIT) {
                badgeBtn.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
                    if (tt.isShowing()) hideDelay.playFromStart();
                });
                badgeBtn.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> hideDelay.stop());
            } else {
                // в кликовом режиме — просто сбиваем возможный таймер при возврате
                badgeBtn.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> hideDelay.stop());
            }

            // подстраховки по конфигу
            badgeBtn.sceneProperty().addListener((o, oldScene, scene) -> {
                if (scene == null) return;
                if (cfg.autoHideOnSceneClick) {
                    scene.addEventFilter(MouseEvent.MOUSE_PRESSED, me -> {
                        if (tt.isShowing() && me.getTarget() != badgeBtn) tt.hide();
                    });
                }
                if (cfg.autoHideOnWindowBlur && scene.getWindow() != null) {
                    scene.getWindow().focusedProperty().addListener((fo, was, nowFocused) -> {
                        if (!nowFocused && tt.isShowing()) tt.hide();
                    });
                }
            });

            // при ресайзе/перемещении окна — удерживаем внутри
            Window app = root.getScene() != null ? root.getScene().getWindow() : null;
            if (app != null) {
                ChangeListener<Number> relayout = (obs, o, n) -> { if (tt.isShowing()) clampTooltipWithinApp(tt, root); };
                app.xProperty().addListener(relayout);
                app.yProperty().addListener(relayout);
                app.widthProperty().addListener(relayout);
                app.heightProperty().addListener(relayout);
            }
        }
    }

    // =========================
    //     ВСПОМОГАТЕЛЬНОЕ
    // =========================

    // Комбинации
    private static final KeyCodeCombination CTRL_S       = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination CTRL_O       = new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination CTRL_E       = new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination CTRL_P       = new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination CTRL_L       = new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination CTRL_SLASH   = new KeyCodeCombination(KeyCode.SLASH, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination CTRL_SHIFT_C = new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCodeCombination CTRL_END     = new KeyCodeCombination(KeyCode.END, KeyCombination.CONTROL_DOWN);
    private static final KeyCodeCombination F5           = new KeyCodeCombination(KeyCode.F5);

    /** Текст подсказки только по реально привязанным действиям. */
    private static String buildTooltipText(Actions a) {
        List<String> lines = new ArrayList<>();
        if (a.onApply != null)         lines.add("Enter — Apply");
        if (a.onSaveCfg != null)       lines.add("Ctrl+S — Save to cfg");
        if (a.onOpenList != null)      lines.add("Ctrl+O — Открыть список");
        if (a.onCheckAdb != null)      lines.add("F5 — Проверить ADB/девайс");
        if (a.onToggleAdbEdit != null) lines.add("Ctrl+E — Редактировать ADB path");
        if (a.onPickAdb != null)       lines.add("Ctrl+P — Выбрать adb.exe");
        if (a.onFocusLink != null)     lines.add("Ctrl+L — Фокус в Deep link");
        if (a.onToggleConsole != null) lines.add("Ctrl+/ — Свернуть/развернуть консоль");
        if (a.onClearConsole != null)  lines.add("Ctrl+Shift+C — Очистить консоль");
        if (a.onScrollBottom != null)  lines.add("Ctrl+End — Прокрутка вниз");
        return String.join("\n", lines);
    }

    /** Держим тултип строго в пределах окна приложения. */
    private static void clampTooltipWithinApp(Tooltip tt, Node anyNodeInsideApp) {
        try {
            Window app = anyNodeInsideApp.getScene() != null ? anyNodeInsideApp.getScene().getWindow() : null;
            Window tip = tt.getSkin() != null && tt.getSkin().getNode() != null
                    ? tt.getSkin().getNode().getScene().getWindow()
                    : null;
            if (app == null || tip == null) return;

            double tx = tip.getX(), ty = tip.getY(), tw = tip.getWidth(), th = tip.getHeight();
            double ax = app.getX(), ay = app.getY(), aw = app.getWidth(), ah = app.getHeight();

            double nx = Math.max(ax, Math.min(tx, ax + aw - tw));
            double ny = Math.max(ay, Math.min(ty, ay + ah - th));

            tip.setX(nx);
            tip.setY(ny);
        } catch (Exception ignore) { }
    }

    /** Если оконце тултипа пересекает кнопку — уводим его строго под кнопку, с отступом. */
    private static void avoidOverlapWithButton(Tooltip tt, Labeled badgeBtn, Node root, double offsetPx) {
        try {
            Window tip = tt.getSkin() != null && tt.getSkin().getNode() != null
                    ? tt.getSkin().getNode().getScene().getWindow()
                    : null;
            if (tip == null) return;

            Bounds b = badgeBtn.localToScreen(badgeBtn.getBoundsInLocal());
            double bx = b.getMinX(), by = b.getMinY(), bw = b.getWidth(), bh = b.getHeight();
            double tx = tip.getX(), ty = tip.getY(), tw = tip.getWidth(), th = tip.getHeight();

            boolean intersects =
                    tx < bx + bw && tx + tw > bx &&
                            ty < by + bh && ty + th > by;

            if (intersects) {
                tt.setX(bx + bw - tw);       // выравниваем правые края
                tt.setY(by + bh + offsetPx); // строго под кнопкой
                clampTooltipWithinApp(tt, root);
            }
        } catch (Exception ignore) { }
    }
}
