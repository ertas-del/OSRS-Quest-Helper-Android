# Release builds are shrunk and obfuscated by R8, which makes a repackaged copy much harder to
# read or change. The app uses no reflection, so very little needs keeping.

# Enum names are stored in settings (for example "mode_SPELLS"), so keep them as written.
-keep enum com.questoverlay.** { *; }

# Readable line numbers in crash reports; build/outputs/mapping/release/mapping.txt
# (kept with each build's artifacts) turns obfuscated names back into real ones.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
