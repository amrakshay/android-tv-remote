# protobuf-javalite reflects over generated message classes.
-keep class com.google.protobuf.** { *; }
-keep class remote.** { *; }
-keep class com.google.polo.** { *; }

# BouncyCastle ships providers for algorithms we do not use; let R8 drop them
# but keep what the JCE looks up by name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
