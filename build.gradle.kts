import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
    `maven-publish`
}

// Get version from Environment Variable (GitHub Actions) or fallback to VERSION file
val githubTag = System.getenv("GITHUB_REF_NAME")
val releaseVersion = if (githubTag != null && githubTag.startsWith("v")) {
    githubTag.removePrefix("v")
} else {
    file("VERSION").readText().trim() 
}

qupathExtension {
    name = "qupath-extension-jinput"
    group = "io.github.qupath"
    version = releaseVersion
    description = "QuPath extension to support the spacemouse (and possibly other 3-D input devices)."
    automaticModule = "io.github.qupath.extension.jinput"
}

// 1. Setup custom configurations for your clever native extraction
val jinputVersion = "2.0.9"
val jinputJar by configurations.creating
val jinputNativeJar by configurations.creating

dependencies {
    val jinputDependency = "net.java.jinput:jinput:$jinputVersion"
    val jinputNativeDependency = "$jinputDependency:natives-all"

    // shadowJar will automatically bundle these core classes
    implementation(jinputDependency)

    // Keep this ONLY in our custom configuration so shadowJar doesn't dump it to the root
    jinputNativeJar(jinputNativeDependency)

    // Standard QuPath dependencies provided by conventions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
}

tasks.processResources {
    from("$projectDir/LICENSE") {
        into("META-INF/licenses/")
    }
}

// 2. The Clever Extract Task in Kotlin DSL
val extractNativeLibs by tasks.registering(Copy::class) {
    val outputDirectory = layout.buildDirectory.dir("natives")
    
    // Map the jar files to zipTrees lazily
    from(jinputNativeJar.map { jarFile ->
        zipTree(jarFile)
    })
    
    include("*.dll", "*.jnilib", "*.so")
    into(outputDirectory)
    
    doLast {
        println("Destination directory: ${outputDirectory.get().asFile.absolutePath}")
    }
}

// 3. The Custom JAR task repackaging
tasks.shadowJar {
    archiveClassifier.set("")
    dependsOn(extractNativeLibs)

    manifest {
        attributes(
            "Implementation-Vendor" to project.group,
            "Implementation-Title" to project.name,
            "Implementation-Version" to archiveVersion.get(),
            "Built-By" to System.getProperty("user.name"),
            "Created-By" to "Gradle ${gradle.gradleVersion}",
            "Build-Timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(Date()),
            "Build-Jdk" to "${System.getProperty("java.version")} (${System.getProperty("java.vendor")} ${System.getProperty("java.vm.version")})",
            "Build-OS" to "${System.getProperty("os.name")} ${System.getProperty("os.arch")} ${System.getProperty("os.version")}",
            "Automatic-Module-Name" to "io.github.qupath.extension.jinput"
        )
    }

    // Explicitly inject our extracted natives into the "natives" folder inside the shadow JAR
    into("natives") {
        from(extractNativeLibs)
    }

    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

// 4. Javadoc config (Casting to StandardJavadocDocletOptions is required in Kotlin DSL)
val strictJavadoc = findProperty("strictJavadoc")?.toString()?.toBoolean() ?: false
if (!strictJavadoc) {
    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    destinationDir = file("${project.rootDir}/docs")
}

// 5. Publishing config in Kotlin DSL
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/zindy/qupath-extension-jinput")
            credentials {
                username = System.getenv("MAVEN_USERNAME") ?: ""
                password = System.getenv("JAVA_TOKEN") ?: ""
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.github.zindy.qupath.extension.jinput"
            from(components["java"])
        }
    }
}
