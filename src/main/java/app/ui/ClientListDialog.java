package app.ui;

import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.List;

public class ClientListDialog extends Dialog<Void> {

    public ClientListDialog(List<String> clientIds) {

        setTitle("Подключённые клиенты");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        // Заголовок
        Label title = new Label("Подключённые клиенты");
        title.getStyleClass().add("section-title");

        Label subtitle = new Label();
        subtitle.getStyleClass().add("form-label");

        if (clientIds.isEmpty()) {
            subtitle.setText("Нет подключённых клиентов");
        } else {
            subtitle.setText("Всего: " + clientIds.size());
        }

        // Список клиентов
        ListView<String> listView = new ListView<>();
        if (!clientIds.isEmpty()) {
            listView.getItems().setAll(clientIds);
        }
        listView.setPrefSize(360, 260);
        listView.getStyleClass().add("console");

        // Контейнер
        VBox box = new VBox(8, title, subtitle, listView);
        box.getStyleClass().add("workspace-card");
        box.setFillWidth(true);

        getDialogPane().setContent(box);

        styleDialog();
    }

    private void styleDialog() {
        DialogPane pane = getDialogPane();

        pane.getStyleClass().add("app-dialog");

        Button closeBtn = (Button) pane.lookupButton(ButtonType.CLOSE);
        if (closeBtn != null) {
            closeBtn.getStyleClass().add("chip-button");
        }
    }
}
