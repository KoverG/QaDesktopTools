// FILE: src/main/java/app/MainApp.java
package app;

import app.core.node.AppShutdown;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader fxml = new FXMLLoader(getClass().getResource("/ui/shell.fxml"));
        Scene scene = new Scene(fxml.load(), 1264, 840);
        scene.getStylesheets().add(getClass().getResource("/ui/styles.css").toExternalForm());
        stage.setTitle("Утилиты для тестирования");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        // перед выходом гасим все зарегистрированные фоновые ресурсы (Node-сервера и т.п.)
        AppShutdown.runAll();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
