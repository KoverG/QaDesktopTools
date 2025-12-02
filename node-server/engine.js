// FILE: node-server/engine.js
const WebSocket = require("ws");
const fs = require("fs");
const path = require("path");

// ===== Paths =====
class Engine {
    constructor(cfg) {
        this.ROOT_DIR       = cfg.rootDir;
        this.SETTING_DIR    = cfg.settingDir;
        this.WS_CONFIG      = cfg.wsConfigFile;

        this.SERVER_PROTOCOL_FILE = path.resolve(this.ROOT_DIR, "setting/private/protocol/priv_server-protocol.json");
        this.CLIENT_PROTOCOL_FILE = path.resolve(this.ROOT_DIR, "setting/private/protocol/priv_client-project-protocol.json");
        this.ORDERBOOK_DIR        = path.resolve(this.ROOT_DIR, "setting/private/orderbook");

        this.MSG_CFG_FILE   = path.join(this.SETTING_DIR, "setting.json"); // url/controlUrl/zeroFlash/...
        this.VARS_DEFAULTS  = path.join(this.SETTING_DIR, "payloads", "variables", "defaults.json");

        this.OB_DIR         = path.join(this.SETTING_DIR, "private", "orderbook");
        this.OB_DEFAULT     = path.join(this.OB_DIR, "priv_subscribe_default.json");
        this.OB_UPD         = path.join(this.OB_DIR, "priv_subscribe_upd.json");

        this.PORT = 8080;
        this.portOverride = cfg.portOverride ?? null;

        // ===== State =====
        this.wsConfig = null;
        this.runtime  = null;
        this.acceptUserClients = false;

        // ws server
        this.wss = null;

        // subscribe/agg state
        this.subscriptions = new Map(); // ws -> instrument (как прислал клиент)
        this.bookState     = new Map(); // ws -> { instrument, base:{bid[],ask[]}, current:{bid:Map, ask:Map} }
        this.aggState      = new Map(); // ws -> { bid: Map(price -> number[]), ask: Map(price -> number[]) }
    }

