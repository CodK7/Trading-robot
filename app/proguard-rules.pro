# Keep line numbers for actionable crash reports while allowing R8 to obfuscate classes.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Accessibility metadata is referenced by the Android framework.
-keep class com.aistudio.autoflow.bxyp.service.LotAutomationService { <init>(); }
