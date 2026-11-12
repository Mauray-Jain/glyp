# Nothing's Glyph SDK talks to the system Glyph service over a binder interface that is matched by
# name, and is used through its public classes; keep both packages whole.
-keep class com.nothing.ketchum.** { *; }
-keep class com.nothing.thirdparty.** { *; }
