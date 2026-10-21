import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "com.clotube"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20241027-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.45.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("uk.co.caprica:vlcj:4.8.3")
}

compose.desktop {
    application {
        mainClass = "com.clotube.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Clotube"
            packageVersion = "1.0.0"
            description = "Clotube Desktop Streaming Client"
            copyright = "© 2026 Clotube. All rights reserved."
            vendor = "Clotube"

            windows {
                menuGroup = "Clotube"
                upgradeUuid = "68c2d159-86cf-44f2-9cb7-285223ab49e0"
                iconFile.set(project.file("src/main/resources/ic_clotube.ico"))
            }
        }
    }
}
