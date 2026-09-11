plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // KSP 替代 kapt 处理 Room 注解；版本号格式为 <Kotlin 版本>-<KSP 版本>，须与 Kotlin 1.9.22 匹配
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}