import { AndroidConfig, withAndroidManifest, withMainActivity, type ConfigPlugin } from '@expo/config-plugins';
import { addImports, appendContentsInsideDeclarationBlock } from '@expo/config-plugins/build/android/codeMod';

const MODE_MARKER = 'OmniView.onActivityPipModeChanged(';
const UI_STATE_MARKER = 'OmniView.onActivityPipTransitionToPip(';

function patchMainActivityForPip(mainActivity: string, language: 'java' | 'kt'): string {
  const isJava = language === 'java';

  if (mainActivity.includes(MODE_MARKER) && mainActivity.includes(UI_STATE_MARKER)) {
    return mainActivity;
  }

  const withRequiredImports = addImports(
    mainActivity,
    ['android.app.PictureInPictureUiState', 'android.os.Build', 'dev.zoriya.omni.OmniView'],
    isJava
  );

  let output = withRequiredImports;

  if (!output.includes(MODE_MARKER)) {
    const modeChangedBlock = isJava
      ? [
          '\n  @Override',
          '  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {',
          '    super.onPictureInPictureModeChanged(isInPictureInPictureMode);',
          '    OmniView.onActivityPipModeChanged(this, isInPictureInPictureMode);',
          '  }\n',
        ]
      : [
          '\n  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {',
          '    super.onPictureInPictureModeChanged(isInPictureInPictureMode)',
          '    OmniView.onActivityPipModeChanged(this, isInPictureInPictureMode)',
          '  }\n',
        ];

    output = appendContentsInsideDeclarationBlock(output, 'class MainActivity', modeChangedBlock.join('\n'));
  }

  if (!output.includes(UI_STATE_MARKER)) {
    const uiStateChangedBlock = isJava
      ? [
          '\n  @Override',
          '  public void onPictureInPictureUiStateChanged(PictureInPictureUiState pipState) {',
          '    super.onPictureInPictureUiStateChanged(pipState);',
          '    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && pipState.isTransitioningToPip()) {',
          '      OmniView.onActivityPipTransitionToPip(this);',
          '    }',
          '  }\n',
        ]
      : [
          '\n  override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {',
          '    super.onPictureInPictureUiStateChanged(pipState)',
          '    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && pipState.isTransitioningToPip) {',
          '      OmniView.onActivityPipTransitionToPip(this)',
          '    }',
          '  }\n',
        ];

    output = appendContentsInsideDeclarationBlock(output, 'class MainActivity', uiStateChangedBlock.join('\n'));
  }

  return output;
}

function ensureUsesFeature(
  androidManifest: AndroidConfig.Manifest.AndroidManifest,
  featureName: string,
  required: 'true' | 'false'
): void {
  const usesFeature = androidManifest.manifest['uses-feature'] ?? [];
  const existing = usesFeature.find((feature) => feature.$['android:name'] === featureName);

  if (existing) {
    existing.$['android:required'] = required;
  } else {
    usesFeature.push({
      $: {
        'android:name': featureName,
        'android:required': required,
      },
    });
  }

  androidManifest.manifest['uses-feature'] = usesFeature;
}

function ensureConfigChanges(mainActivity: AndroidConfig.Manifest.ManifestActivity): void {
  const requiredChanges = ['screenSize', 'smallestScreenSize', 'screenLayout', 'orientation'];
  const existing = mainActivity.$['android:configChanges'] ?? '';
  const existingSet = new Set(
    existing
      .split('|')
      .map((item) => item.trim())
      .filter(Boolean)
  );

  requiredChanges.forEach((item) => existingSet.add(item));
  mainActivity.$['android:configChanges'] = Array.from(existingSet).join('|');
}

function patchManifestForPip(
  androidManifest: AndroidConfig.Manifest.AndroidManifest
): AndroidConfig.Manifest.AndroidManifest {
  ensureUsesFeature(androidManifest, 'android.software.picture_in_picture', 'false');

  const mainActivity = AndroidConfig.Manifest.getMainActivityOrThrow(androidManifest);
  mainActivity.$['android:supportsPictureInPicture'] = 'true';
  mainActivity.$['android:resizeableActivity'] = 'true';
  ensureConfigChanges(mainActivity);

  return androidManifest;
}

export const withPip: ConfigPlugin = (config) => {
  const withMainActivityPatched = withMainActivity(config, (activityConfig) => {
    activityConfig.modResults.contents = patchMainActivityForPip(
      activityConfig.modResults.contents,
      activityConfig.modResults.language
    );
    return activityConfig;
  });

  return withAndroidManifest(withMainActivityPatched, (manifestConfig) => {
    manifestConfig.modResults = patchManifestForPip(manifestConfig.modResults);
    return manifestConfig;
  });
};
