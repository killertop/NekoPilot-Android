# Android instrumentation APKs apply the target QA mapping and resolve shared Kotlin/runtime
# dependencies from the minified target package. Keep these bridge classes only in QA, so a
# production release stays fully shrinkable while its R8-equivalent regression variant can boot.
-keep,allowoptimization class kotlin.** {
    *;
}

-keep,allowoptimization class kotlinx.coroutines.** {
    *;
}

-keep,allowoptimization class androidx.tracing.** {
    *;
}

# The Room migration-test library executes against the target process and uses Room's public ABI
# beyond a fixed method list (for example MigrationContainer). Keep this test bridge intact in
# QA; production Release remains fully shrinkable.
-keep,allowoptimization class androidx.room.** {
    *;
}

# Room's migration-test helper also invokes SQLite and Arch Core types across the target/test
# boundary. Treat those public AndroidX APIs as the same QA-only bridge.
-keep,allowoptimization class androidx.sqlite.** {
    *;
}

-keep,allowoptimization class androidx.arch.core.** {
    *;
}

# The following APIs are called directly by instrumentation tests after AGP applies the target
# mapping. They are not necessarily reached by the production graph, but must remain callable
# in the QA target process for the full optimized-device suite.

-keepclassmembers,allowoptimization class androidx.fragment.app.FragmentManager {
    public boolean executePendingTransactions();
    public java.util.List getFragments();
}

-keepclassmembers,allowoptimization class androidx.appcompat.app.ActionBar {
    public java.lang.CharSequence getTitle();
}

-keep,allowoptimization class moe.matsuri.nb4a.ProtocolsKt {
    *;
}

# Room's migration-test helper calls these Gson APIs from the unbundled test APK.
-keep,allowoptimization class com.google.gson.GsonBuilder {
    public <init>();
    public com.google.gson.GsonBuilder setPrettyPrinting();
    public com.google.gson.GsonBuilder disableHtmlEscaping();
    public com.google.gson.GsonBuilder registerTypeAdapterFactory(com.google.gson.TypeAdapterFactory);
    public com.google.gson.Gson create();
}

-keep,allowoptimization class com.google.gson.Gson {
    public java.lang.Object fromJson(java.io.Reader, java.lang.Class);
    public com.google.gson.TypeAdapter getAdapter(java.lang.Class);
    public com.google.gson.TypeAdapter getDelegateAdapter(com.google.gson.TypeAdapterFactory, com.google.gson.reflect.TypeToken);
}

-keep,allowoptimization class com.google.gson.reflect.TypeToken {
    public java.lang.Class getRawType();
    public static com.google.gson.reflect.TypeToken get(java.lang.Class);
}

-keep,allowoptimization class com.google.gson.TypeAdapter {
    public java.lang.Object read(com.google.gson.stream.JsonReader);
    public java.lang.Object fromJsonTree(com.google.gson.JsonElement);
}

-keep,allowoptimization class com.google.gson.JsonElement {
    public com.google.gson.JsonObject getAsJsonObject();
}

-keep,allowoptimization class com.google.gson.JsonObject {
    public boolean has(java.lang.String);
}

-keepclassmembers,allowoptimization class androidx.core.view.ViewCompat {
    public static void requestApplyInsets(android.view.View);
    public static androidx.core.view.WindowInsetsCompat getRootWindowInsets(android.view.View);
}

-keepclassmembers,allowoptimization class androidx.core.view.WindowInsetsCompat {
    public androidx.core.graphics.Insets getInsets(int);
}

-keep,allowoptimization class androidx.core.view.WindowInsetsCompat$Type {
    *;
}

-keepclassmembers,allowoptimization class androidx.core.graphics.Insets {
    public final int top;
    public final int bottom;
}

-keepclassmembers,allowoptimization class com.google.android.material.snackbar.BaseTransientBottomBar {
    public void dismiss();
    public android.view.View getAnchorView();
}
