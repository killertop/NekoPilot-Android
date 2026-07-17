-repackageclasses ''
-allowaccessmodification

-keep class io.nekohasekai.sagernet.** { *;}
-keep class moe.matsuri.nb4a.** { *;}

# Gson's TypeToken reads its anonymous subclass generic signature at runtime.
# Keep the attribute and subclasses intact in R8-minified QA/release builds.
-keepattributes Signature
-keep,allowobfuscation,allowoptimization class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.reflect.TypeToken

-dontobfuscate
-keepattributes SourceFile

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.PropertyVetoException
