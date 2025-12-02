// FILE: src/main/java/app/core/View.java
package app.core;

public enum View {
    HOME("/ui/home.fxml", "Главное меню"),
    WEBSOCKET("/ui/websocket.fxml", "WebSocket"),
    WEBSOCKET_MANUAL("/ui/WebSocketManual.fxml", "WebSocket Manual"),
    DEEPLINK("/ui/deeplink.fxml", "DeepLinks");

    private final String fxml;
    private final String title;

    View(String fxml, String title) {
        this.fxml = fxml;
        this.title = title;
    }

    public String fxml() {
        return fxml;
    }

    public String title() {
        return title;
    }
}
