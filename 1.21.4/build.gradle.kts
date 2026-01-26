import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    java
    kotlin("jvm") version "2.2.20"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

dependencies {
    implementation(project(":core"))
    implementation(kotlin("stdlib", "2.2.20"))
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
}

val javaTargetVersion = 21

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaTargetVersion))
}

kotlin {
    jvmToolchain(javaTargetVersion)
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        jvmTarget.set(JvmTarget.JVM_21)
    }
}