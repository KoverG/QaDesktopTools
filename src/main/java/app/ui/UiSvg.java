// FILE: src/main/java/app/ui/UiSvg.java
package app.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Единая утилита для SVG-иконок из resources/icons.
 * Базовая идея — любые экраны вызывают только эти методы.
 *
 * Пример:
 * UiSvg.setButtonSvg(btnClear, "/icons/trash.svg", 14, true);
 */
public final class UiSvg {

    private UiSvg() {}

    /** Быстрое создание узла-иконки нужного размера. */
    public static Node icon(String resPath, double sizePx) {
        return loadSvgIcon(resPath, sizePx, true, Color.WHITE);
    }

    /** Унифицированная установка SVG на кнопку (фиксация hit-box, только графика, удобный размер). */
    public static void setButtonSvg(Button btn, String resPath, double sizePx, boolean monochromeWhite) {
        if (btn == null) return;

        Node svg = loadSvgIcon(resPath, sizePx, monochromeWhite, Color.WHITE);
        if (svg != null) {
            svg.setMouseTransparent(true);

            StackPane box = new StackPane(svg);
            box.setMinSize(sizePx, sizePx);
            box.setPrefSize(sizePx, sizePx);
            box.setMaxSize(sizePx, sizePx);
            box.setPickOnBounds(false);

            btn.setText("");
            btn.setGraphic(box);
            btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            // кликается вся рамка
            btn.setPickOnBounds(true);
            btn.setMinSize(28, 28);
            btn.setPrefSize(28, 28);
        }
    }

    /** Низкоуровневый парсер SVG (поддержка минимального подмножества для stroke-минимализма). */
    public static Node loadSvgIcon(String resourcePath, double targetSizePx, boolean forceMonochrome, Color monoStrokeColor) {
        try (var is = UiSvg.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;

            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            Element svg = doc.getDocumentElement();

            double vbW = 24, vbH = 24;
            if (svg.hasAttribute("viewBox")) {
                String[] p = svg.getAttribute("viewBox").trim().split("\\s+");
                if (p.length == 4) { vbW = safeDouble(p[2], 24); vbH = safeDouble(p[3], 24); }
            } else {
                if (svg.hasAttribute("width"))  vbW = safeDouble(svg.getAttribute("width").replace("px",""), 24);
                if (svg.hasAttribute("height")) vbH = safeDouble(svg.getAttribute("height").replace("px",""), 24);
            }
            double base = Math.max(vbW, vbH);
            double scale = targetSizePx / (base > 0 ? base : 24);

            Group g = new Group();
            NodeList paths = svg.getElementsByTagName("path");
            for (int i = 0; i < paths.getLength(); i++) {
                Element pe = (Element) paths.item(i);
                String d = pe.getAttribute("d");
                if (d == null || d.isBlank()) continue;

                SVGPath p = new SVGPath();
                p.setContent(d);

                String fill = pe.getAttribute("fill");
                boolean noFill = fill == null || fill.isBlank() || "none".equalsIgnoreCase(fill);
                p.setFill(noFill ? Color.TRANSPARENT : (forceMonochrome ? Color.TRANSPARENT : Color.web(fill)));

                if (forceMonochrome) {
                    p.setStroke(monoStrokeColor != null ? monoStrokeColor : Color.WHITE);
                } else {
                    String stroke = pe.getAttribute("stroke");
                    if (stroke != null && !stroke.isBlank() && !"none".equalsIgnoreCase(stroke)) {
                        p.setStroke(Color.web(stroke));
                    } else {
                        p.setStroke(null);
                    }
                }

                if (pe.hasAttribute("stroke-width"))   p.setStrokeWidth(safeDouble(pe.getAttribute("stroke-width"), 1.5));
                if (pe.hasAttribute("stroke-linecap")) p.setStrokeLineCap(parseCap(pe.getAttribute("stroke-linecap")));
                if (pe.hasAttribute("stroke-linejoin"))p.setStrokeLineJoin(parseJoin(pe.getAttribute("stroke-linejoin")));
                if (pe.hasAttribute("stroke-miterlimit")) p.setStrokeMiterLimit(safeDouble(pe.getAttribute("stroke-miterlimit"), 10));

                g.getChildren().add(p);
            }

            g.setScaleX(scale);
            g.setScaleY(scale);
            g.getStyleClass().add("icon");
            return g;
        } catch (Exception e) {
            // без логгера умышленно, чтобы утилита не зависела от контроллеров
            return null;
        }
    }

    private static double safeDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception ignore) { return def; }
    }
    private static StrokeLineCap parseCap(String v) {
        return switch (v.toLowerCase()) {
            case "round" -> StrokeLineCap.ROUND;
            case "square" -> StrokeLineCap.SQUARE;
            default -> StrokeLineCap.BUTT;
        };
    }
    private static StrokeLineJoin parseJoin(String v) {
        return switch (v.toLowerCase()) {
            case "round" -> StrokeLineJoin.ROUND;
            case "bevel" -> StrokeLineJoin.BEVEL;
            default -> StrokeLineJoin.MITER;
        };
    }
}
