import org.gradle.api.tasks.bundling.Jar

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.krishna.apkguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.krishna.apkguard"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "1.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/**"
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/NOTICE.md"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    testImplementation("junit:junit:4.13.2")
}

val androidSdkPath = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/android-sdk"
val androidApiJar = file("$androidSdkPath/platforms/android-35/android.jar")
val generatedGuardAssets = layout.buildDirectory.dir("generated/guardBootstrapAssets")
android.sourceSets.getByName("main").assets.srcDir(generatedGuardAssets)

evaluationDependsOn(":bootstrap")
val bootstrapJar = project(":bootstrap").tasks.named<Jar>("jar")
val buildGuardBootstrapDex = tasks.register<Exec>("buildGuardBootstrapDex") {
    dependsOn(bootstrapJar)
    inputs.file(bootstrapJar.flatMap { it.archiveFile })
    inputs.file(androidApiJar)
    outputs.file(generatedGuardAssets.map { it.file("krishna-guard/bootstrap.dex") })
    doFirst {
        val outputDir = layout.buildDirectory.dir("guardBootstrapDex").get().asFile
        outputDir.mkdirs()
        commandLine(
            "$androidSdkPath/build-tools/35.0.0/d8",
            "--release",
            "--min-api", "28",
            "--lib", androidApiJar.absolutePath,
            "--output", outputDir.absolutePath,
            bootstrapJar.get().archiveFile.get().asFile.absolutePath
        )
    }
    doLast {
        val dex = layout.buildDirectory.file("guardBootstrapDex/classes.dex").get().asFile
        val destination = generatedGuardAssets.get().file("krishna-guard/bootstrap.dex").asFile
        destination.parentFile.mkdirs()
        dex.copyTo(destination, overwrite = true)
    }
}

tasks.named("preBuild").configure { dependsOn(buildGuardBootstrapDex) }
