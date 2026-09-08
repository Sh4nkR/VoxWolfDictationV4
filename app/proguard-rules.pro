# Keep the JNI class — R8 cannot see native references
-keep class com.voxwolf.dictation.asr.WhisperNative {
    native <methods>;
    *;
}

# Keep the accessibility service
-keep class com.voxwolf.dictation.service.VoxWolfAccessibilityService {
    *;
}

# Keep the onboarding activity
-keep class com.voxwolf.dictation.onboarding.OnboardingActivity {
    *;
}
