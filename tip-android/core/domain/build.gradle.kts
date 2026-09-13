plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        kotlin.srcDirs(
            "src/main/kotlin",
            "../model/src/main/kotlin",
            "../security/src/main/kotlin",
            "../platform/src/main/kotlin"
        )
    }
    test {
        kotlin.srcDirs(
            "src/test/kotlin"
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnit()
}
