// FILE: src/main/java/app/core/AppConfig.java
package app.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

public final class AppConfig {
    private static final Properties P = new Properties();

    static {
        // 1) Пытаемся прочитать внешний файл config/app.properties рядом с jar
        Path external = Path.of("config", "app.properties");
        boolean loaded = false;

        if (Files.exists(external)) {
            try {
                try (InputStream is = Files.newInputStream(external)) {
                    P.load(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
                    loaded = true;
                }
            } catch (IOException e) {
                // логируем в stderr, но не падаем
                e.printStackTrace();
            }
        }

        // 2) Фолбэк: если внешнего файла нет или ошибка — пробуем classpath (/app.properties)
        if (!loaded) {
            try (InputStream is = AppConfig.class.getResourceAsStream("/app.properties")) {
                if (is != null) {
                    P.load(is);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private AppConfig() {}

    public static String version() {
        return P.getProperty("app.version", "0.0.0");
    }
}
