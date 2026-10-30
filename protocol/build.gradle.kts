plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain JVM module, not an Android one: everything the protocol
// needs (TLS, crypto, protobuf) is standard Java, so the whole of it is unit
// testable on the desktop with no emulator and no device.
kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        // protoc output, generated from spike/proto/atv.pb. See protocol/README.md.
        java.srcDir("src/main/java")
    }
}

dependencies {
    api(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Desktop harness for driving a real TV without a phone in the loop.
//   ./gradlew :protocol:tvcli --args="keys 192.168.0.236 creds VOLUME_DOWN"
tasks.register<JavaExec>("tvcli") {
    group = "verification"
    description = "Exercise the protocol against a real Android TV"
    mainClass.set("dev.amrakshay.atvremote.protocol.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
