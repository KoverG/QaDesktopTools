package app.ui;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextArea;

/**
 * Навешивает CSS-классы на вертикальный thumb TextArea:
 *  - "thumb-at-top"    при упоре вверх
 *  - "thumb-at-bottom" при упоре вниз
 */
public final class ScrollThumbRounding {
    private static final String AT_TOP = "thumb-at-top";
    private static final String AT_BOTTOM = "thumb-at-bottom";

    private ScrollThumbRounding() {}

    public static void attach(TextArea ta) {
        // Ждем, когда вложенные ноды будут построены скином
        Platform.runLater(() -> {
            ScrollBar vbar = (ScrollBar) ta.lookup(".scroll-bar:vertical");
            if (vbar == null) return;

            InvalidationListener apply = obs -> applyClasses(vbar);
            // Обновлять при скролле и изменениях диапазона
            vbar.valueProperty().addListener(apply);
            vbar.minProperty().addListener(apply);
            vbar.maxProperty().addListener(apply);
            vbar.visibleAmountProperty().addListener(apply);

            // Если скин пересоздаст children — просто пере-применим классы (thumb найдем заново)
            vbar.skinProperty().addListener((o, oldSkin, newSkin) ->
                    Platform.runLater(() -> applyClasses(vbar)));

            // Стартовое применение
            apply.invalidated(null);
        });
    }

    private static void applyClasses(ScrollBar vbar) {
        Node thumb = vbar.lookup(".thumb");
        if (thumb == null) return;

        thumb.getStyleClass().removeAll(AT_TOP, AT_BOTTOM);

        double eps = 1e-6;
        double val = vbar.getValue();
        double min = vbar.getMin();
        double max = vbar.getMax();

        // если контента мало и ползунок занимает весь трек — считаем и верхом, и низом
        boolean full = (vbar.getVisibleAmount() >= (max - min) - eps);

        if (full || val <= min + eps) thumb.getStyleClass().add(AT_TOP);
        if (full || val >= max - eps) thumb.getStyleClass().add(AT_BOTTOM);
    }
}
