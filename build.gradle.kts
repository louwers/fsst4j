import com.vanniktech.maven.publish.SonatypeHost

plugins {
    java
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish") version "0.28.0"
}

apply(from = "version.gradle.kts")

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Detect platform string for Maven classifier
fun detectPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    
    val osName = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        os.contains("linux") -> "linux"
        else -> "unknown"
    }
    
    val archName = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
        arch.contains("x86") -> "x86"
        else -> "unknown"
    }
    
    return "$osName-$archName"
}

// Platform detection
val os = System.getProperty("os.name").lowercase()
val isWindows = os.contains("win")
val isMac = os.contains("mac")
val isLinux = os.contains("linux")

val libraryExtension = when {
    isWindows -> "dll"
    isMac -> "dylib"
    else -> "so"
}
val libraryName = "libfsst.$libraryExtension"

val fsstBuildDir = file("fsst/build")
val fsstStaticLib = if (isWindows) {
    fsstBuildDir.resolve("Release/fsst.lib")
} else {
    fsstBuildDir.resolve("libfsst.a")
}
val fsstSharedLib = fsstBuildDir.resolve(libraryName)

tasks.register<Exec>("configureFsst") {
    group = "build"
    description = "Configure FSST build with CMake"
    workingDir = fsstBuildDir
    
    val cmakeArgs = mutableListOf("..", "-DCMAKE_BUILD_TYPE=Release")
    if (isLinux) {
        cmakeArgs.add("-DCMAKE_CXX_FLAGS=-fPIC")
    }
    commandLine("cmake", *cmakeArgs.toTypedArray())
    
    doFirst { fsstBuildDir.mkdirs() }
    outputs.dir(fsstBuildDir)
    inputs.dir(file("fsst"))
}

tasks.register<Exec>("buildFsstStatic") {
    group = "build"
    description = "Build FSST static library"
    dependsOn("configureFsst")
    workingDir = fsstBuildDir
    
    val buildArgs = if (isWindows) {
        listOf("--build", ".", "--config", "Release", "--target", "fsst")
    } else {
        listOf("--build", ".", "--target", "fsst")
    }
    commandLine("cmake", *buildArgs.toTypedArray())
    
    outputs.file(fsstStaticLib)
    inputs.files(
        "fsst/libfsst.cpp", "fsst/fsst_avx512.cpp",
        "fsst/fsst_avx512_unroll1.inc", "fsst/fsst_avx512_unroll2.inc",
        "fsst/fsst_avx512_unroll3.inc", "fsst/fsst_avx512_unroll4.inc",
        "fsst/CMakeLists.txt"
    )
}

tasks.register<Exec>("buildFsstShared") {
    group = "build"
    description = "Create FSST shared library from static library"
    dependsOn("buildFsstStatic")
    workingDir = fsstBuildDir
    
    val linkerFlags = when {
        isWindows -> listOf("-shared", "-o", fsstSharedLib.absolutePath, fsstStaticLib.absolutePath, "-std=c++17")
        isMac -> listOf("-shared", "-o", fsstSharedLib.absolutePath, "-Wl,-all_load", fsstStaticLib.absolutePath, "-std=c++17")
        else -> listOf("-shared", "-o", fsstSharedLib.absolutePath, "-Wl,--whole-archive", fsstStaticLib.absolutePath, "-Wl,--no-whole-archive", "-std=c++17")
    }
    commandLine(if (isWindows) "g++" else "c++", *linkerFlags.toTypedArray())
    
    outputs.file(fsstSharedLib)
    inputs.file(fsstStaticLib)
}

tasks.register<Copy>("copyNativeLibrary") {
    group = "build"
    description = "Copy FSST shared library to build/lib"
    dependsOn("buildFsstShared")
    
    from(fsstSharedLib)
    into("build/lib")
    
    doFirst {
        if (!fsstSharedLib.exists()) {
            throw RuntimeException("Native library not found at ${fsstSharedLib.absolutePath}")
        }
    }
}

tasks.named("build") {
    dependsOn("copyNativeLibrary")
}

tasks.test {
    useJUnitPlatform()
    dependsOn("copyNativeLibrary")
    systemProperty("java.library.path", file("build/lib").absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    
    doFirst {
        val libFile = file("build/lib/$libraryName")
        require(libFile.exists()) { "Native library not found: ${libFile.absolutePath}" }
    }
}

tasks.compileJava {
    options.release.set(22)
}

tasks.compileTestJava {
    options.release.set(22)
}

tasks.register<Copy>("embedNativeLibrary") {
    group = "build"
    description = "Copy native library to resources for JAR embedding"
    dependsOn("copyNativeLibrary")
    
    from("build/lib") {
        include(libraryName)
        into("META-INF/native")
        rename(libraryName, "libfsst-${detectPlatform()}.$libraryExtension")
    }
    into("${layout.buildDirectory.get()}/resources/main")
}

tasks.named("javadoc") {
    mustRunAfter("embedNativeLibrary")
}

tasks.named("compileTestJava") {
    mustRunAfter("embedNativeLibrary")
}

tasks.named<Jar>("jar") {
    dependsOn("embedNativeLibrary")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("${layout.buildDirectory.get()}/resources/main/META-INF/native") {
        into("META-INF/native")
    }
}

// Create a lazy version provider that only executes during publishing
val versionProvider = provider {
    if (gradle.startParameter.taskNames.any { it.contains("publish") }) {
        @Suppress("UNCHECKED_CAST")
        val getVersionFunc = extra["getVersionFromGitTag"] as () -> String
        getVersionFunc()
    } else {
        "0.0.2" // fallback for non-publishing tasks
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("nl.bartlouwers", "fsst4j", versionProvider.get())
    
    pom {
        name = "FSST4J"
        description = "Java library wrapping FSST (Fast Static Symbol Table) compression using Foreign Function & Memory API"
        inceptionYear = "2024"
        url = "https://github.com/bartlouwers/fsst4j"
        
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        
        developers {
            developer {
                id = "bartlouwers"
                name = "Bart Louwers"
                url = "https://github.com/bartlouwers/"
            }
        }
        
        scm {
            url = "https://github.com/bartlouwers/fsst4j"
            connection = "scm:git:git://github.com/bartlouwers/fsst4j.git"
            developerConnection = "scm:git:ssh://git@github.com/bartlouwers/fsst4j.git"
        }
    }
}

afterEvaluate {
    tasks.findByName("generateMetadataFileForMavenPublication")?.mustRunAfter("plainJavadocJar")
    
    val platformJarsDir = project.findProperty("platformJarsDir") as String?
    if (platformJarsDir != null) {
        val platformDir = file(platformJarsDir)
        if (platformDir.exists()) {
            val publication = publishing.publications.findByName("maven") as? org.gradle.api.publish.maven.MavenPublication
            publication?.let {
                platformDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jarFile ->
                    val name = jarFile.nameWithoutExtension
                    val patterns = listOf(
                        Regex("^fsst4j-[0-9.]+-(.+)$"),
                        Regex("^fsst4j-(.+)$")
                    )
                    val platform = patterns.firstNotNullOfOrNull { it.find(name)?.groupValues?.get(1) }
                    
                    if (platform != null) {
                        println("Adding platform artifact: $platform from ${jarFile.name}")
                        publication.artifact(jarFile) { classifier = platform }
                    } else {
                        println("Warning: Could not extract platform from ${jarFile.name}")
                    }
                }
            }
        }
    }
}

signing {
    val signingKey = project.findProperty("signingInMemoryKey") as String?
    val signingPassword = project.findProperty("signingInMemoryKeyPassword") as String?
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
}
