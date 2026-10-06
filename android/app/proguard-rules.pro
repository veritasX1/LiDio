# Release builds (R8): what is reached by reflection or from native code keeps its name and shape.
# LiDio's own native parts (FFmpeg tags, Milkdrop): JNI finds classes and methods by name.
-keepclasseswithmembers class io.github.veritasx1.lidio.** { native <methods>; }
-keep class io.github.veritasx1.lidio.Ffmpeg* { *; }
-keep class io.github.veritasx1.lidio.Milk* { *; }
