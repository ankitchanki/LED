# Services, receivers, activities and XML-inflated views are kept via the manifest/aapt rules.
# Shizuku.newProcess is called via reflection (Privileged.kt).
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
