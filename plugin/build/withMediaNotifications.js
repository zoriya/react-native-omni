"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.withMediaNotifications = void 0;
const config_plugins_1 = require("@expo/config-plugins");
function ensureUsesPermission(androidManifest, permission) {
    var _a;
    const usesPermissions = (_a = androidManifest.manifest["uses-permission"]) !== null && _a !== void 0 ? _a : [];
    const alreadyDefined = usesPermissions.some((item) => item.$["android:name"] === permission);
    if (!alreadyDefined) {
        usesPermissions.push({
            $: {
                "android:name": permission,
            },
        });
    }
    androidManifest.manifest["uses-permission"] = usesPermissions;
}
function patchManifestForMediaNotifications(androidManifest) {
    var _a;
    const mainApplication = config_plugins_1.AndroidConfig.Manifest.getMainApplication(androidManifest);
    if (!mainApplication) {
        console.warn('AndroidManifest.xml is missing a <application android:name=".MainApplication" /> element - skipping Omni media service config.');
        return androidManifest;
    }
    const services = (_a = mainApplication.service) !== null && _a !== void 0 ? _a : [];
    const serviceName = "dev.zoriya.omni.OmniPlayerService";
    const hasService = services.some((service) => service.$["android:name"] === serviceName);
    if (!hasService) {
        services.push({
            $: {
                "android:name": serviceName,
                "android:enabled": "true",
                "android:exported": "true",
                "android:foregroundServiceType": "mediaPlayback",
            },
            "intent-filter": [
                {
                    action: [
                        {
                            $: {
                                "android:name": "androidx.media3.session.MediaSessionService",
                            },
                        },
                    ],
                },
            ],
        });
    }
    mainApplication.service = services;
    ensureUsesPermission(androidManifest, "android.permission.FOREGROUND_SERVICE");
    ensureUsesPermission(androidManifest, "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK");
    return androidManifest;
}
const withMediaNotifications = (config) => {
    return (0, config_plugins_1.withAndroidManifest)(config, (manifestConfig) => {
        manifestConfig.modResults = patchManifestForMediaNotifications(manifestConfig.modResults);
        return manifestConfig;
    });
};
exports.withMediaNotifications = withMediaNotifications;
