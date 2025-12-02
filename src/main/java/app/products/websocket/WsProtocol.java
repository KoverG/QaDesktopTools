// FILE: src/main/java/app/products/websocket/WsProtocol.java
package app.products.websocket;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface WsProtocol {
    String decorateUrl(String baseUrl);
    List<String> initialHandshakeMessages();
    String build(String key, Map<String, Object> args);
    void onInbound(String text, Consumer<String> log);
}