package app.shell;

import app.core.AppConfig;
import app.core.Router;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;

public class ShellController {
    @FXML private BorderPane root;
    @FXML private Label title;          // шапка
    @FXML private Label lblVersion;     // левый нижний угол
    @FXML private Label lblHeaderScreen; // правый край шапки

    @FXML
    public void initialize() {
        Router.init(root);
        // Динамически обновляем название экрана только в правой части шапки
        Router.get().setOnTitle(s -> { if (lblHeaderScreen != null) lblHeaderScreen.setText(s); });

        // заголовок в шапке
        title.setText("Утилиты для тестирования");

        // версия слева снизу
        lblVersion.setText("v" + AppConfig.version());

        // старт – главное меню (без пуша в историю)
        Router.get().home();
    }
}
