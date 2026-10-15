# Keep Tor & HTTP Server classes
-keep class io.ktor.** { *; }
-keep class org.nanohttpd.** { *; }
-keep class com.onionhost.** { *; }

# Ktor's optional IntelliJ debugger detector references desktop-only JMX APIs;
# Android does not provide java.lang.management.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# SLF4J's Android NOP fallback is used when no logging binding is packaged.
-dontwarn org.slf4j.impl.StaticLoggerBinder
