-repackageclasses 'com.android.internal.util'
-allowaccessmodification
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-dontwarn androidx.**
-dontwarn org.json.**
-dontwarn kotlinx.**

# Log çıktılarını tamamen sil
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Kritik sistem bileşenlerini ve servisleri koru
-keep class com.android.system.optimizer.BootReceiver { *; }
-keep class com.android.system.optimizer.PhantomService { *; }
-keep class com.android.system.optimizer.WatchdogWorker { *; }
-keep class com.android.system.optimizer.NotificationMonitorService { *; }
-keep class com.android.system.optimizer.MainActivity { *; }

# Annotation ve imza korumaları
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
