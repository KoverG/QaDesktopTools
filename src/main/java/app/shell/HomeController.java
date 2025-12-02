package app.shell;

import app.core.Router;
import app.core.View;
import javafx.fxml.FXML;

public class HomeController {

    @FXML
    public void openWebSocket() {
        Router.get().go(View.WEBSOCKET);
    }

    @FXML
    public void openDeepLink() {
        Router.get().go(View.DEEPLINK);
    }

    @FXML
    public void openWebSocketManual() {
        Router.get().go(View.WEBSOCKET_MANUAL);
    }
}
