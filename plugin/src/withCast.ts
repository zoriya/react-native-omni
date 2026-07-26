import {
	AndroidConfig,
	type ConfigPlugin,
	withAndroidManifest,
} from "@expo/config-plugins";

const OPTIONS_PROVIDER_NAME =
	"com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME";
const OPTIONS_PROVIDER_VALUE = "dev.zoriya.omni.OmniCastOptionsProvider";

function patchManifestForCast(
	androidManifest: AndroidConfig.Manifest.AndroidManifest,
): AndroidConfig.Manifest.AndroidManifest {
	const mainApplication =
		AndroidConfig.Manifest.getMainApplication(androidManifest);
	if (!mainApplication) {
		console.warn(
			"AndroidManifest.xml is missing a <application> element - skipping Omni cast config.",
		);
		return androidManifest;
	}

	const metaData = mainApplication["meta-data"] ?? [];
	const existing = metaData.find(
		(item) => item.$["android:name"] === OPTIONS_PROVIDER_NAME,
	);
	if (existing) {
		existing.$["android:value"] = OPTIONS_PROVIDER_VALUE;
	} else {
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

export const withCast: ConfigPlugin = (config) => {
	return withAndroidManifest(config, (manifestConfig) => {
		manifestConfig.modResults = patchManifestForCast(manifestConfig.modResults);
		return manifestConfig;
	});
};
