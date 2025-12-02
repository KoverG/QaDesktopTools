package app.products.websocketmanual;

import javafx.scene.control.ComboBox;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ManualComboController {

    private final ComboBox<String> combo;
    private final Supplier<List<String>> itemsSupplier;

    public ManualComboController(ComboBox<String> combo, Supplier<List<String>> itemsSupplier) {
        this.combo = Objects.requireNonNull(combo, "combo");
        this.itemsSupplier = Objects.requireNonNull(itemsSupplier, "itemsSupplier");
        init();
    }

    private void init() {
        // Комбобокс всегда активен
        combo.setDisable(false);

        // Берём promptText из FXML, не трогаем его здесь
        combo.setOnShowing(e -> refresh());
    }

    public void refresh() {
        List<String> items = itemsSupplier.get();
        if (items == null) {
            items = Collections.emptyList();
        }

        combo.getItems().setAll(items);

        // Не выключаем комбобокс, просто сбрасываем выбор если пусто
        if (items.isEmpty()) {
            combo.setValue(null);
        } else {
            String current = combo.getValue();
            if (current == null || !items.contains(current)) {
                combo.setValue(items.get(0));
            }
        }
    }

    public String getSelectedValue() {
        return combo.getValue();
    }
}
