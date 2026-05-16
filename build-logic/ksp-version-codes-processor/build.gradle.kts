import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(gradleApi())

    implementation("com.google.devtools.ksp:symbol-processing-api:${System.getProperty("com.google.devtools.ksp")}")
    implementation(libs.kotlin.csv.jvm)
}

System.getProperty("gradle.java.version.select").let { selectJdk ->
    java {
        sourceCompatibility = JavaVersion.toVersion("17")
        targetCompatibility = JavaVersion.toVersion("17")
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(selectJdk))
        }
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("17"))
        }
        jvmToolchain(selectJdk.toInt())
    }
}
