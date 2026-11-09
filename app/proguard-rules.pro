# The downloader library reads GitHub release JSON through Jackson when it
# updates yt-dlp; keep both intact rather than chase reflection breakage.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# commons-compress instantiates its zip extra-field classes by reflection; the
# library uses it to unpack Python and ffmpeg on first run.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.compress.**
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
