# 本项目未开启代码混淆（isMinifyEnabled = false）。
# 如果以后开启混淆，ML Kit 需要保留其内部反射用到的类：
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
