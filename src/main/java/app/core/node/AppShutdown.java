// FILE: src/main/java/app/core/node/AppShutdown.java
package app.core.node;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Централизованный менеджер остановки фоновых ресурсов приложения.
 * Используется для остановки Node-серверов и любых других ресурсов.
 */
public final class AppShutdown {

    private static final Set<Runnable> STOPPERS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private AppShutdown() {
        // utility
    }

    /**
     * Зарегистрировать обработчик, который нужно вызвать при закрытии приложения.
     */
    public static void register(Runnable stopper) {
        if (stopper != null) {
            STOPPERS.add(stopper);
        }
    }

    /**
     * Отменить регистрацию обработчика (если больше не нужен).
     */
    public static void unregister(Runnable stopper) {
        if (stopper != null) {
            STOPPERS.remove(stopper);
        }
    }

    /**
     * Вызывается из MainApp.stop(): пытается аккуратно остановить все ресурсы.
     */
    public static void runAll() {
        for (Runnable stopper : STOPPERS.toArray(new Runnable[0])) {
            try {
                stopper.run();
            } catch (Exception ignored) {
                // на завершении приложения не падаем
            }
        }
    }
}