    // ===== Utils =====
    readJson(file) {
        const s = fs.readFileSync(file, "utf8");
        try { return JSON.parse(s); } catch (e) { throw new Error(`[FATAL] ${file} invalid JSON: ${e.message}`); }
    }
    exists(p) { try { return fs.existsSync(p); } catch { return false; } }
    readJsonOrNull(file) {
        try {
            if (!fs.existsSync(file)) return null;
            const txt = fs.readFileSync(file, "utf8");
            return JSON.parse(txt);
        } catch (e) {
            console.warn(`[WARN] readJsonOrNull failed for ${file}:`, e.message);
            return null;
        }
    }
    writeJsonPretty(file, obj) {
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, JSON.stringify(obj, null, 2), "utf8");
    }
    loadJsonOrExit(filePath, name) {
        try {
            const data = fs.readFileSync(filePath, "utf8");
            return JSON.parse(data);
        } catch (e) {
            console.error(`Ошибка загрузки ${name} (${filePath}):`, e);
            process.exit(1);
        }
    }

    // >>> ВСТАВЛЕННЫЙ МЕТОД <<<
    // Собирает control URL из базового url + path + query-параметров
    buildControlUrl(urlBase, urlControlPath, urlControlParams) {
        let base = urlBase || "ws://localhost:8080";

        // убираем хвостовые слэши
        if (typeof base === "string") {
            base = base.replace(/\/+$/, "");
        }

        // добавляем path
        if (typeof urlControlPath === "string" && urlControlPath.trim() !== "") {
            let p = urlControlPath.trim();
            if (!p.startsWith("/")) p = "/" + p;
            base += p;
        }

        // добавляем query-параметры
        if (urlControlParams && typeof urlControlParams === "object") {
            const entries = Object.entries(urlControlParams)
                .filter(([k]) => typeof k === "string" && k.trim() !== "");

            if (entries.length > 0) {
                const esc = encodeURIComponent;
                const qs = entries
                    .map(([k, v]) => esc(k) + "=" + esc(v != null ? String(v) : ""))
                    .join("&");
                base += (base.includes("?") ? "&" : "?") + qs;
            }
        }

        return base;
    }
    // <<< КОНЕЦ ВСТАВКИ >>>

    deepInterpolate(node, vars) {
        if (node == null) return node;
        if (typeof node === "string") return node.replace(/\$\{(\w+)\}/g, (_, k) => (vars && k in vars ? String(vars[k]) : ""));
        if (Array.isArray(node)) return node.map(n => this.deepInterpolate(n, vars));
        if (typeof node === "object") {
            const out = {};
            for (const [k, v] of Object.entries(node)) out[k] = this.deepInterpolate(v, vars);
            return out;
        }
        return node;
    }
    setByPath(obj, dotPath, value) {
        const parts = dotPath.split(".");
        let cur = obj;
        for (let i = 0; i < parts.length - 1; i++) {
            const k = parts[i];
            if (typeof cur[k] !== "object" || cur[k] === null) cur[k] = {};
            cur = cur[k];
        }
        cur[parts[parts.length - 1]] = value;
        return obj;
    }

    getByPathWithIndex(obj, dotPath) {
        if (!obj || !dotPath) return undefined;
        const parts = dotPath.split(".");
        let cur = obj;

        for (const part of parts) {
            if (cur == null) return undefined;

            const m = part.match(/^([^\[\]]+)(?:\[(\d+)\])?$/);
            if (!m) return undefined;

            const key = m[1];
            const idx = m[2] != null ? parseInt(m[2], 10) : null;

            cur = cur[key];
            if (cur == null) return undefined;

            if (idx != null) {
                if (!Array.isArray(cur) || idx < 0 || idx >= cur.length) return undefined;
                cur = cur[idx];
            }
        }
        return cur;
    }

    // ===== Wrappers + Orderbook =====
    loadWrapper(wrapperName, vars) {
        try {
            const proto = this.readJson(this.SERVER_PROTOCOL_FILE);
            const wrap = proto?.wrappers?.[wrapperName];
            if (!wrap) return null;
            return this.deepInterpolate(JSON.parse(JSON.stringify(wrap)), vars || {});
        } catch {
            return null;
        }
    }

    // Логика выбора имени инструмента для Subscribe (без "mode")
    resolveSubscribeInstrument(iConfig, vars, entry) {
        const clientInst = vars && vars.instrument;
        const entryInst  = entry && entry.i;

        // Строка в wrapper'е — используем как есть (fixed)
        if (typeof iConfig === "string" && iConfig.trim() !== "") {
            return iConfig;
        }

        // По умолчанию — как было: из vars.instrument или из entry.i
        return clientInst || entryInst || "";
    }

    // Универсальный нормализатор OB-структур из файла
    normalizeObData(raw) {
        if (!raw) return null;
        if (Array.isArray(raw)) return raw;
        if (raw && Array.isArray(raw.ob)) return raw.ob;
        if (raw && (raw.ask || raw.bid)) return [{ i: "", ask: raw.ask || [], bid: raw.bid || [] }];
        return null;
    }

    loadObDataForScenario(scn) {
        const prefer   = path.join(this.ORDERBOOK_DIR, `priv_subscribe_${scn}.json`);
        const fallback = this.OB_DEFAULT; // priv_subscribe_default.json

        const file = this.exists(prefer) ? prefer : fallback;
        if (!this.exists(file)) {
            console.warn(`[WARN] orderbook file not found: ${file}`);
            return null;
        }

        let raw = null;
        try {
            raw = this.readJson(file);
        } catch (e) {
            console.warn(`[WARN] bad orderbook JSON in ${file}: ${e.message}`);
            return null;
        }

        const ob = this.normalizeObData(raw);
        if (!ob) {
            console.warn(`[WARN] ${file} must be {ask,bid} | {ob:[...]} | [...]`);
            return null;
        }
        return ob;
    }

    // ===== сборка ответа по wrapper 'subscribe' =====
    buildSubscribePayload(scn, vars) {
        const obArr   = this.loadObDataForScenario(scn);
        const wrapper = this.loadWrapper("subscribe", vars);
        if (!wrapper && !obArr) {
            return this.system(
                "ProtocolNotFound",
                "Wrapper 'subscribe' and orderbook data not found",
                { scenario: scn }
            );
        }
        if (!wrapper) {
            return this.system(
                "ProtocolNotFound",
                "Wrapper 'subscribe' not found in priv_server-protocol.json",
                { scenario: scn }
            );
        }
        if (!obArr || !Array.isArray(obArr) || obArr.length === 0) {
            return this.system(
                "Undefined",
                "Orderbook data not found for scenario",
                { scenario: scn }
            );
        }

        const iConfig =
            wrapper &&
            wrapper.p &&
            Array.isArray(wrapper.p.ob) &&
            wrapper.p.ob[0] &&
            typeof wrapper.p.ob[0] === "object"
                ? wrapper.p.ob[0].i
                : null;

        const mode       = this.instrumentSourceMode();            // "client" | "fixed"
        const clientInst = vars && vars.instrument;

        const materialized = obArr.map(entry => {
            let i;
            if (mode === "client") {
                // в клиентском режиме: всегда из клиента (или из entry, если есть)
                i = clientInst || entry.i || "";
            } else {
                // в fixed-режиме остаётся старая логика (берём i из wrapper'а, если задан)
                i = this.resolveSubscribeInstrument(iConfig, vars, entry);
            }
            return { i, ask: entry.ask || [], bid: entry.bid || [] };
        });

        this.setByPath(wrapper, "p.ob", materialized);
        return wrapper;
    }


    // ===== Runtime/Config =====
    buildRuntimeConfig() {
        const cfg = this.readJsonOrNull(this.MSG_CFG_FILE) || {};

        // базовый URL сервера
        const urlBase = cfg.url || "ws://localhost:8080";

        // новый формат: urlControlPath + urlControlParams
        const hasPath = typeof cfg.urlControlPath === "string" && cfg.urlControlPath.trim() !== "";
        const hasParams =
            cfg.urlControlParams &&
            typeof cfg.urlControlParams === "object" &&
            Object.keys(cfg.urlControlParams).length > 0;

        let controlUrl;
        if (hasPath || hasParams) {
            // новый формат: собираем из url + path + params
            controlUrl = this.buildControlUrl(urlBase, cfg.urlControlPath, cfg.urlControlParams);
        } else if (cfg.controlUrl) {
            // старый формат: готовый controlUrl в конфиге
            controlUrl = cfg.controlUrl;
        } else {
            // дефолт: /control?control=1
            controlUrl = this.buildControlUrl(urlBase, "/control", { control: "1" });
        }

        const url = urlBase;
        const zeroFlash  = !!cfg.zeroFlash;
        const instrumentSource = cfg.instrumentSource === "fixed" ? "fixed" : "client";

        const vars = this.readJsonOrNull(this.VARS_DEFAULTS) || {};
        if (!vars.instrument) vars.instrument = "{{instrument}}";

        const templates = {};
        // Ответ на hello — по wrapper 'hello' из priv_server-protocol.json
        templates.hello       = this.loadWrapper("hello");
        // Ответ на unsubscribe — по wrapper 'unsubscribe'
        templates.unsubscribe = this.loadWrapper("unsubscribe");
        // Поток котировок — по wrapper 'quote'
        templates.quote       = this.loadWrapper("quote");

        // проверим наличие базового файла OB
        const obDefaultProbe = this.readJsonOrNull(this.OB_DEFAULT);
        if (!obDefaultProbe) {
            console.error(`[FATAL] ${this.OB_DEFAULT} не найден`);
            process.exit(1);
        }

        const obUpdRaw = this.readJsonOrNull(this.OB_UPD);
        const obUpdArr = this.normalizeObData(obUpdRaw);
        if (Array.isArray(obUpdArr) && obUpdArr.length > 0) {
            const wrap = this.loadWrapper("subscribe");
            if (wrap) {
                const iConfig =
                    wrap &&
                    wrap.p &&
                    Array.isArray(wrap.p.ob) &&
                    wrap.p.ob[0] &&
                    typeof wrap.p.ob[0] === "object"
                        ? wrap.p.ob[0].i
                        : null;

                const materialized = obUpdArr.map(entry => {
                    let i;
                    if (instrumentSource === "client") {
                        // кладём плейсхолдер, чтобы потом fillTemplate()
                        // подставил конкретный instrument из контекста
                        i = "{{instrument}}";
                    } else {
                        // fixed-режим: как раньше, используем i из wrapper'а
                        i = this.resolveSubscribeInstrument(iConfig, null, entry);
                    }
                    return { i, ask: entry.ask || [], bid: entry.bid || [] };
                });

                this.setByPath(wrap, "p.ob", materialized);
                templates.subscribe_upd = wrap;
            }
        }

        return { url, controlUrl, zeroFlash, instrumentSource, vars, templates };
    }


    reloadWsConfig() {
        const ok = this.readJsonOrNull(this.WS_CONFIG);
        if (ok) this.wsConfig = ok;
        return !!ok;
    }
    reloadRuntime() {
        this.runtime = this.buildRuntimeConfig();
        console.log("[DEBUG] runtime reloaded:", {
            url: this.runtime.url,
            controlUrl: this.runtime.controlUrl,
            zeroFlash: this.runtime.zeroFlash,
            instrumentSource: this.runtime.instrumentSource,
            hasUpd: !!this.runtime.templates.subscribe_upd
        });
    }

    currentScenarioId() { return this.wsConfig.current_scenario || "default"; }
    listScenarioIds()   { return Object.keys((this.wsConfig && this.wsConfig.scenarios) || {}); }
    applyScenarioAndPersist(newScenarioId) {
        const scenarios = (this.wsConfig && this.wsConfig.scenarios) || {};
        if (!scenarios[newScenarioId]) {
            if (newScenarioId === "upd") {
                scenarios.upd = { bid: true, ask: true };
                this.wsConfig.scenarios = scenarios;
            } else {
                throw new Error(`Неизвестный сценарий: ${newScenarioId}`);
            }
        }
        if (this.wsConfig.current_scenario !== newScenarioId) {
            this.wsConfig.current_scenario = newScenarioId;
            this.writeJsonPretty(this.WS_CONFIG, this.wsConfig);
            console.log("[EVENT] scenario changed ?", newScenarioId);
        }
    }

    // ===== Template helpers =====
    fillTemplate(node, ctx) {
        if (Array.isArray(node)) return node.map(n => this.fillTemplate(n, ctx));
        if (node && typeof node === "object") {
            const out = {}; for (const [k, v] of Object.entries(node)) out[k] = this.fillTemplate(v, ctx);
            return out;
        }
        if (typeof node === "string") return node.replace(/\{\{(\w+)\}\}/g, (_, k) => (k in ctx ? String(ctx[k]) : _));
        return node;
    }
    buildMessage(type, { ws, id, rid, context = {} } = {}) {
        const tpl = this.runtime.templates[type];
        if (!tpl) throw new Error(`Не найден шаблон '${type}'`);
        if (!id) id = this.nextId(ws);

        // сначала как раньше — применяем шаблон
        const body = this.fillTemplate(tpl, context);

        // спец-обработка только для hello (HelloResp)
        if (type === "hello") {
            // p из шаблона (там лежит session.field)
            const pTpl = tpl.p || {};
            const sessionMeta = pTpl.session;

            if (sessionMeta && typeof sessionMeta.field === "string") {
                const realFieldName = sessionMeta.field;      // напр. "ClientId"
                const sessionValue  = context.sessionId;      // нейтральное имя

                if (!body.p || typeof body.p !== "object") {
                    body.p = {};
                }

                // в ответе будет: p.{ClientId} = <sessionValue>
                body.p[realFieldName] = sessionValue;

                // мету session наружу не пускаем
                if ("session" in body.p) {
                    delete body.p.session;
                }
            }

        }

        return { id, ...(rid ? { rid } : {}), ...body };
    }

    nextId(ws) { ws._msgCounter = (ws?._msgCounter || 0) + 1; return String(ws._msgCounter); }

    stringifyWithMinusZero(obj) {
        let s = JSON.stringify(obj);
        s = s.replace(
            /("ask"\s*:\s*\[\s*)([\s\S]*?)(\s*\])/g,
            (m, head, body, tail) => {
                const patchedBody = body.replace(/("p"\s*:\s*)0(\s*[,\}])/g, '$1-0$2');
                return head + patchedBody + tail;
            }
        );
        return s;
    }
    trySend(ws, payload) {
        if (ws.readyState !== WebSocket.OPEN) return false;
        try {
            const json = this.stringifyWithMinusZero(payload);
            ws.send(json);
            return true;
        } catch { return false; }
    }
    zeroFlashEnabled() { return !!this.runtime.zeroFlash; }
    instrumentTemplate() {
        try { return this.runtime.vars.instrument || "{{instrument}}"; } catch { return "{{instrument}}"; }
    }
    instrumentSourceMode() {
        try { return this.runtime.instrumentSource || "client"; } catch { return "client"; }
    }

    // ===== Broadcast & Status =====
    isUser(ws) { return !ws._isControl; }
    isActiveUser(ws) { return this.isUser(ws) && ws._isUserActive === true; }
    countUsersActive() {
        let n = 0; this.wss.clients.forEach(c => { if (c.readyState === WebSocket.OPEN && this.isActiveUser(c)) n++; });
        return n;
    }
    listActiveClientIds() {
        const ids = [];
        this.wss.clients.forEach(ws => {
            if (ws.readyState !== WebSocket.OPEN) return;
            if (!this.isUser(ws)) return;
            if (ws._isUserActive && ws._session_Id) ids.push(ws._session_Id);
        });
        return ids;
    }
    notifyClientsClosed(ids) {
        const payload = { id: String(Date.now()), t: "ClientsClosed", clients: ids };
        this.wss.clients.forEach(ws => { if (ws.readyState === WebSocket.OPEN && ws._isControl) this.trySend(ws, payload); });
    }
    broadcastStatusToControls() {
        const payload = {
            id: String(Date.now()), t: "Status",
            scenario: this.currentScenarioId(), clientsActive: this.countUsersActive(), accept: this.acceptUserClients
        };
        this.wss.clients.forEach(ws => { if (ws.readyState === WebSocket.OPEN && ws._isControl) this.trySend(ws, payload); });
        console.log("[DEBUG] broadcast Status ?", payload);
    }
    logToControls(message) {
        const payload = { id: String(Date.now()), t: "Log", message };
        this.wss.clients.forEach(ws => { if (ws.readyState === WebSocket.OPEN && ws._isControl) this.trySend(ws, payload); });
    }
    closeAllUsers(code = 1000, reason = "scenario_change") {
        const ids = this.listActiveClientIds();
        this.notifyClientsClosed(ids);
        const users = Array.from(this.wss.clients).filter(ws => ws.readyState === WebSocket.OPEN && this.isUser(ws));
        console.log(`[CONTROL] closeAllUsers: closing ${users.length} user clients`);
        for (const c of users) { try { c.close(code, reason); } catch {} }
        setTimeout(() => { for (const c of users) if (c.readyState !== WebSocket.CLOSED) { try { c.terminate(); } catch {} } }, 1000);
    }

    // ===== Heartbeat =====
    setupHeartbeat(ws) {
        const PING_HEX = "70696e67";
        ws.isAlive = true;
        ws.on("pong", () => {
            ws.isAlive = true;
            if (ws._pongTimeout) { clearTimeout(ws._pongTimeout); ws._pongTimeout = null; }
        });
        const hb = this.wsConfig.heartbeat || {};
        const interval = hb.interval_ms || 120000;
        const timeout  = hb.timeout_ms  || 15000;
        ws._pingInterval = setInterval(() => {
            if (ws.readyState !== WebSocket.OPEN) return;
            ws.isAlive = false;
            try { ws.ping(Buffer.from(PING_HEX, "hex")); } catch {}
            ws._pongTimeout = setTimeout(() => { if (!ws.isAlive) { try { ws.terminate(); } catch {} } }, timeout);
        }, interval);
        ws.on("close", () => {
            if (ws._pingInterval) clearInterval(ws._pingInterval);
            if (ws._pongTimeout)  clearTimeout(ws._pongTimeout);
        });
    }

    // ===================== CLIENT PROTOCOL =====================

    // Универсальный билдер клиентских сообщений по wrapperName (project.wrappers)
    buildClientMessage(wrapperName, context = {}) {
        try {
            const clientProto = this.readJson(this.CLIENT_PROTOCOL_FILE);
            const project = clientProto?.project || clientProto;
            const wrapper = project?.wrappers?.[wrapperName];

            if (!wrapper) {
                console.warn(`[WARN] Client wrapper '${wrapperName}' not found`);
                return null;
            }
            return this.deepInterpolate(JSON.parse(JSON.stringify(wrapper)), context);
        } catch (error) {
            console.warn(`[WARN] Failed to build client message '${wrapperName}':`, error);
            return null;
        }
    }

    // Обработка входящих USER-сообщений
    handleClientMessage(ws, msg) {
        try {
            const clientProto = this.readJson(this.CLIENT_PROTOCOL_FILE);
            const project  = clientProto?.project || clientProto;
            const paths    = project?.paths    || {};
            const wrappers = project?.wrappers || {};

            const msgType = msg.t;

            // ==== сопоставление типа протокола: type ("HelloReq") -> wrapperName ("HelloMessage") ====
            let wrapperName = null;
            for (const [wName, def] of Object.entries(wrappers)) {
                if (def && typeof def.t === "string" && def.t === msgType) {
                    wrapperName = wName;
                    break;
                }
            }

            // Если тип не найден в обёртках — просто игнорируем
            if (!wrapperName) {
                console.warn(`[WARN] Unknown user message type: ${msgType} (no wrapper matched)`);
                return;
            }

            // ----- HelloMessage -----
            if (wrapperName === "HelloMessage" && msg.id) {
                ws._session_Id = Math.random().toString(16).slice(2);
                ws._isUserActive = true;

                const resp = this.buildMessage("hello", {
                    ws,
                    rid: msg.id,
                    context: { sessionId: ws._session_Id }
                });
                this.trySend(ws, resp);
                this.broadcastStatusToControls();
                return;
            }

            // ----- SubscribeMessage -----
            if (wrapperName === "SubscribeMessage" && msg.id) {
                // 1) вытаскиваем instrument по paths из client-protocol.project.paths
                let instrument = null;
                const pathExpr = paths["Subscribe.instrument"];
                if (typeof pathExpr === "string" && pathExpr.trim() !== "") {
                    instrument = this.getByPathWithIndex(msg, pathExpr.trim());
                }
                // fallback на старую схему, если в paths нет или не сработало
                if (!instrument) {
                    instrument = msg.p?.Instruments?.[0];
                }
                if (!instrument) return;

                // 2) работаем с конфигом сценария/подписки
                this.reloadWsConfig();
                this.reloadRuntime();

                const scnId  = this.currentScenarioId();
                const hasUpd = !!this.runtime.templates.subscribe_upd;

                let resp;
                if (scnId === "upd" && hasUpd) {
                    // вариант через готовый шаблон (id,rid,t,p выставляются buildMessage)
                    resp = this.buildMessage("subscribe_upd", {
                        ws,
                        rid: msg.id,
                        context: { instrument }
                    });
                } else {
                    // вариант: строим тело (t/p), затем вручную оборачиваем в id/rid
                    const body = this.buildSubscribePayload(scnId, { instrument });
                    resp = { id: this.nextId(ws), rid: msg.id, ...body };
                }

                if (resp && resp.t === "System") {
                    this.trySend(ws, resp);
                    return;
                }

                // 3) применяем сценарные фильтры (bid-only / ask-only / оба / none)
                const scn   = this.wsConfig.scenarios?.[scnId] || this.wsConfig.scenarios.default || {};
                const bidOn = !!scn.bid;
                const askOn = !!scn.ask;

                const ob = resp?.p?.ob?.[0];
                if (ob && typeof ob === "object") {
                    if (!bidOn) delete ob.bid;
                    if (!askOn) delete ob.ask;

                    if (!this.zeroFlashEnabled()) {
                        if (Array.isArray(ob.bid)) ob.bid = ob.bid.filter(l => Number(l?.v) !== 0);
                        if (Array.isArray(ob.ask)) ob.ask = ob.ask.filter(l => Number(l?.v) !== 0);
                    }
                }

                // 4) сохраняем состояние и помечаем пользователя активным
                this.subscriptions.set(ws, instrument);
                this.initBookStateFromSubscribe(ws, instrument, resp);

                ws._isUserActive = true;
                this.trySend(ws, resp);
                this.broadcastStatusToControls();
                return;
            }

            // ----- UnsubscribeMessage -----
            if (wrapperName === "UnsubscribeMessage" && msg.id) {
                this.subscriptions.delete(ws);
                this.bookState.delete(ws);
                this.aggState.delete(ws);
                ws._isUserActive = false;
                this.broadcastStatusToControls();

                const resp = this.buildMessage("unsubscribe", {
                    ws,
                    rid: msg.id
                });
                this.trySend(ws, resp);
                return;
            }

            console.warn(
                `[WARN] Unknown user wrapper: ${wrapperName} (msg.t=${msgType})`
            );
        } catch (error) {
            console.error("[ERROR] Error handling client message:", error);
        }
    }

    // ===== Order book + Agg state =====
    getAgg(ws) {
        let a = this.aggState.get(ws);
        if (!a) { a = { bid: new Map(), ask: new Map() }; this.aggState.set(ws, a); }
        return a;
    }

    // теперь учитывает instrumentSource (client|fixed)
    initBookStateFromSubscribe(ws, instrumentFromClient, subscribePayload) {
        const ob0 = subscribePayload?.p?.ob?.[0];

        // по умолчанию — как прислал клиент
        let instrumentFinal = instrumentFromClient;

        // если режим fixed — берём реальный i из SubscribeResp, если он строка
        if (this.instrumentSourceMode() === "fixed") {
            if (ob0 && typeof ob0.i === "string" && ob0.i.trim() !== "") {
                instrumentFinal = ob0.i;
            }
        }

        const sideArr = side => (ob0?.[side] || []).map(x => ({ p: +x.p, v: +x.v }));
        const base = { bid: sideArr("bid"), ask: sideArr("ask") };
        const current = {
            bid: new Map(base.bid.map(l => [l.p, l.v])),
            ask: new Map(base.ask.map(l => [Math.abs(l.p), l.v])) // ASK по |p|
        };
        this.bookState.set(ws, { instrument: instrumentFinal, base, current });
        this.aggState.set(ws, { bid: new Map(), ask: new Map() });
    }
    getState(ws) { return this.bookState.get(ws) || null; }

    STEP = 0.001;
    round3(x){ return Math.round(x * 1000) / 1000; }
    nowTs(){ return Date.now(); }

    avgVolumeFromBase(state, side) {
        const vals = (state.base[side] || []).map(x => +x.v).filter(v => v > 0);
        if (!vals.length) return 1;
        const avg = vals.reduce((a,b)=>a+b,0) / vals.length;
        return Math.max(1, Math.round(avg));
    }
    jitterLike(avg){ return Math.max(1, Math.round(avg * (0.9 + Math.random()*0.2))); }

    nextBidPrices(state, count) {
        const prices = [...state.current.bid.keys()];
        const max = prices.length ? Math.max(...prices) : 0;
        const out = []; let cur = max;
        while (out.length < count) { cur = this.round3(cur + this.STEP); if (!state.current.bid.has(cur)) out.push(cur); }
        return out;
    }
    nextAskPrices(state, count) {
        const prices = [...state.current.ask.keys()];
        const max = prices.length ? Math.max(...prices) : this.STEP;
        const out = []; let cur = max;
        while (out.length < count) { cur = this.round3(cur + this.STEP); if (!state.current.ask.has(cur)) out.push(cur); }
        return out;
    }
    prevBidPrices(state, count) {
        const prices = [...state.current.bid.keys()];
        const min = prices.length ? Math.min(...prices) : this.STEP;
        const out = []; let cur = min, guard = 20000;
        while (out.length < count && guard-- > 0) { cur = this.round3(cur - this.STEP); if (cur <= 0) break; if (!state.current.bid.has(cur)) out.push(cur); }
        return out;
    }
    prevAskPrices(state, count) {
        const prices = [...state.current.ask.keys()];
        if (!prices.length) return [];
        const min = Math.min(...prices);
        const out = []; let cur = min, guard = 20000;
        while (out.length < count && guard-- > 0) { cur = this.round3(cur - this.STEP); if (cur <= 0) break; if (!state.current.ask.has(cur)) out.push(cur); }
        return out;
    }

    collectItems(state, volFn, t) {
        const inst = state.instrument, items = [];
        for (const [p, v] of state.current.bid.entries()) items.push({ i: inst, p, v: volFn(v,"bid",p), t });
        for (const [p, v] of state.current.ask.entries()) items.push({ i: inst, p: -Math.abs(p), v: volFn(v,"ask",p), t });
        return items;
    }
    zeroFlushItems(state, t) { return this.collectItems(state, () => 0, t); }
    buildAfterWithAgg(ws, state, t) {
        const inst = state.instrument;
        const a = this.getAgg(ws);

        const bidPrices = [...state.current.bid.keys()].sort((x, y) => y - x);
        const askPrices = [...state.current.ask.keys()].sort((x, y) => x - y);

        const items = [];
        for (const p of bidPrices) {
            const vBase = state.current.bid.get(p);
            items.push({ i: inst, p, v: vBase, t });
            const arr = a.bid.get(p) || [];
            for (const aggV of arr) items.push({ i: inst, p, v: aggV, t });
        }
        for (const p of askPrices) {
            const vBase = state.current.ask.get(p);
            const pNeg  = -Math.abs(p);
            items.push({ i: inst, p: pNeg, v: vBase, t });
            const arr = a.ask.get(p) || [];
            for (const aggV of arr) items.push({ i: inst, p: pNeg, v: aggV, t });
        }
        return items;
    }
    buildPreZeroWithOps(state, ops, t) {
        const inst = state.instrument;
        const bidSet = new Set([...state.current.bid.keys()]);
        const askSet = new Set([...state.current.ask.keys()]);

        if (Array.isArray(ops)) {
            for (const op of ops) {
                if (!op) continue;
                const side  = (op.side === "ask") ? "ask" : "bid";
                const price = +op.price;
                const vol   = +op.volume;
                if (!Number.isFinite(price) || price <= 0) continue;
                if (!Number.isFinite(vol)   || vol   <= 0) continue;
                if (side === "bid") bidSet.add(price); else askSet.add(price);
            }
        }

        const bidPrices = [...bidSet].sort((a, b) => b - a);
        const askPrices = [...askSet].sort((a, b) => a - b);

        const items = [];
        for (const p of bidPrices) items.push({ i: inst, p,              v: 0, t });
        for (const p of askPrices) items.push({ i: inst, p: -Math.abs(p), v: 0, t });
        return items;
    }

    // ===== Поток котировок по wrapper 'quote' =====
    quoteRespFlushThenSnapshot(ws, preZeroItems, afterItems) {
        const zf = this.zeroFlashEnabled();

        // 1) каркас из priv_server-protocol.json (wrapper "quote")
        const quoteWrap = this.loadWrapper("quote");

        // 2) собираем тело
        const bodyArray = zf ? [...preZeroItems, ...afterItems]
            : afterItems.filter(x => Number(x.v) !== 0);

        // 3) финальный payload (id впереди)
        const payload = { id: this.nextId(ws), ...quoteWrap, p: bodyArray };

        this.trySend(ws, payload);
    }

    // массовая транзакция
    sendTransactionalToAll(mutator) {
        this.wss.clients.forEach(ws => {
            if (ws.readyState !== WebSocket.OPEN || ws._isControl) return;
            const instrument = this.subscriptions.get(ws); if (!instrument) return;
            const state = this.getState(ws); if (!state) return;

            const t = this.nowTs();
            const preZero = this.zeroFlushItems(state, t);

            try { mutator(ws, state); }
            catch (e) { console.error("[ERROR] mutator failed:", e?.message || e); }

            const after = this.buildAfterWithAgg(ws, state, t);
            this.quoteRespFlushThenSnapshot(ws, preZero, after);
        });
    }

    jitter10p(val){ const f = 0.9 + Math.random() * 0.2; return Math.max(1, Math.round(val * f)); }
    aggregateOnLevel(ws, state, side, where) {
        const map = state.current[side];
        if (!map || map.size === 0) return;
        const keys = [...map.keys()];
        const price = (side === "bid")
            ? (where === "top" ? Math.max(...keys) : Math.min(...keys))
            : (where === "top" ? Math.min(...keys) : Math.max(...keys));
        const curVol = +map.get(price);
        if (!Number.isFinite(curVol) || curVol <= 0) return;
        const delta = this.jitter10p(curVol);
        const a = this.getAgg(ws);
        const arr = (a[side].get(price) || []);
        arr.push(delta);
        a[side].set(price, arr);
    }
    aggregateBoth(ws, state, where) { this.aggregateOnLevel(ws, state, "bid", where); this.aggregateOnLevel(ws, state, "ask", where); }
    aggregateAllLevels(ws, state, side) {
        const map = state.current[side];
        if (!map || map.size === 0) return;
        const a = this.getAgg(ws);
        for (const [price, vol] of map.entries()) {
            const delta = this.jitter10p(+vol);
            const arr = (a[side].get(price) || []);
            arr.push(delta);
            a[side].set(price, arr);
        }
    }
    aggregateAllBoth(ws, state) { this.aggregateAllLevels(ws, state, "bid"); this.aggregateAllLevels(ws, state, "ask"); }

    clearSideWithAgg(ws, state, side) { state.current[side].clear(); this.getAgg(ws)[side].clear(); }
    clearAllWithAgg(ws, state) {
        state.current.bid.clear(); state.current.ask.clear();
        const a = this.getAgg(ws); a.bid.clear(); a.ask.clear();
    }
    removeOneTopWithAgg(ws, state, side) {
        const keys = [...state.current[side].keys()];
        if (!keys.length) return;
        const price = (side === "bid") ? Math.max(...keys) : Math.min(...keys);
        state.current[side].delete(price); this.getAgg(ws)[side].delete(price);
    }
    removeOneBottomWithAgg(ws, state, side) {
        const keys = [...state.current[side].keys()];
        if (!keys.length) return;
        const price = (side === "bid") ? Math.min(...keys) : Math.max(...keys);
        state.current[side].delete(price); this.getAgg(ws)[side].delete(price);
    }

    clearAggOnLevel(ws, state, side, where) {
        const map = state.current[side];
        if (!map || map.size === 0) return;
        const keys = [...map.keys()];
        const price = (side === "bid")
            ? (where === "top" ? Math.max(...keys) : Math.min(...keys))
            : (where === "top" ? Math.min(...keys) : Math.max(...keys));
        const a = this.getAgg(ws);
        const arr = a[side].get(price) || [];
        if (arr.length === 0) { this.logToControls(`Агрегации по уровню ${side === 'bid' ? 'Bid' : 'Ask'} нет`); return; }
        arr.pop(); a[side].set(price, arr);
    }
    clearAggBoth(ws, state, where) { this.clearAggOnLevel(ws, s, "bid", where); this.clearAggOnLevel(ws, s, "ask", where); }
    clearAggAllSide(ws, state, side) { const a = this.getAgg(ws); a[side].clear(); }
    clearAggAllBoth(ws, state) { const a = this.getAgg(ws); a.bid.clear(); a.ask.clear(); }

    addSideLevels(state, side, count) {
        if (state.current[side].size === 0) {
            for (const { p, v } of (state.base[side] || [])) {
                const priceToStore = side === "ask" ? Math.abs(p) : p;
                state.current[side].set(priceToStore, +v);
            }
            return;
        }
        const avg = this.avgVolumeFromBase(state, side);
        const prices = side === "bid" ? this.nextBidPrices(state, count) : this.nextAskPrices(state, count);
        for (const p of prices) state.current[side].set(p, this.jitterLike(avg));
    }
    addOneTop(state, side) {
        if (state.current[side].size === 0) { this.addSideLevels(state, side, 1); return; }
        const avg = this.avgVolumeFromBase(state, side);
        if (side === "bid") {
            const prices = this.nextBidPrices(state, 1);
            for (const p of prices) state.current[side].set(p, this.jitterLike(avg));
        } else {
            const prices = this.prevAskPrices(state, 1);
            for (const p of prices) state.current[side].set(p, this.jitterLike(avg));
        }
    }
    addOneBottom(state, side) {
        if (state.current[side].size === 0) { this.addSideLevels(state, side, 1); return; }
        const avg = this.avgVolumeFromBase(state, side);
        if (side === "bid") {
            const prices = this.prevBidPrices(state, 1);
            if (!prices.length) {
                const min = Math.min(...[...state.current.bid.keys()]);
                const newPrice = this.round3(min - this.STEP);
                if (newPrice > 0) state.current.bid.set(newPrice, this.jitterLike(avg));
                return;
            }
            for (const p of prices) state.current[side].set(p, this.jitterLike(avg));
        } else {
            const prices = this.nextAskPrices(state, 1);
            for (const p of prices) state.current[side].set(p, this.jitterLike(avg));
        }
    }

    applyQuoteRespTemplateToState(state, payload) {
        if (!payload || !Array.isArray(payload.p)) return;
        const arr = this.zeroFlashEnabled() ? payload.p : payload.p.filter(q => Number(q?.v) !== 0);

        for (const q of arr) {
            if (!q) continue;
            let price = Number(q.p);
            let vol   = Number(q.v);
            if (!Number.isFinite(price) || !Number.isFinite(vol)) continue;

            const isAsk = price < 0;
            const side  = isAsk ? "ask" : "bid";
            const other = isAsk ? "bid" : "ask";
            const pAbs  = Math.abs(price);

            if (vol <= 0) {
                state.current.bid.delete(pAbs);
                state.current.ask.delete(pAbs);
            } else {
                state.current[side].set(pAbs, vol);
                state.current[other].delete(pAbs);
            }
        }
    }

    // теперь завязан на state.instrument, а не напрямую на this.subscriptions
    sendQuoteUpdateTemplateBasedRaw() {
        this.wss.clients.forEach(ws => {
            if (ws.readyState !== WebSocket.OPEN || ws._isControl) return;

            let state = this.getState(ws);
            if (!state) {
                const instrumentFromMap = this.subscriptions.get(ws);
                if (!instrumentFromMap) return;
                state = {
                    instrument: instrumentFromMap,
                    base: { bid: [], ask: [] },
                    current: { bid: new Map(), ask: new Map() }
                };
                this.bookState.set(ws, state);
            }

            const instrument = state.instrument;

            try {
                // строим payload по шаблону wrapper "quote" (runtime.templates.quote)
                const payload = this.buildMessage("quote", { ws, context: { instrument } });

                // нормализуем p:[] и применяем к bookState
                payload.p = Array.isArray(payload.p) ? payload.p : [];
                this.applyQuoteRespTemplateToState(state, payload);

                const out = this.zeroFlashEnabled()
                    ? payload
                    : { ...payload, p: (payload.p || []).filter(x => Number(x?.v) !== 0) };
                this.trySend(ws, out);
            } catch (e) {
                console.error("Ошибка при построении сообщения quote:", e);
            }
        });
    }

    // ===== SYSTEM message =====
    system(code, message, extra = {}) { return { t: "System", code, message, ts: Date.now(), ...extra }; }

    // ===== WS Server =====
    startWs() {
        const fromCfg = Number(this.wsConfig?.port) || 8080;
        this.PORT = this.portOverride || fromCfg;

        this.wss = new WebSocket.Server({ port: this.PORT });
        this.wss.on("listening", () => console.log("[EVENT] listening ws://localhost:" + this.PORT));

        this.wss.on("connection", (ws, req) => {
            ws._isControl = /\bcontrol=1\b/.test(req?.url || "");
            ws._isUserActive = false;

            if (!ws._isControl && !this.acceptUserClients) { try { ws.close(1008, "accept_disabled"); } catch {} return; }

            this.setupHeartbeat(ws);
            console.log(`[CONNECT] ${ws._isControl ? "control" : "user"} socket opened, accept=${this.acceptUserClients}`);
            this.broadcastStatusToControls();

            ws.on("message", (raw) => {
                let msg; try { msg = JSON.parse(String(raw)); } catch { return; }

                // ----- CONTROL -----
                if (msg.t === "ControlHello") {
                    ws._isControl = true;
                    this.reloadWsConfig();
                    this.reloadRuntime();
                    this.acceptUserClients = true;
                    this.broadcastStatusToControls();
                    return this.trySend(ws, {
                        id: this.nextId(ws), t: "Status",
                        scenario: this.currentScenarioId(), clientsActive: this.countUsersActive(), accept: this.acceptUserClients
                    });
                }

                if (msg.t === "GetConfig") {
                    this.reloadWsConfig();
                    return this.trySend(ws, { id: this.nextId(ws), t: "Config", scenarios: this.listScenarioIds(), current: this.currentScenarioId() });
                }

                if (msg.t === "GetStatus") {
                    this.reloadWsConfig();
                    return this.trySend(ws, { id: this.nextId(ws), t: "Status", scenario: this.currentScenarioId(), clientsActive: this.countUsersActive(), accept: this.acceptUserClients });
                }

                if (msg.t === "SetScenario") {
                    const scn = String(msg.scenario || "").trim();
                    try {
                        this.applyScenarioAndPersist(scn);

                        this.acceptUserClients = false;
                        this.closeAllUsers(1000, "scenario_change");
                        this.broadcastStatusToControls();

                        setTimeout(() => {
                            this.acceptUserClients = true;
                            this.broadcastStatusToControls();
                        }, 200);

                        return this.trySend(ws, { id: this.nextId(ws), t: "ScenarioSet", scenario: scn });
                    } catch (e) {
                        return this.trySend(ws, { id: this.nextId(ws), t: "Error", message: e.message || String(e) });
                    }
                }

                if (msg.t === "Control") {
                    const cmd = String(msg.cmd || "");

                    if (cmd === "acceptOn")  { this.acceptUserClients = true;  this.broadcastStatusToControls(); return; }
                    if (cmd === "acceptOff") { this.acceptUserClients = false; this.broadcastStatusToControls(); return; }

                    if (cmd === "closeAll")   { const ids = this.listActiveClientIds(); this.notifyClientsClosed(ids);
                        this.wss.clients.forEach(c => { try { c.close(1000,"server_closeAll"); } catch {} });
                        this.broadcastStatusToControls(); return; }
                    if (cmd === "closeUsers") { this.closeAllUsers(1000, "manual_disconnect"); this.broadcastStatusToControls(); return; }

                    if (cmd === "manualQuote") {
                        const ops = Array.isArray(msg.ops) ? msg.ops : [];
                        const replaceCurrent = !!msg.replaceCurrent;
                        return this.sendManualQuoteToAll(ops, replaceCurrent);
                    }

                    // --- массовые ---
                    if (cmd === "quoteClearBid")  return this.sendTransactionalToAll((ws,s) => this.clearSideWithAgg(ws, s, "bid"));
                    if (cmd === "quoteClearAsk")  return this.sendTransactionalToAll((ws,s) => this.clearSideWithAgg(ws, s, "ask"));
                    if (cmd === "quoteClearAll")  return this.sendTransactionalToAll((ws,s) => this.clearAllWithAgg(ws, s));
                    if (cmd === "quoteAddBid")    return this.sendTransactionalToAll((ws,s) => this.addSideLevels(s, "bid", 3));
                    if (cmd === "quoteAddAsk")    return this.sendTransactionalToAll((ws,s) => this.addSideLevels(s, "ask", 3));
                    if (cmd === "quoteAddBoth")   return this.sendTransactionalToAll((ws,s) => { this.addSideLevels(s, "bid", 3); this.addSideLevels(s, "ask", 3); });

                    // --- точечные уровни ---
                    if (cmd === "quoteAddBidTop1")     return this.sendTransactionalToAll((ws,s) => this.addOneTop(s, "bid"));
                    if (cmd === "quoteAddBidBottom1")  return this.sendTransactionalToAll((ws,s) => this.addOneBottom(s, "bid"));
                    if (cmd === "quoteAddAskTop1")     return this.sendTransactionalToAll((ws,s) => this.addOneTop(s, "ask"));
                    if (cmd === "quoteAddAskBottom1")  return this.sendTransactionalToAll((ws,s) => this.addOneBottom(s, "ask"));

                    if (cmd === "quoteDelBidTop1")     return this.sendTransactionalToAll((ws,s) => this.removeOneTopWithAgg(ws, s, "bid"));
                    if (cmd === "quoteDelBidBottom1")  return this.sendTransactionalToAll((ws,s) => this.removeOneBottomWithAgg(ws, s, "bid"));
                    if (cmd === "quoteDelAskTop1")     return this.sendTransactionalToAll((ws,s) => this.removeOneTopWithAgg(ws, s, "ask"));
                    if (cmd === "quoteDelAskBottom1")  return this.sendTransactionalToAll((ws,s) => this.removeOneBottomWithAgg(ws, s, "ask"));

                    if (cmd === "quoteAddBothTop1")
                        return this.sendTransactionalToAll((ws, s) => { this.addOneTop(s, "bid"); this.addOneTop(s, "ask"); });
                    if (cmd === "quoteAddBothBottom1")
                        return this.sendTransactionalToAll((ws, s) => { this.addOneBottom(s, "bid"); this.addOneBottom(s, "ask"); });
                    if (cmd === "quoteDelBothTop1")
                        return this.sendTransactionalToAll((ws, s) => { this.removeOneTopWithAgg(ws, s, "bid"); this.removeOneTopWithAgg(ws, s, "ask"); });
                    if (cmd === "quoteDelBothBottom1")
                        return this.sendTransactionalToAll((ws, s) => { this.removeOneBottomWithAgg(ws, s, "bid"); this.removeOneBottomWithAgg(ws, s, "ask"); });

                    // --- Agg добавление ---
                    if (cmd === "quoteAggBidUp")    return this.sendTransactionalToAll((ws,s) => this.aggregateOnLevel(ws, s, "bid", "top"));
                    if (cmd === "quoteAggBidDown")  return this.sendTransactionalToAll((ws,s) => this.aggregateOnLevel(ws, s, "bid", "bottom"));
                    if (cmd === "quoteAggAskUp")    return this.sendTransactionalToAll((ws,s) => this.aggregateOnLevel(ws, s, "ask", "top"));
                    if (cmd === "quoteAggAskDown")  return this.sendTransactionalToAll((ws,s) => this.aggregateOnLevel(ws, s, "ask", "bottom"));
                    if (cmd === "quoteAggBothUp")   return this.sendTransactionalToAll((ws,s) => this.aggregateBoth(ws, s, "top"));
                    if (cmd === "quoteAggBothDown") return this.sendTransactionalToAll((ws,s) => this.aggregateBoth(ws, s, "bottom"));
                    if (cmd === "quoteAggBidAll")   return this.sendTransactionalToAll((ws,s) => this.aggregateAllLevels(ws, s, "bid"));
                    if (cmd === "quoteAggAskAll")   return this.sendTransactionalToAll((ws,s) => this.aggregateAllLevels(ws, s, "ask"));
                    if (cmd === "quoteAggBothAll")  return this.sendTransactionalToAll((ws,s) => this.aggregateAllBoth(ws, s));

                    // --- Agg очистка ---
                    if (cmd === "quoteAggClearBidTop")   return this.sendTransactionalToAll((ws,s) => this.clearAggOnLevel(ws, s, "bid", "top"));
                    if (cmd === "quoteAggClearBidBot")   return this.sendTransactionalToAll((ws,s) => this.clearAggOnLevel(ws, s, "bid", "bottom"));
                    if (cmd === "quoteAggClearAskTop")   return this.sendTransactionalToAll((ws,s) => this.clearAggOnLevel(ws, s, "ask", "top"));
                    if (cmd === "quoteAggClearAskBot")   return this.sendTransactionalToAll((ws,s) => this.clearAggOnLevel(ws, s, "ask", "bottom"));
                    if (cmd === "quoteAggClearBothTop")  return this.sendTransactionalToAll((ws,s) => this.clearAggBoth(ws, s, "top"));
                    if (cmd === "quoteAggClearBothBot")  return this.sendTransactionalToAll((ws,s) => this.clearAggBoth(ws, s, "bottom"));
                    if (cmd === "quoteAggClearBidAll")   return this.sendTransactionalToAll((ws,s) => this.clearAggAllSide(ws, s, "bid"));
                    if (cmd === "quoteAggClearAskAll")   return this.sendTransactionalToAll((ws,s) => this.clearAggAllSide(ws, s, "ask"));
                    if (cmd === "quoteAggClearBothAll")  return this.sendTransactionalToAll((ws,s) => this.clearAggAllBoth(ws, s));

                    if (cmd === "saveSubscribeUpd") {
                        try {
                            let anyState = null;
                            for (const [, st] of this.bookState.entries()) { anyState = st; break; }

                            const ask = [];
                            const bid = [];

                            if (anyState) {
                                const t = this.nowTs();

                                const asks = Array.from(anyState.current.ask.entries()).sort((a,b)=>a[0]-b[0]);
                                for (const [p, v] of asks) {
                                    const rec = { p: -Math.abs(p), v: Number(v)||0, y: 0, t };
                                    if (this.zeroFlashEnabled() || rec.v !== 0) ask.push(rec);
                                }

                                const bids = Array.from(anyState.current.bid.entries()).sort((a,b)=>b[0]-a[0]);
                                for (const [p, v] of bids) {
                                    const rec = { p: Math.abs(p), v: Number(v)||0, y: 0, t };
                                    if (this.zeroFlashEnabled() || rec.v !== 0) bid.push(rec);
                                }
                            }

                            const fmtArr = arr => arr.map(o =>
                                `    { "p": ${o.p}, "v": ${o.v}, "y": ${o.y}, "t": ${o.t} }`
                            ).join(",\n");

                            const raw =
                                `{
  "ask": [
${fmtArr(ask)}
  ],
  "bid": [
${fmtArr(bid)}
  ]
}`.replace(/("p":\s*)0(\s*[,\}])/g, '$1-0$2');

                            fs.mkdirSync(path.dirname(this.OB_UPD), { recursive: true });
                            fs.writeFileSync(this.OB_UPD, raw + "\n", "utf8");

                            this.reloadRuntime();
                            this.logToControls(`[CONTROL] subscribe_upd saved ? ${this.OB_UPD}`);
                        } catch (e) {
                            return this.trySend(ws, {
                                id: this.nextId(ws),
                                t: "Error",
                                message: "saveSubscribeUpd failed: " + (e?.message || e)
                            });
                        }
                        return;
                    }

                    if (cmd === "quoteUpdate") return this.sendQuoteUpdateTemplateBasedRaw();

                    return this.trySend(ws, { id: this.nextId(ws), t: "Error", message: `Неизвестная команда: ${cmd}` });
                }

                // ----- USER -----
                if (!ws._isControl) {
                    this.handleClientMessage(ws, msg);
                }

            });

            ws.on("close", (code, reason) => {
                this.subscriptions.delete(ws);
                this.bookState.delete(ws);
                this.aggState.delete(ws);
                ws._isUserActive = false;
                this.broadcastStatusToControls();
                console.log(`[CLOSE] ${ws._isControl ? "control" : "user"} code=${code} reason=${reason}`);
            });
        });
    }

    // ===== manualQuote =====
    sendManualQuoteToAll(ops, replaceCurrent) {
        this.wss.clients.forEach(ws => {
            if (ws.readyState !== WebSocket.OPEN || ws._isControl) return;
            const instrument = this.subscriptions.get(ws); if (!instrument) return;
            const state = this.getState(ws); if (!state) return;

            const t = this.nowTs();
            const preZero = this.buildPreZeroWithOps(state, ops, t);

            try {
                for (const op of ops) {
                    const side  = (op.side === "ask") ? "ask" : "bid";
                    const price = +op.price, volume = +op.volume;
                    if (!Number.isFinite(price) || price <= 0) continue;
                    if (!Number.isFinite(volume) || volume <= 0) continue;

                    const map = state.current[side];
                    if (map.has(price)) {
                        if (replaceCurrent) { map.set(price, volume); }
                        else {
                            const a = this.getAgg(ws);
                            const arr = a[side].get(price) || [];
                            arr.push(volume);
                            a[side].set(price, arr);
                        }
                    } else {
                        map.set(price, volume);
                    }
                }
            } catch (e) { console.error("[ERROR] manualQuote mutator failed:", e?.message || e); }

            const after = this.buildAfterWithAgg(ws, state, t);
            this.quoteRespFlushThenSnapshot(ws, preZero, after);
        });
    }

    // ===== lifecycle =====
    async start() {
        this.wsConfig = this.loadJsonOrExit(this.WS_CONFIG, "ws-config");
        this.runtime  = this.buildRuntimeConfig();

        console.log("[INIT] settings loaded from:", {
            ws_config: this.WS_CONFIG,
            url: this.runtime.url,
            controlUrl: this.runtime.controlUrl,
            zeroFlash: this.runtime.zeroFlash,
            instrumentSource: this.runtime.instrumentSource
        });

        this.startWs();
    }
}

module.exports = { Engine };
