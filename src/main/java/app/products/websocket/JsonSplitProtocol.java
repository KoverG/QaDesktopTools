// FILE: src/main/java/app/products/websocket/JsonSplitProtocol.java
package app.products.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonSplitProtocol implements WsProtocol {
    private static final ObjectMapper M = new ObjectMapper();
    private static final Pattern VAR_DOLLAR = Pattern.compile("\\$\\{([a-zA-Z0-9_\\.\\-]+)}");
    private static final Pattern VAR_BRACES = Pattern.compile("\\{\\{([a-zA-Z0-9_\\.\\-]+)}}");

    private final Path settingsDir;

    // Новый формат: два файла протокола
    private final Path systemProtocolJson;
    private final Path projectProtocolJson;

    // Корни JSON-деревьев для system и project
    private JsonNode systemRoot;
    private JsonNode projectRoot;

    private Map<String, OutDef> outDefs = Map.of();
    private Map<String, InDef> inDefs = Map.of();
    // Порядок логических ключей out, которые надо отправить при старте соединения
    private List<String> connectStartMessages = List.of();
    private Map<String, JsonNode> wrappers = Map.of(); // wrappers из system + project

    private final Map<String, Object> ctx = new HashMap<>();

    public JsonSplitProtocol(Path settingsDir, Map<String, Object> defaults) {
        this.settingsDir = Objects.requireNonNull(settingsDir);

        // Файл с system-частью протокола (обязательный)
        this.systemProtocolJson = settingsDir
                .resolve("private")
                .resolve("protocol")
                .resolve("priv_client-system-protocol.json");

        // Файл с project-частью (опциональный)
        this.projectProtocolJson = settingsDir
                .resolve("private")
                .resolve("protocol")
                .resolve("priv_client-project-protocol.json");

        if (!Files.exists(systemProtocolJson)) {
            throw new IllegalStateException("priv_client-system-protocol.json not found: " + systemProtocolJson);
        }

        reload();
        if (defaults != null) {
            ctx.putAll(defaults);
        }
    }

    public void reload() {
        try {
            // ===== system-root из priv_client-system-protocol.json =====
            String sysJson = Files.readString(systemProtocolJson, StandardCharsets.UTF_8);
            systemRoot = M.readTree(sysJson);
            if (systemRoot == null || !systemRoot.isObject()) {
                throw new IllegalStateException("Root of " + systemProtocolJson + " must be a JSON object");
            }

            // ===== project-root из priv_client-project-protocol.json (опционален) =====
            if (Files.exists(projectProtocolJson)) {
                String projJson = Files.readString(projectProtocolJson, StandardCharsets.UTF_8);
                projectRoot = M.readTree(projJson);
                if (projectRoot == null || !projectRoot.isObject()) {
                    projectRoot = M.createObjectNode();
                }
            } else {
                projectRoot = M.createObjectNode();
            }

            // ==== wrappers: сначала system.wrappers, потом project.wrappers ====
            Map<String, JsonNode> wrappersMap = new LinkedHashMap<>();

            JsonNode systemWrappers = systemRoot.path("wrappers");
            if (systemWrappers.isObject()) {
                systemWrappers.fields().forEachRemaining(e -> wrappersMap.put(e.getKey(), e.getValue()));
            }

            JsonNode projectWrappers = projectRoot.path("wrappers");
            if (projectWrappers.isObject()) {
                projectWrappers.fields().forEachRemaining(e -> {
                    // project не перетирает system при совпадении имён
                    wrappersMap.putIfAbsent(e.getKey(), e.getValue());
                });
            }

            this.wrappers = wrappersMap;

            // ==== connectStartMessages (из systemRoot.connectStartMessages) ====
            List<String> start = new ArrayList<>();
            JsonNode arr = systemRoot.path("connectStartMessages");
            if (arr.isArray()) {
                arr.forEach(n -> start.add(n.asText()));
            }
            this.connectStartMessages = List.copyOf(start);

            // ==== out (из systemRoot.out), передаём wrappers в OutDef.parse ====
            Map<String, OutDef> out = new LinkedHashMap<>();
            JsonNode outNode = systemRoot.path("out");
            if (outNode.isObject()) {
                outNode.fields().forEachRemaining(e ->
                        out.put(e.getKey(), OutDef.parse(e.getKey(), e.getValue(), wrappers))
                );
            }
            this.outDefs = out;

            // ==== in (из systemRoot.in) ====
            Map<String, InDef> in = new LinkedHashMap<>();
            JsonNode inNode = systemRoot.path("in");
            if (inNode.isObject()) {
                inNode.fields().forEachRemaining(e ->
                        in.put(e.getKey(), InDef.parse(e.getKey(), e.getValue()))
                );
            }
            this.inDefs = in;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load protocol: system=" + systemProtocolJson
                    + ", project=" + projectProtocolJson, e);
        }
    }

    // ===== WsProtocol методы =====
    @Override
    public String decorateUrl(String baseUrl) {
        // URL уже полностью собирается в setting.json / WebSocketController,
        // поэтому тут ничего не добавляем.
        return baseUrl;
    }

    @Override
    public List<String> initialHandshakeMessages() {
        List<String> msgs = new ArrayList<>();
        for (String key : connectStartMessages) {
            OutDef d = outDefs.get(key);
            if (d != null) msgs.add(renderOut(d, Map.of()));
        }
        return msgs;
    }

    @Override
    public String build(String key, Map<String, Object> args) {
        OutDef d = outDefs.get(key);
        if (d == null) throw new IllegalArgumentException("Unknown out key: " + key);
        return renderOut(d, args == null ? Map.of() : args);
    }

    @Override
    public void onInbound(String text, Consumer<String> log) {
        try {
            JsonNode n = M.readTree(text);
            String t = n.path("t").asText(null);
            if (t != null) {
                for (InDef d : inDefs.values()) {
                    if (t.equals(d.matchT)) {
                        if (!d.capture.isEmpty()) {
                            for (var c : d.capture.entrySet()) {
                                JsonNode v = getByPath(n, c.getKey());
                                if (v != null && !v.isNull()) {
                                    ctx.put(c.getValue(), v.isTextual() ? v.asText() : v.toString());
                                }
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        if (log != null) log.accept(text);
    }

    // ===== helpers =====
    private String renderOut(OutDef d, Map<String, Object> args) {
        if (d.template != null) {
            Object rendered = deepInterpolateWithTypes(d.template, args);
            try {
                return M.writeValueAsString(rendered);
            } catch (Exception e) {
                throw new RuntimeException("Render error for key=" + d.key, e);
            }
        }
        return "{}";
    }

    private Object deepInterpolateWithTypes(Object node, Map<String, Object> args) {
        if (node instanceof String) {
            String s = (String) node;
            Matcher m = VAR_DOLLAR.matcher(s);
            if (m.matches()) {
                String varName = m.group(1);
                Object value = getVariableValue(varName, args);
                return value;
            } else {
                return replaceDollarVarInString(s, args);
            }
        } else if (node instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) node;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), deepInterpolateWithTypes(entry.getValue(), args));
            }
            return result;
        } else if (node instanceof List) {
            List<Object> result = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) node;
            for (Object item : list) {
                result.add(deepInterpolateWithTypes(item, args));
            }
            return result;
        }
        return node;
    }

    private Object getVariableValue(String varName, Map<String, Object> args) {
        if (args != null && args.containsKey(varName)) {
            return args.get(varName);
        }
        if (ctx.containsKey(varName)) {
            return ctx.get(varName);
        }
        return "";
    }

    private String replaceDollarVarInString(String s, Map<String, Object> args) {
        Matcher m = VAR_DOLLAR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1);
            Object value = getVariableValue(varName, args);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Map<String, Object> loadAndSubstPayload(String relPath, Map<String, Object> args) {
        try {
            Path p = settingsDir.resolve("payloads").resolve(relPath);
            String json = Files.readString(p, StandardCharsets.UTF_8);
            json = substBraces(json, args);
            return M.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Payload load error: " + relPath, e);
        }
    }

    private static JsonNode getByPath(JsonNode n, String path) {
        JsonNode cur = n;
        for (String p : path.split("\\.")) {
            if (cur == null) return null;
            cur = cur.get(p);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private Object wrapInject(Map<String, Object> wrap, Map<String, Object> payload) {
        return deepReplace(wrap, s -> s.equals("${payload}") ? payload : replaceDollarVarInString(s, Map.of("payload", payload)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> src) {
        return M.convertValue(src, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepReplace(Map<String, Object> node, java.util.function.Function<String, Object> repl) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : node.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) out.put(e.getKey(), repl.apply(s));
            else if (v instanceof Map<?, ?> m) out.put(e.getKey(), deepReplace((Map<String, Object>) m, repl));
            else if (v instanceof List<?> a) out.put(e.getKey(), deepReplaceArr(a, repl));
            else out.put(e.getKey(), v);
        }
        return out;
    }

    private List<Object> deepReplaceArr(List<?> arr, java.util.function.Function<String, Object> repl) {
        List<Object> out = new ArrayList<>(arr.size());
        for (Object v : arr) {
            if (v instanceof String s) out.add(repl.apply(s));
            else if (v instanceof Map<?, ?> m) out.add(deepReplace((Map<String, Object>) m, repl));
            else if (v instanceof List<?> a) out.add(deepReplaceArr(a, repl));
            else out.add(v);
        }
        return out;
    }

    private String substBraces(String text, Map<String, Object> args) {
        Matcher m = VAR_BRACES.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            Object v = (args != null && args.containsKey(name)) ? args.get(name) : (ctx.containsKey(name) ? ctx.get(name) : "");
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ==== Модели ====
    private static final class OutDef {
        final String key;
        final Map<String, Object> template;
        final Map<String, Object> wrap;
        final String payloadPath;

        OutDef(String key, Map<String, Object> template, Map<String, Object> wrap, String payloadPath) {
            this.key = key;
            this.template = template;
            this.wrap = wrap;
            this.payloadPath = payloadPath;
        }

        static OutDef parse(String key, JsonNode n, Map<String, JsonNode> wrappers) {
            Map<String, Object> template = null;
            if (n.has("template")) {
                JsonNode templateNode = n.get("template");
                if (templateNode.isTextual()) {
                    String wrapperName = templateNode.asText();
                    JsonNode wrapperNode = wrappers.get(wrapperName);
                    if (wrapperNode != null) {
                        template = M.convertValue(wrapperNode, new TypeReference<Map<String, Object>>() {});
                    } else {
                        throw new RuntimeException("Wrapper not found: " + wrapperName);
                    }
                } else {
                    template = M.convertValue(templateNode, new TypeReference<Map<String, Object>>() {});
                }
            }

            Map<String, Object> wrap = n.has("wrap")
                    ? M.convertValue(n.get("wrap"), new TypeReference<Map<String, Object>>() {})
                    : null;
            String payload = n.has("payload") ? n.get("payload").asText() : null;
            return new OutDef(key, template, wrap, payload);
        }
    }

    private static final class InDef {
        final String key;
        final String matchT;
        final Map<String, String> capture;

        InDef(String key, String matchT, Map<String, String> capture) {
            this.key = key;
            this.matchT = matchT;
            this.capture = capture;
        }

        static InDef parse(String key, JsonNode n) {
            String matchT = n.path("match").path("t").asText(null);
            Map<String, String> capture = new LinkedHashMap<>();
            JsonNode cap = n.path("capture");
            if (cap.isObject()) cap.fields().forEachRemaining(e -> capture.put(e.getKey(), e.getValue().asText()));
            return new InDef(key, matchT, capture);
        }
    }
}
