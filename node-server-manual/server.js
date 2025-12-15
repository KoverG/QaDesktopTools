/* eslint-disable no-console */
const WebSocket = require("ws");
const fs        = require("fs");
const path      = require("path");
const crypto    = require("crypto");
const net       = require("net");
const readline  = require("readline");
const { rotateLogsOnStartup } = require("./log-rotate");

const ROOT_DIR       = __dirname;
const STATE_FILE     = path.join(ROOT_DIR, "clients.json");
const LOG_DIR        = path.join(ROOT_DIR, "log");
const INCOMING_LOG   = path.join(LOG_DIR, "incoming.log");
const OUTGOING_LOG   = path.join(LOG_DIR, "outgoing.log");
const SETTING_FILE   = path.join(ROOT_DIR, "setting", "setting.json");

// Глобальная карта ws -> clientId,
// чтобы можно было отправлять из обработчика stdin
const clients = new Map();

// Глобальный runtime-флаг приёма клиентов (как acceptUserClients в биржевом сервере)
let acceptClients = false;

// ===== low-level helpers =====

function ensureDir(p) {
    try { fs.mkdirSync(p, { recursive: true }); } catch { /* ignore */ }
}

function truncate(str, max = 300) {
    if (!str) return "";
    str = String(str);
    return str.length > max ? str.slice(0, max) + "..." : str;
}

function appendLog(file, line) {
    try {
        ensureDir(LOG_DIR);
        fs.appendFile(file, line + "\n", (err) => {
            if (err) {
                console.error("[MANUAL] log write error:", err.message);
            }
        });
    } catch (e) {
        console.error("[MANUAL] log write error (sync):", e.message);
    }
}

function nowIso() {
    return new Date().toISOString();
}

/**
 * Ping payload в WebSocket — всегда байты (0..125).
 * Мы берём строку из setting.json и кодируем UTF-8.
 * Если строка пустая — шлём пустой ping.
 */
function buildPingPayloadBuffer(raw) {
    const s = (raw == null ? "" : String(raw)).trim();
    if (!s) return Buffer.alloc(0);

    const buf = Buffer.from(s, "utf8");
    return buf.length > 125 ? buf.subarray(0, 125) : buf;
}

// ===== state: только clients[] в clients.json =====

function loadState() {
    try {
        const txt = fs.readFileSync(STATE_FILE, "utf8");
        const data = JSON.parse(txt);
        if (!Array.isArray(data.clients)) data.clients = [];
        // если там остался старый acceptClients — просто игнорируем
        return { clients: data.clients };
    } catch {
        return { clients: [] };
    }
}

function saveState(state) {
    try {
        ensureDir(path.dirname(STATE_FILE));
        const out = {
            clients: Array.isArray(state.clients) ? state.clients : []
        };
        fs.writeFileSync(STATE_FILE, JSON.stringify(out, null, 2), "utf8");
    } catch (e) {
        console.error("[MANUAL] failed to save clients.json:", e.message);
    }
}

function ensureState() {
    const st = loadState();
    if (!Array.isArray(st.clients)) st.clients = [];
    saveState(st);
}

// генерим clientId как в Java: UUID -> первые 8 символов
function genClientId() {
    try {
        const raw = crypto.randomUUID().replace(/-/g, "");
        return raw.slice(0, 8);
    } catch {
        // fallback
        return Math.random().toString(16).slice(2, 10);
    }
}

// ===== load settings.json =====

function loadSettings() {
    const DEFAULT = {
        port: 8080,
        rejectCode: 1013,
        rejectMessage: "Server is not accepting clients",
        pingPayload: "" // строка; пусто = пустой ping
    };

    try {
        const txt = fs.readFileSync(SETTING_FILE, "utf8");
        const data = JSON.parse(txt);

        const port =
            typeof data.port === "number" && data.port > 0
                ? data.port
                : DEFAULT.port;

        const rejectCode =
            typeof data.rejectCode === "number" && data.rejectCode >= 1000 && data.rejectCode <= 4999
                ? data.rejectCode
                : DEFAULT.rejectCode;

        const rejectMessage =
            typeof data.rejectMessage === "string" && data.rejectMessage.trim() !== ""
                ? data.rejectMessage.trim()
                : DEFAULT.rejectMessage;

        const pingPayload =
            typeof data.pingPayload === "string"
                ? data.pingPayload
                : DEFAULT.pingPayload;

        return { port, rejectCode, rejectMessage, pingPayload };
    } catch (e) {
        console.error("[MANUAL] failed to read setting.json, using defaults:", e.message);

        try {
            ensureDir(path.dirname(SETTING_FILE));
            fs.writeFileSync(
                SETTING_FILE,
                JSON.stringify(DEFAULT, null, 2),
                "utf8"
            );
        } catch { /* ignore */ }

        return DEFAULT;
    }
}

