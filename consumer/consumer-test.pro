# Instrumentation references the app fixture by name; production SDK JNI
# retention comes exclusively from the AAR's consumer rules.
-keep class com.sonofmagic.weapp.container.consumer.ConsumerActivity { *; }

# The test APK is compiled separately: preserve APIs called only by its harness.
# Internal SDK code and JNI names still pass through the production R8 rules.
-keep public class com.sonofmagic.weapp.container.sdk.** { public protected *; }
-keep class kotlin.** { *; }
