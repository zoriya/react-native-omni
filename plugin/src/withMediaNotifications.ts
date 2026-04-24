import { AndroidConfig, withAndroidManifest, type ConfigPlugin } from '@expo/config-plugins';

function ensureUsesPermission(
  androidManifest: AndroidConfig.Manifest.AndroidManifest,
  permission: string
): void {
  const usesPermissions = androidManifest.manifest['uses-permission'] ?? [];
  const alreadyDefined = usesPermissions.some((item) => item.$['android:name'] === permission);

  if (!alreadyDefined) {
    usesPermissions.push({
      $: {
        'android:name': permission,
      },
    });
  }

  androidManifest.manifest['uses-permission'] = usesPermissions;
}

function patchManifestForMediaNotifications(
  androidManifest: AndroidConfig.Manifest.AndroidManifest
): AndroidConfig.Manifest.AndroidManifest {
  const mainApplication = AndroidConfig.Manifest.getMainApplication(androidManifest);
  if (!mainApplication) {
    console.warn(
      'AndroidManifest.xml is missing a <application android:name=".MainApplication" /> element - skipping Omni media service config.'
    );
    return androidManifest;
  }

  const services = mainApplication.service ?? [];
  const serviceName = 'dev.zoriya.omni.OmniPlayerService';
  const hasService = services.some((service) => service.$['android:name'] === serviceName);
  if (!hasService) {
    services.push({
      $: {
        'android:name': serviceName,
        'android:enabled': 'true',
        'android:exported': 'true',
        'android:foregroundServiceType': 'mediaPlayback',
      },
      'intent-filter': [
        {
          action: [
            {
              $: {
                'android:name': 'androidx.media3.session.MediaSessionService',
              },
            },
          ],
        },
      ],
    });
  }
  mainApplication.service = services;

  ensureUsesPermission(androidManifest, 'android.permission.FOREGROUND_SERVICE');
  ensureUsesPermission(androidManifest, 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK');
  return androidManifest;
}

export const withMediaNotifications: ConfigPlugin = (config) => {
  return withAndroidManifest(config, (manifestConfig) => {
    manifestConfig.modResults = patchManifestForMediaNotifications(manifestConfig.modResults);
    return manifestConfig;
  });
};
