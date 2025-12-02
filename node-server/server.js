// FILE: node-server/server.js
const path = require("path");
const { Engine } = require("./engine");

(async () => {
    const ROOT_DIR    = __dirname;
    const SETTING_DIR = path.join(ROOT_DIR, "setting");
    const WS_CONFIG   = path.join(ROOT_DIR, "setting", "setting.json");// сценарии/heartbeat здесь

    const engine = new Engine({
        rootDir: ROOT_DIR,
        settingDir: SETTING_DIR,
        wsConfigFile: WS_CONFIG,
        portOverride: null // можно задать порт руками при необходимости
    });

    await engine.start();
})();