const SETTINGS       = loadSettings();
const PORT           = SETTINGS.port;
const REJECT_CODE    = SETTINGS.rejectCode;
const REJECT_MSG     = SETTINGS.rejectMessage;
const PING_PAYLOAD   = SETTINGS.pingPayload;

// ===== check port availability =====

function checkPortInUse(port, cb) {
    const server = net.createServer();

    server.once("error", (err) => {
        if (err.code === "EADDRINUSE") {
            cb(true);
        } else {
            console.error("[MANUAL] unexpected error while checking port:", err.message);
            cb(false);
        }
    });

    server.once("listening", () => {
        server.close(() => cb(false));
    });

    // слушаем только для проверки, потом сразу закрываем
    server.listen(port, () => {});
}

// ===== start ws server =====

function startServer() {
    ensureState();

    const wss = new WebSocket.Server({ port: PORT }, () => {
        console.log(`[MANUAL] Server listening ws://localhost:${PORT}`);
    });

    wss.on("connection", (ws, req) => {
        // как в биржевом сервере: используем runtime-флаг acceptClients в памяти,
        // никак не читаем его из файла
        if (!acceptClients) {
            console.log("[MANUAL] CLIENT_REJECTED acceptClients=false");
            appendLog(INCOMING_LOG, `${nowIso()} REJECT from=${req.socket.remoteAddress || "unknown"}`);

            ws.close(REJECT_CODE, REJECT_MSG);
            return;
        }

        // читаем текущее состояние только ради списка клиентов
        let state = loadState();

        // присваиваем clientId
        let id = genClientId();
        const existingIds = new Set(
            (state.clients || [])
                .map(c => (typeof c === "string" ? c : c && c.id).trim())
                .filter(Boolean)
        );
        while (existingIds.has(id)) {
            id = genClientId();
        }

        ws._clientId = id;
        clients.set(ws, id);

        // пишем в state и файл
        const newClients = (state.clients || []).filter(c => {
            const cid = typeof c === "string" ? c : (c && c.id);
            return cid && cid !== id;
        });
        newClients.push({ id });
        state.clients = newClients;
        saveState(state);

        console.log(`[MANUAL] CLIENT_CONNECTED id=${id}`);
        appendLog(INCOMING_LOG, `${nowIso()} CONNECT id=${id} from=${req.socket.remoteAddress || "unknown"}`);

        ws.on("message", (data) => {
            const text = data.toString("utf8");
            const cid  = ws._clientId || "unknown";

            console.log(`[MANUAL] CLIENT_MESSAGE id=${cid} payload=${truncate(text, 400)}`);
            appendLog(INCOMING_LOG, `${nowIso()} IN id=${cid} ${text}`);

            // БОЛЬШЕ НИКАКОГО ЭХО.
            // Сервер не отправляет клиенту ничего автоматически.
            // Ответы клиентам идут только по командам из stdin (SEND id=... / PING id=...).
        });

        // ===== ловим PONG (это НЕ message, поэтому раньше не отображалось) =====
        ws.on("pong", (data) => {
            const cid = ws._clientId || "unknown";

            let buf;
            try {
                buf = Buffer.isBuffer(data) ? data : Buffer.from(data || []);
            } catch {
                buf = Buffer.alloc(0);
            }

            const hex  = buf.length ? buf.toString("hex") : "";
            const text = buf.length ? buf.toString("utf8") : "";

            // форматируем с payload=..., чтобы Java extractPayload() работал как раньше
            const payload = hex
                ? `PONG hex=${hex}${text ? ` text="${text.replace(/\r/g, " ").replace(/\n/g, " ").trim()}"` : ""}`
                : "PONG";

            console.log(`[MANUAL] CLIENT_PONG id=${cid} payload=${payload}`);
            appendLog(INCOMING_LOG, `${nowIso()} PONG id=${cid} ${payload}`);
        });

        ws.on("close", (code, reason) => {
            const cid = ws._clientId || "unknown";

            // обновляем state: убираем клиента из списка
            let st = loadState();
            st.clients = (st.clients || []).filter(c => {
                const cid2 = typeof c === "string" ? c : (c && c.id);
                return cid2 && cid2 !== cid;
            });
            saveState(st);

            clients.delete(ws);
            console.log(`[MANUAL] CLIENT_DISCONNECTED id=${cid} code=${code}`);
            appendLog(INCOMING_LOG, `${nowIso()} CLOSE id=${cid} code=${code} reason=${reason || ""}`);
        });

        ws.on("error", (err) => {
            const cid = ws._clientId || "unknown";
            console.error("[MANUAL] ws error for", cid, ":", err.message);
            appendLog(INCOMING_LOG, `${nowIso()} ERROR id=${cid} ${err.message}`);
        });
    });

    wss.on("listening", () => {
        console.log("[MANUAL] Node manual server started");
    });

    wss.on("error", (err) => {
        console.error("[MANUAL] server error:", err.message);
    });

    // ===== stdin: команды от Java (WebSocketManualController) =====
    // Поддерживаем:
    //   ACCEPT on
    //   ACCEPT off
    //   SEND id=<clientId> { ...json... }
    //   PING id=<clientId>

    const rl = readline.createInterface({
        input: process.stdin,
        crlfDelay: Infinity
    });

    rl.on("line", (line) => {
        if (!line) return;
        line = line.trim();
        if (!line) return;

        const upper = line.toUpperCase();

        if (upper === "ACCEPT ON") {
            acceptClients = true;
            console.log("[MANUAL] ACCEPT_ON");
            return;
        }

        if (upper === "ACCEPT OFF") {
            acceptClients = false;
            console.log("[MANUAL] ACCEPT_OFF");
            return;
        }

        // PING id=<clientId>
        if (line.startsWith("PING ")) {
            const m = /^PING\s+id=([^\s]+)(?:\s+.*)?$/.exec(line);
            if (!m) {
                console.error("[MANUAL] bad PING command:", line);
                return;
            }

            const clientId = m[1];

            // ищем ws по clientId
            let target = null;
            for (const [ws, cid] of clients.entries()) {
                if (cid === clientId) {
                    target = ws;
                    break;
                }
            }

            if (!target || target.readyState !== WebSocket.OPEN) {
                console.error("[MANUAL] PING failed, client not found or not open:", clientId);
                return;
            }

            try {
                // ВАЖНО: это именно WebSocket Ping frame (opcode 0x9)
                // Payload берём как бинарные байты из UTF-8 строки pingPayload (до 125 байт).
                const payloadBuf = buildPingPayloadBuffer(PING_PAYLOAD);
                target.ping(payloadBuf);

                console.log(`[MANUAL] SERVER_PING id=${clientId}`);
                appendLog(
                    OUTGOING_LOG,
                    `${nowIso()} OUT id=${clientId} (PING) payloadText="${String(PING_PAYLOAD).trim()}" payloadHex=${payloadBuf.toString("hex")}`
                );
            } catch (e) {
                console.error("[MANUAL] PING error for", clientId, ":", e.message);
            }
            return;
        }

        // ожидаем команды вида:
        //   SEND id=<clientId> { ...json... }
        if (!line.startsWith("SEND ")) {
            return;
        }

        const m = /^SEND\s+id=([^\s]+)\s+(.+)$/.exec(line);
        if (!m) {
            console.error("[MANUAL] bad SEND command:", line);
            return;
        }

        const clientId = m[1];
        const payload  = m[2];

        // ищем ws по clientId
        let target = null;
        for (const [ws, cid] of clients.entries()) {
            if (cid === clientId) {
                target = ws;
                break;
            }
        }

        if (!target || target.readyState !== WebSocket.OPEN) {
            console.error("[MANUAL] SEND failed, client not found or not open:", clientId);
            return;
        }

        try {
            target.send(payload);
            console.log(`[MANUAL] SERVER_SEND id=${clientId} payload=${truncate(payload, 400)}`);
            appendLog(OUTGOING_LOG, `${nowIso()} OUT id=${clientId} ${payload}`);
        } catch (e) {
            console.error("[MANUAL] SEND error for", clientId, ":", e.message);
        }
    });

    rl.on("close", () => {
        console.log("[MANUAL] stdin closed, no more SEND commands.");
    });
}

// ===== entry point =====

// 1) при каждом запуске проверяем смену дня и архивируем логи
rotateLogsOnStartup({
    logDir: LOG_DIR,
    incomingLog: INCOMING_LOG,
    outgoingLog: OUTGOING_LOG
});

// 2) дальше обычный старт
checkPortInUse(PORT, (inUse) => {
    if (inUse) {
        console.log(`[MANUAL] PORT_IN_USE port=${PORT}`);
        // ВАЖНО: не падаем с EADDRINUSE, просто выходим.
        process.exit(0);
    } else {
        startServer();
    }
});
