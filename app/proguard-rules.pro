# release 코드 축소(R8) 규칙

# Pleos SDK: 내비·차량·음성 서비스와 AIDL·직렬화(이름 기준)로 주고받는다.
# NaviHelper·TextToSpeech 는 자체 규칙이 없어, 이름이 바뀌지 않도록 SDK 전체를 그대로 둔다.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# Parcelable·AIDL (서비스 바인딩)
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * extends android.os.IInterface { *; }

# kotlinx.serialization (Pleos navi-data 의 @Serializable)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** serializer(...);
}

# AAOS Car API (useLibrary("android.car")): 실행 때 차량 시스템이 제공한다
-dontwarn android.car.**
