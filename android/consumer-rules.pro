# libvlc's JNI_OnLoad looks up classes & members by name and doesn't ship keep rules.
-keep class org.videolan.libvlc.** { *; }
