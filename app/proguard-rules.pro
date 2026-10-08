# Ktor 使用协程与 AtomicFU，keep 关键类避免混淆出错
-keep class kotlinx.coroutines.** { *; }
-dontwarn org.slf4j.**
