# Step 6 release hardening. Protocol/archive libraries provide consumer rules; retain only
# metadata and security-provider constructors that are discovered by name.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class org.bouncycastle.jcajce.provider.** { public <init>(...); }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { public <init>(...); }
