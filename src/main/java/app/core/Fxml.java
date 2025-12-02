package app.core;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.net.URL;

public final class Fxml {
    private Fxml() {}

    public static Parent load(String path) {
        try {
            URL url = Fxml.class.getResource(path);
            if (url == null) {
                throw new IllegalStateException("FXML not found on classpath: " + path);
            }
            return new FXMLLoader(url).load();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load FXML: " + path + " -> " + e.getMessage(), e);
        }
    }
}
