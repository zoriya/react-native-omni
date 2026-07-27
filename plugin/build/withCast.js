"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.withCast = void 0;
const config_plugins_1 = require("@expo/config-plugins");
const OPTIONS_PROVIDER_NAME = "com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME";
const OPTIONS_PROVIDER_VALUE = "dev.zoriya.omni.OmniCastOptionsProvider";
function patchManifestForCast(androidManifest) {
    var _a;
    const mainApplication = config_plugins_1.AndroidConfig.Manifest.getMainApplication(androidManifest);
    if (!mainApplication) {
        console.warn("AndroidManifest.xml is missing a <application> element - skipping Omni cast config.");
        return androidManifest;
    }
    const metaData = (_a = mainApplication["meta-data"]) !== null && _a !== void 0 ? _a : [];
    const existing = metaData.find((item) => item.$["android:name"] === OPTIONS_PROVIDER_NAME);
    if (existing) {
        existing.$["android:value"] = OPTIONS_PROVIDER_VALUE;
    }
    else {
        metaData.push({
            $: {
                "android:name": OPTIONS_PROVIDER_NAME,
                "android:value": OPTIONS_PROVIDER_VALUE,
            },
        });
    }
    mainApplication["meta-data"] = metaData;
    return androidManifest;
}
const withCast = (config) => {
    return (0, config_plugins_1.withAndroidManifest)(config, (manifestConfig) => {
        manifestConfig.modResults = patchManifestForCast(manifestConfig.modResults);
        return manifestConfig;
    });
};
exports.withCast = withCast;
