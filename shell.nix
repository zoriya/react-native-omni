{pkgs ? import <nixpkgs> {}}: let
  buildToolsVersion = "35.0.0";
  android = pkgs.androidenv.composeAndroidPackages {
    buildToolsVersions = [buildToolsVersion "36.0.0"];
    platformVersions = ["35" "36"];
    cmakeVersions = ["latest" "3.22.1"];
    includeNDK = true;
    ndkVersions = ["latest" "27.1.12297006"];
    includeEmulator = true;
    includeSources = true;
    includeSystemImages = true;
    useGoogleAPIs = true;
    useGoogleTVAddOns = true;
    includeExtras = [
      "extras;google;gcm"
    ];
  };
  jdk = pkgs.jdk17_headless;
in
  pkgs.mkShell rec {
    packages = with pkgs; [
      bun
      nodejs
      biome
      jdk
      ((android-studio.override {forceWayland = true;}).withSdk android.androidsdk)
    ];

    shellHook = ''
      echo 'sdk.dir=${ANDROID_SDK_ROOT}' > ${toString ./.}/example/android/local.properties
      mkdir -p ${toString ./.}/example/android/.gradle
      echo 'java.home=${JAVA_HOME}' > ${toString ./.}/example/android/.gradle/config.properties
      if [ -f ${toString ./.}/example/android/.idea/gradle.xml ]; then
        sed -i 's|name="gradleJvm" value="[^"]*"|name="gradleJvm" value="#GRADLE_LOCAL_JAVA_HOME"|' ${toString ./.}/example/android/.idea/gradle.xml
      fi
    '';

    ANDROID_HOME = "${android.androidsdk}/libexec/android-sdk";
    ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";
    ANDROID_NDK_ROOT = "${ANDROID_SDK_ROOT}/ndk-bundle";
    GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${ANDROID_SDK_ROOT}/build-tools/${buildToolsVersion}/aapt2";
    JAVA_HOME = jdk.home;
  }
