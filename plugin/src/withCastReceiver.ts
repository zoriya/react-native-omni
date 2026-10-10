import {
	AndroidConfig,
	type ConfigPlugin,
	withAndroidManifest,
	withGradleProperties,
	withMainActivity,
} from "@expo/config-plugins";
import {
	addImports,
	appendContentsInsideDeclarationBlock,
} from "@expo/config-plugins/build/android/codeMod";

const CAST_TV_ACTIONS = [
	"com.google.android.gms.cast.tv.action.LAUNCH",
	"com.google.android.gms.cast.tv.action.LOAD",
];
const NEW_INTENT_MARKER = "OmniCastReceiver.onNewIntent(";
const GRADLE_PROPERTY = "Omni_castReceiver";

function patchManifestForCastReceiver(
	androidManifest: AndroidConfig.Manifest.AndroidManifest,
): AndroidConfig.Manifest.AndroidManifest {
	// cast connect launches & loads media in the main activity (android tv only)
	const mainActivity =
		AndroidConfig.Manifest.getMainActivityOrThrow(androidManifest);
	const filters = mainActivity["intent-filter"] ?? [];
	for (const action of CAST_TV_ACTIONS) {
		if (
			filters.some((filter) =>
				filter.action?.some((a) => a.$["android:name"] === action),
			)
		)
			continue;
		filters.push({
			action: [{ $: { "android:name": action } }],
			category: [{ $: { "android:name": "android.intent.category.DEFAULT" } }],
		});
	}
	mainActivity["intent-filter"] = filters;

	return androidManifest;
}

function patchMainActivityForCastReceiver(
	mainActivity: string,
	language: "java" | "kt",
): string {
	if (mainActivity.includes(NEW_INTENT_MARKER)) return mainActivity;
	if (/\bonNewIntent\s*\(/.test(mainActivity)) {
		console.warn(
			"MainActivity already overrides onNewIntent - add `OmniCastReceiver.onNewIntent(intent)` to it to receive casts.",
		);
		return mainActivity;
	}

	const isJava = language === "java";
	const output = addImports(
		mainActivity,
		["android.content.Intent", "dev.zoriya.omni.OmniCastReceiver"],
		isJava,
	);
	const block = isJava
		? [
				"\n  @Override",
				"  public void onNewIntent(Intent intent) {",
				"    super.onNewIntent(intent);",
				"    OmniCastReceiver.onNewIntent(intent);",
				"  }\n",
			]
		: [
				"\n  override fun onNewIntent(intent: Intent) {",
				"    super.onNewIntent(intent)",
				"    OmniCastReceiver.onNewIntent(intent)",
				"  }\n",
			];
	return appendContentsInsideDeclarationBlock(
		output,
		"class MainActivity",
		block.join("\n"),
	);
}

export const withCastReceiver: ConfigPlugin = (config) => {
	const withMainActivityPatched = withMainActivity(config, (activityConfig) => {
		activityConfig.modResults.contents = patchMainActivityForCastReceiver(
			activityConfig.modResults.contents,
			activityConfig.modResults.language,
		);
		return activityConfig;
	});

	const withManifestPatched = withAndroidManifest(
		withMainActivityPatched,
		(manifestConfig) => {
			manifestConfig.modResults = patchManifestForCastReceiver(
				manifestConfig.modResults,
			);
			return manifestConfig;
		},
	);

	// builds omni with cast connect & its dependencies (see android/build.gradle)
	return withGradleProperties(withManifestPatched, (propertiesConfig) => {
		const properties = propertiesConfig.modResults.filter(
			(item) => !(item.type === "property" && item.key === GRADLE_PROPERTY),
		);
		properties.push({ type: "property", key: GRADLE_PROPERTY, value: "true" });
		propertiesConfig.modResults = properties;
		return propertiesConfig;
	});
};
