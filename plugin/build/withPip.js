"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.withPip = void 0;
const config_plugins_1 = require("@expo/config-plugins");
const codeMod_1 = require("@expo/config-plugins/build/android/codeMod");
const MODE_MARKER = "OmniView.onActivityPipModeChanged(";
const UI_STATE_MARKER = "OmniView.onActivityPipTransitionToPip(";
function patchMainActivityForPip(mainActivity, language) {
    const isJava = language === "java";
    if (mainActivity.includes(MODE_MARKER) &&
        mainActivity.includes(UI_STATE_MARKER)) {
        return mainActivity;
    }
    const withRequiredImports = (0, codeMod_1.addImports)(mainActivity, [
        "android.app.PictureInPictureUiState",
        "android.os.Build",
        "dev.zoriya.omni.OmniView",
    ], isJava);
    let output = withRequiredImports;
    if (!output.includes(MODE_MARKER)) {
        const modeChangedBlock = isJava
            ? [
                "\n  @Override",
                "  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {",
                "    super.onPictureInPictureModeChanged(isInPictureInPictureMode);",
                "    OmniView.onActivityPipModeChanged(this, isInPictureInPictureMode);",
                "  }\n",
            ]
            : [
                "\n  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {",
                "    super.onPictureInPictureModeChanged(isInPictureInPictureMode)",
                "    OmniView.onActivityPipModeChanged(this, isInPictureInPictureMode)",
                "  }\n",
            ];
        output = (0, codeMod_1.appendContentsInsideDeclarationBlock)(output, "class MainActivity", modeChangedBlock.join("\n"));
    }
    if (!output.includes(UI_STATE_MARKER)) {
        const uiStateChangedBlock = isJava
            ? [
                "\n  @Override",
                "  public void onPictureInPictureUiStateChanged(PictureInPictureUiState pipState) {",
                "    super.onPictureInPictureUiStateChanged(pipState);",
                "    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && pipState.isTransitioningToPip()) {",
                "      OmniView.onActivityPipTransitionToPip(this);",
                "    }",
                "  }\n",
            ]
            : [
                "\n  override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {",
                "    super.onPictureInPictureUiStateChanged(pipState)",
                "    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && pipState.isTransitioningToPip) {",
                "      OmniView.onActivityPipTransitionToPip(this)",
                "    }",
                "  }\n",
            ];
        output = (0, codeMod_1.appendContentsInsideDeclarationBlock)(output, "class MainActivity", uiStateChangedBlock.join("\n"));
    }
    return output;
}
function ensureUsesFeature(androidManifest, featureName, required) {
    var _a;
    const usesFeature = (_a = androidManifest.manifest["uses-feature"]) !== null && _a !== void 0 ? _a : [];
    const existing = usesFeature.find((feature) => feature.$["android:name"] === featureName);
    if (existing) {
        existing.$["android:required"] = required;
    }
    else {
        usesFeature.push({
            $: {
                "android:name": featureName,
                "android:required": required,
            },
        });
    }
    androidManifest.manifest["uses-feature"] = usesFeature;
}
function ensureConfigChanges(mainActivity) {
    var _a;
    const requiredChanges = [
        "screenSize",
        "smallestScreenSize",
        "screenLayout",
        "orientation",
    ];
    const existing = (_a = mainActivity.$["android:configChanges"]) !== null && _a !== void 0 ? _a : "";
    const existingSet = new Set(existing
        .split("|")
        .map((item) => item.trim())
        .filter(Boolean));
    requiredChanges.forEach((item) => existingSet.add(item));
    mainActivity.$["android:configChanges"] = Array.from(existingSet).join("|");
}
function patchManifestForPip(androidManifest) {
    ensureUsesFeature(androidManifest, "android.software.picture_in_picture", "false");
    const mainActivity = config_plugins_1.AndroidConfig.Manifest.getMainActivityOrThrow(androidManifest);
    mainActivity.$["android:supportsPictureInPicture"] = "true";
    mainActivity.$["android:resizeableActivity"] = "true";
    ensureConfigChanges(mainActivity);
    return androidManifest;
}
const withPip = (config) => {
    const withMainActivityPatched = (0, config_plugins_1.withMainActivity)(config, (activityConfig) => {
        activityConfig.modResults.contents = patchMainActivityForPip(activityConfig.modResults.contents, activityConfig.modResults.language);
        return activityConfig;
    });
    return (0, config_plugins_1.withAndroidManifest)(withMainActivityPatched, (manifestConfig) => {
        manifestConfig.modResults = patchManifestForPip(manifestConfig.modResults);
        return manifestConfig;
    });
};
exports.withPip = withPip;
