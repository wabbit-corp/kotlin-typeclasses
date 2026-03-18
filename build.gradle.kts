import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    google()
    mavenCentral()

    maven("https://jitpack.io")
}

group = "one.wabbit"
version = "0.0.1"

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")

    id("maven-publish")

    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    coordinates("one.wabbit", "kotlin-typeclasses-plugin", "0.0.1")
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("kotlin-typeclasses-plugin")
        description.set("K2 compiler plugin for one.wabbit typeclass inference and derivation.")
        url.set("https://github.com/wabbit-corp/kotlin-typeclasses-plugin")
        licenses {
            license {
                name.set("GNU Affero General Public License v3.0 or later")
                url.set("https://spdx.org/licenses/AGPL-3.0-or-later.html")
            }
        }
        developers {
            developer {
                id.set("wabbit-corp")
                name.set("Wabbit Consulting Corporation")
                email.set("wabbit@wabbit.one")
            }
        }
        scm {
            url.set("https://github.com/wabbit-corp/kotlin-typeclasses-plugin")
            connection.set("scm:git:git://github.com/wabbit-corp/kotlin-typeclasses-plugin.git")
            developerConnection.set("scm:git:ssh://git@github.com/wabbit-corp/kotlin-typeclasses-plugin.git")
        }
    }
}

val localPublishRequested =
    gradle.startParameter.taskNames.any { taskName -> "MavenLocal" in taskName }

if (localPublishRequested) {
    tasks.withType<org.gradle.plugins.signing.Sign>().configureEach {
        enabled = false
    }
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("one.wabbit:kotlin-typeclasses:0.0.1")

    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-parcelize-compiler:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-power-assert-compiler-plugin-embeddable:2.3.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0")
    testImplementation("androidx.compose.runtime:runtime:1.9.3")
    testImplementation("androidx.compose.runtime:runtime-annotation-jvm:1.9.3")
    testImplementation("androidx.collection:collection-jvm:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1")
}

java {
    targetCompatibility = JavaVersion.toVersion(21)
    sourceCompatibility = JavaVersion.toVersion(21)
}

val configuredVersionString = version.toString()

tasks.register("printVersion") {
    inputs.property("configuredVersion", configuredVersionString)
    doLast {
        println(inputs.properties["configuredVersion"])
    }
}

tasks.register("assertReleaseVersion") {
    inputs.property("configuredVersion", configuredVersionString)
    doLast {
        val versionString = inputs.properties["configuredVersion"].toString()
        require(!versionString.endsWith("+dev-SNAPSHOT")) {
            "Release publishing requires a non-snapshot version, got $versionString"
        }
        val refType = System.getenv("GITHUB_REF_TYPE") ?: ""
        val refName = System.getenv("GITHUB_REF_NAME") ?: ""
        if (refType == "tag" && refName.isNotBlank()) {
            val expectedTag = "v$versionString"
            require(refName == expectedTag) {
                "Git tag $refName does not match project version $versionString"
            }
        }
    }
}

tasks.register("assertSnapshotVersion") {
    inputs.property("configuredVersion", configuredVersionString)
    doLast {
        val versionString = inputs.properties["configuredVersion"].toString()
        require(versionString.endsWith("+dev-SNAPSHOT")) {
            "Snapshot publishing requires a +dev-SNAPSHOT version, got $versionString"
        }
        require((System.getenv("GITHUB_REF_TYPE") ?: "") != "tag") {
            "Snapshot publishing must not run from a tag ref"
        }
    }
}

tasks {
    withType<Test> {
        jvmArgs("-ea")
    }
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }
    withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xcontext-parameters")
        }
    }

    jar {
        setProperty("zip64", true)
    }
}

kover {
}
