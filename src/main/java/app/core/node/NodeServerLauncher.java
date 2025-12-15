// FILE: src/main/java/app/core/node/NodeServerLauncher.java
package app.core.node;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class NodeServerLauncher {
    private static final String NODE_BIN = "node";

    private final Path serverDir;
    private final Consumer<String> onLog;
    private Process process;

    // теги, которые считаем «служебными» и не показываем в UI
    private static final Set<String> SILENT_PREFIXES = Set.of(
            "[EVENT]", "[CONTROL]", "[CONNECT]", "[CLOSE]"
    );

    public NodeServerLauncher(Path serverDir, Consumer<String> onLog) {
        this.serverDir = Objects.requireNonNull(serverDir);
        this.onLog = onLog == null ? s -> {} : onLog;

        // Регистрируем стоппер в центральном менеджере завершения приложения
        AppShutdown.register(this::stopIfRunning);
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized void startIfNeeded() throws IOException {
        if (isRunning()) return;

        // server.js обязателен
        Path serverJs = serverDir.resolve("server.js");
        if (!Files.exists(serverJs)) {
            throw new FileNotFoundException("Не найден server.js: " + serverJs.toAbsolutePath());
        }

        // ЕДИНЫЙ обязательный конфиг: setting/setting.json
        Path settingJson = serverDir.resolve("setting").resolve("setting.json");
        if (!Files.exists(settingJson)) {
            throw new IllegalStateException("Missing file: " + settingJson.toAbsolutePath());
        }

        // Стартуем Node-процесс
        ProcessBuilder pb = new ProcessBuilder()
                .directory(serverDir.toFile())
                .command(nodeExec(), "server.js");

        process = pb.start();

        startPipeReader(process.getInputStream(), false);
        startPipeReader(process.getErrorStream(), true);
    }

    public synchronized void stopIfRunning() {
        if (!isRunning()) return;

        try {
            process.destroy();
            if (!process.waitFor(700, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log("Ошибка остановки Node: " + e.getMessage());
        } finally {
            process = null;
        }
    }

    private void startPipeReader(InputStream is, boolean err) {
        var exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, err ? "node-stderr" : "node-stdout");
            t.setDaemon(true);
            return t;
        });
        exec.submit(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!err) {
                        boolean silent = SILENT_PREFIXES.stream().anyMatch(line::startsWith);
                        if (silent) continue; // гасим шумные системные логи
                    }
                    log((err ? "[ERR] " : "") + line);
                }
            } catch (IOException ignore) {
                // процесс завершается — тишина
            }
        });
    }

    private void log(String s) {
        Platform.runLater(() -> onLog.accept(s));
    }

    /**
     * Обновляет current_scenario в setting/setting.json (используется в биржевом сервере).
     */
    public void setCurrentScenario(String id) throws IOException {
        Path cfg = serverDir.resolve("setting").resolve("setting.json");
        String json = Files.readString(cfg, StandardCharsets.UTF_8);
        String patched = json.replaceAll(
                "\"current_scenario\"\\s*:\\s*\"[^\"]*\"",
                "\"current_scenario\":\"" + id.replace("\"", "\\\"") + "\""
        );
        Files.writeString(cfg, patched, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        log("setting.json: current_scenario = " + id);
    }

    /**
     * Отправить строку-команду в stdin Node-процесса.
     * Используется WebSocketManual для команд вида:
     *   SEND id=<clientId> { ...json... }
     */
    public synchronized void sendCommandLine(String line) {
        if (!isRunning()) {
            log("Попытка отправить команду в Node, но процесс не запущен.");
            return;
        }
        if (line == null || line.isBlank()) return;

        try {
            OutputStream os = process.getOutputStream();
            // важный момент: команда должна быть одной строкой
            String normalized = line.replace("\r", " ").replace("\n", " ").trim();
            byte[] bytes = (normalized + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            os.write(bytes);
            os.flush();
        } catch (IOException e) {
            log("Ошибка отправки команды в Node: " + e.getMessage());
        }
    }

    private String nodeExec() {
        return NODE_BIN;
    }
}
