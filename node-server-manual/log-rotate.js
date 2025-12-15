// FILE: node-server-manual/log-rotate.js
/* eslint-disable no-console */
const fs   = require("fs");
const path = require("path");

function ensureDir(p) {
    try { fs.mkdirSync(p, { recursive: true }); } catch { /* ignore */ }
}

function pad2(n) { return String(n).padStart(2, "0"); }

function localDayStr(d = new Date()) {
    // локальная дата (не UTC), чтобы смена дня соответствовала ОС
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

function safeReadText(p) {
    try { return fs.readFileSync(p, "utf8"); } catch { return null; }
}

function safeWriteText(p, s) {
    try {
        ensureDir(path.dirname(p));
        fs.writeFileSync(p, String(s ?? ""), "utf8");
    } catch (e) {
        console.error("[MANUAL] failed to write day marker:", e.message);
    }
}

function safeStat(p) {
    try { return fs.statSync(p); } catch { return null; }
}

function inferDayFromLogs(incomingLogPath, outgoingLogPath) {
    const stIn  = safeStat(incomingLogPath);
    const stOut = safeStat(outgoingLogPath);

    const tIn  = stIn ? stIn.mtime : null;
    const tOut = stOut ? stOut.mtime : null;

    // берём дату по более "старому" mtime (похоже на день, когда писались логи)
    if (tIn && tOut) return localDayStr(tIn < tOut ? tIn : tOut);
    if (tIn) return localDayStr(tIn);
    if (tOut) return localDayStr(tOut);
    return null;
}

function ensureFileExists(p) {
    try {
        ensureDir(path.dirname(p));
        if (!fs.existsSync(p)) fs.writeFileSync(p, "", "utf8");
    } catch (e) {
        console.error("[MANUAL] failed to ensure log file:", p, e.message);
    }
}

// Берём день из строки лога вида: 2025-12-13T12:57:55.454Z ...
function dayFromLogLine(line) {
    // строго ISO-префикс
    if (!line || line.length < 11) return null;
    // YYYY-MM-DDT...
    const m = /^(\d{4}-\d{2}-\d{2})T/.exec(line);
    return m ? m[1] : null;
}

function splitLogFileByDay(filePath, dayToArchive) {
    const txt = safeReadText(filePath);
    if (!txt) {
        return { archiveLines: [], keepLines: [] };
    }

    // сохраняем строки как есть (без потери пустых строк)
    const lines = txt.split(/\r?\n/);

    const archiveLines = [];
    const keepLines = [];

    for (const line of lines) {
        if (line === "") {
            // пустые строки: оставляем в текущем файле, чтобы не "съедать" форматирование
            keepLines.push(line);
            continue;
        }

        const day = dayFromLogLine(line);

        if (day === dayToArchive) {
            archiveLines.push(line);
        } else if (day) {
            // это строки другого дня (например today) — оставляем
            keepLines.push(line);
        } else {
            // строка без ISO-даты: чтобы не потерять, оставляем в текущем файле
            keepLines.push(line);
        }
    }

    // убираем возможный хвост пустых строк из archive, чтобы не создавать "пустой" файл
    while (archiveLines.length && archiveLines[archiveLines.length - 1] === "") archiveLines.pop();

    return { archiveLines, keepLines };
}

/**
 * Ротация логов при старте с защитой от "смешанных дней" в одном файле.
 *
 * Логика:
 * - если lastDay == today: НИЧЕГО НЕ ЧИСТИМ (это и есть "один день = один файл", переживает рестарты)
 * - если lastDay != today:
 *    - из incoming/outgoing выделяем строки lastDay -> пишем в archive/lastDay/{incoming|outgoing}.log
 *    - всё, что не lastDay (включая today) -> остаётся в новых incoming/outgoing.log (не теряется)
 * - затем .current-day.txt = today
 */
function rotateLogsOnStartup(o) {
    const logDir        = o.logDir;
    const incomingLog   = o.incomingLog;
    const outgoingLog   = o.outgoingLog;
    const archiveDir    = o.archiveDir || path.join(logDir, "archive");
    const dayMarkerFile = o.dayMarkerFile || path.join(logDir, ".current-day.txt");

    try {
        ensureDir(logDir);
        ensureDir(archiveDir);

        ensureFileExists(incomingLog);
        ensureFileExists(outgoingLog);

        const today = localDayStr();

        let lastDay = safeReadText(dayMarkerFile);
        lastDay = lastDay ? String(lastDay).trim() : "";

        if (!lastDay) {
            lastDay = inferDayFromLogs(incomingLog, outgoingLog) || today;
        }

        // ? ВАЖНО: если день тот же — НЕ чистим файлы (переживаем рестарты)
        if (lastDay === today) {
            safeWriteText(dayMarkerFile, today + "\n");
            return;
        }

        // day changed: архивируем только строки lastDay
        const archiveSubDir = path.join(archiveDir, lastDay);
        ensureDir(archiveSubDir);

        // incoming
        const inSplit = splitLogFileByDay(incomingLog, lastDay);
        if (inSplit.archiveLines.length > 0) {
            fs.writeFileSync(
                path.join(archiveSubDir, "incoming.log"),
                inSplit.archiveLines.join("\n") + "\n",
                "utf8"
            );
        }
        fs.writeFileSync(incomingLog, inSplit.keepLines.join("\n"), "utf8");

        // outgoing
        const outSplit = splitLogFileByDay(outgoingLog, lastDay);
        if (outSplit.archiveLines.length > 0) {
            fs.writeFileSync(
                path.join(archiveSubDir, "outgoing.log"),
                outSplit.archiveLines.join("\n") + "\n",
                "utf8"
            );
        }
        fs.writeFileSync(outgoingLog, outSplit.keepLines.join("\n"), "utf8");

        console.log(`[MANUAL] LOG_ROTATE day=${lastDay} -> ${today} archivedTo=${archiveSubDir}`);

        // обновляем маркер на сегодня
        safeWriteText(dayMarkerFile, today + "\n");
    } catch (e) {
        console.error("[MANUAL] rotateLogsOnStartup failed:", e.message);
        // в случае ошибки — хотя бы обновим маркер (чтобы не зациклиться),
        // но файлы не трогаем, чтобы не потерять данные
        try {
            safeWriteText(dayMarkerFile, localDayStr() + "\n");
        } catch { /* ignore */ }
    }
}

module.exports = { rotateLogsOnStartup };
