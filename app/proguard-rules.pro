# Keep Xposed module entry point and all hook classes
-keep class io.github.yingjfd.magicorgin.hook.** { *; }
-keep class io.github.yingjfd.magicorgin.pods.** { *; }
-keep class io.github.yingjfd.magicorgin.config.** { *; }

# Keep launcher icon resources (referenced only from AndroidManifest, not from Kotlin)
-keep class **.R$* { public static <fields>; }
-keepclassmembers class **.R$mipmap { *; }
