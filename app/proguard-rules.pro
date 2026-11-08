-keep class cc.tomko.outify.core.SessionCallback { *; }
-keepclassmembers class * implements cc.tomko.outify.core.SessionCallback { *; }
-keep class cc.tomko.outify.core.spirc.SpircInitializationCallback { *; }
-keep class cc.tomko.outify.core.spirc.SpircBufferCallback { *; }
-keep class cc.tomko.outify.core.spirc.SpircDeviceCallback { *; }

-keep class cc.tomko.outify.playback.callbacks.PlayerEventCallback { *; }
-keepclassmembers class * implements cc.tomko.outify.playback.callbacks.PlayerEventCallback { *; }
-keep class cc.tomko.outify.playback.AudioEngine { *; }

# These names/signatures are resolved by JNI, including callback implementations.
-keep class cc.tomko.outify.core.jam.JamNative { *; }
-keep interface cc.tomko.outify.core.jam.JamCallback { *; }
-keep interface cc.tomko.outify.core.jam.JamRequestCallback { *; }
-keepclassmembers class * implements cc.tomko.outify.core.jam.JamCallback { *; }
-keepclassmembers class * implements cc.tomko.outify.core.jam.JamRequestCallback { *; }
