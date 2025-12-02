package app.products.deeplink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AdbRunner {

    private final Path adb;

    public AdbRunner(Path adb) { this.adb = adb; }

    public Path adbPath() { return adb; }

    /** Проверка, доступен ли adb и виден ли хотя бы один device. */
    public CheckResult check() {
        List<String> out = new ArrayList<>();
        int code1 = exec(new String[]{adb.toString(), "version"}, out);
        if (code1 != 0) return new CheckResult(false, "adb not found / bad path", out);

        List<String> out2 = new ArrayList<>();
        int code2 = exec(new String[]{adb.toString(), "devices"}, out2);
        if (code2 != 0) return new CheckResult(false, "adb devices failed", out2);

        boolean any = out2.stream().anyMatch(s -> s.trim().matches("^[^\\s]+\\s+device$"));
        return new CheckResult(any, any ? "device OK" : "no devices", out2);
    }

    /** Запуск диплинка через am start. Если единственное устройство — без -s. */
    public RunResult openDeeplink(String uri) {
        List<String> devices = listSerials();
        List<String> cmd = new ArrayList<>();
        cmd.add(adb.toString());
        if (devices.size() > 1) {
            // при нескольких — берём первое (при необходимости расширить UI выбором)
            cmd.add("-s"); cmd.add(devices.get(0));
        }
        cmd.add("shell"); cmd.add("am"); cmd.add("start"); cmd.add("-W");
        cmd.add("-a"); cmd.add("android.intent.action.VIEW");
        cmd.add("-d"); cmd.add(uri);

        List<String> out = new ArrayList<>();
        int code = exec(cmd.toArray(new String[0]), out);
        return new RunResult(code == 0, code, out);
    }

    // ===== helpers =====
    private List<String> listSerials() {
        List<String> out = new ArrayList<>();
        exec(new String[]{adb.toString(), "devices"}, out);
        List<String> serials = new ArrayList<>();
        for (String line : out) {
            String s = line.trim();
            if (s.startsWith("List of devices attached") || s.isEmpty()) continue;
            if (s.endsWith("device")) serials.add(s.split("\\s+")[0]);
        }
        return serials;
    }

    private static int exec(String[] cmd, List<String> out) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) out.add(line);
            }
            return p.waitFor();
        } catch (IOException | InterruptedException e) {
            out.add("ERR: " + e.getMessage());
            return -1;
        }
    }

    // ===== DTOs =====
    public record CheckResult(boolean ok, String hint, List<String> log) {}
    public record RunResult(boolean ok, int exitCode, List<String> log) {}
}
