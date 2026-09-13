# Step 6 release hardening. Protocol/archive libraries provide consumer rules; retain only
# metadata and security-provider constructors that are discovered by name.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class org.bouncycastle.jcajce.provider.** { public <init>(...); }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { public <init>(...); }

# MBassador's optional expression-language filter is not used by the app, and Android does
# not ship the desktop javax.el API.
-dontwarn javax.el.**

# SMBJ's optional JAAS/Kerberos authenticator is not selected by the password-based SMB
# connection path. Android does not provide the desktop JGSS API.
-dontwarn org.ietf.jgss.**
