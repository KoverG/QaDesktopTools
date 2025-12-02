#!/usr/bin/env node
// pack-src.js — мини-сборщик "всё в один файл"
// Maven + JavaFX (src) + config + node-server + timestamp
// В НАЧАЛЕ: список всех включённых файлов.

// ====================== НАСТРОЙКА ======================

// Белый список директорий (относительно корня репозитория)
const WHITELIST_DIRS = [
    'src',
    'config',
    'node-server'
];

// Белый список отдельных файлов (относительно корня)
const WHITELIST_FILES = [
    'pom.xml'
];

// Чёрный список директорий по ИМЕНИ (игнорируются на любом уровне
// внутри белых директорий)
const BLACKLIST_DIR_NAMES = new Set([
    'node_modules',
    '.git',
    '.idea',
    '.mvn',
    'logs',
    'build',
    'target',
    'out'
]);

// Чёрный список файлов по ОТНОСИТЕЛЬНОМУ пути (от корня)
const BLACKLIST_FILES = new Set([
    'node-server/package.json',
    'node-server/package-lock.json'
]);

// Расширения, которые считаем "кодом" для src.
// Для src применяем этот фильтр, для остальных папок — берём все файлы.
const SRC_INCLUDE_EXT = new Set([
    '.java', '.fxml', '.css', '.json', '.xml', '.properties',
    '.md', '.txt', '.html', '.js'
]);

// =======================================================

const fs   = require('fs');
const path = require('path');

const ROOT = process.cwd();

function readUtf8Safe(absPath) {
    try {
        return fs.readFileSync(absPath, 'utf8').replace(/\r\n/g, '\n');
    } catch {
        return null;
    }
}

function pushChunk(chunks, rel, content) {
    chunks.push(`// FILE: ${rel}\n${content}\n`);
}

// gen "YYYY-MM-DD HH-mm-ss"
function timestamp() {
    const d   = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
        + `${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}`;
}

// Решаем включать файл или нет с точки зрения типа контента
function shouldIncludeFileByType(rel, rootDirName) {
    const ext = path.extname(rel).toLowerCase();
    if (rootDirName === 'src') {
        // Для src — только "кодовые" расширения
        return SRC_INCLUDE_EXT.has(ext);
    }
    // Для остальных белых директорий берём все файлы
    return true;
}

// Рекурсивный обход белой директории с учётом чёрных списков
function walkDir(rootAbs, rootRel, rootDirName, collectedRels) {
    let entries;
    try {
        entries = fs.readdirSync(rootAbs, { withFileTypes: true });
    } catch {
        return;
    }

    for (const e of entries) {
        const full = path.join(rootAbs, e.name);

        if (e.isDirectory()) {
            if (BLACKLIST_DIR_NAMES.has(e.name)) {
                continue;
            }
            walkDir(full, rootRel, rootDirName, collectedRels);
        } else if (e.isFile()) {
            let rel = path.relative(ROOT, full).replace(/\\/g, '/');

            if (BLACKLIST_FILES.has(rel)) {
                continue;
            }
            if (!shouldIncludeFileByType(rel, rootDirName)) {
                continue;
            }

            collectedRels.push(rel);
        }
    }
}

// Добавляем файлы из белого списка директорий
function addWhitelistDirs(chunks) {
    const allRels = [];
    const perRootCounts = {};

    for (const dirRel of WHITELIST_DIRS) {
        const rootAbs = path.resolve(ROOT, dirRel);
        const rootDirName = dirRel.split(/[\\/]/)[0];

        if (!fs.existsSync(rootAbs)) {
            console.warn(`[WARN] Папка ${dirRel} не найдена, пропускаю`);
            continue;
        }

        const rels = [];
        walkDir(rootAbs, dirRel, rootDirName, rels);

        rels.sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));

        for (const rel of rels) {
            const abs      = path.resolve(ROOT, rel);
            const content  = readUtf8Safe(abs);
            if (content == null) continue;
            pushChunk(chunks, rel, content);
            allRels.push(rel);
        }

        perRootCounts[dirRel] = rels.length;
    }

    return { allRels, perRootCounts };
}

// Добавляем файлы из белого списка одиночных файлов
function addWhitelistFiles(chunks) {
    const rels = [];
    for (const relRaw of WHITELIST_FILES) {
        const rel = relRaw.replace(/\\/g, '/');
        const abs = path.resolve(ROOT, rel);
        if (!fs.existsSync(abs)) {
            console.warn(`[WARN] Файл ${rel} из WHITELIST_FILES не найден, пропускаю`);
            continue;
        }
        if (BLACKLIST_FILES.has(rel)) {
            console.warn(`[WARN] Файл ${rel} находится в BLACKLIST_FILES, пропускаю`);
            continue;
        }
        const content = readUtf8Safe(abs);
        if (content == null) continue;
        pushChunk(chunks, rel, content);
        rels.push(rel);
    }
    return rels;
}

function makeHeaderBlock(allFiles) {
    const lines = [];
    lines.push('// ===================== BUNDLE FILE LIST =====================');
    lines.push(`// Всего файлов: ${allFiles.length}`);
    lines.push(`// Сгенерировано: ${timestamp()}`);
    lines.push('// -------------------------------------------------------------');
    allFiles.forEach((rel, i) => {
        lines.push(`// ${String(i + 1).padStart(3, ' ')}. ${rel}`);
    });
    lines.push('// =================== END OF FILE LIST =======================\n');
    return lines.join('\n') + '\n';
}

function main() {
    const codeChunks = [];

    // 1) Одиночные файлы (pom.xml и т.п.)
    const fileRels = addWhitelistFiles(codeChunks);

    // 2) Белые директории (src, config, node-server, ...)
    const { allRels: dirRels, perRootCounts } = addWhitelistDirs(codeChunks);

    // Общий список файлов для заголовка
    const allFiles = [...fileRels, ...dirRels];

    const outName  = `code-bundle ${timestamp()}.txt`;
    const OUT_FILE = path.resolve(ROOT, 'build', outName);
    fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });

    const header = makeHeaderBlock(allFiles);
    fs.writeFileSync(OUT_FILE, header + codeChunks.join('\n'), 'utf8');

    // Красивый лог
    const parts = [];
    for (const dirRel of WHITELIST_DIRS) {
        const cnt = perRootCounts[dirRel] || 0;
        parts.push(`${cnt} из ${dirRel}`);
    }
    parts.push(`${fileRels.length} одиночных файлов`);

    console.log(`OK: собрано ${parts.join(' + ')} -> ${OUT_FILE}`);
}

main();
