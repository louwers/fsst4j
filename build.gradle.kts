import com.vanniktech.maven.publish.SonatypeHost

plugins {
    java
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish") version "0.28.0"
}

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

// Platform detection
val os = System.getProperty("os.name").lowercase()
val isWindows = os.contains("win")
val isMac = os.contains("mac")
val isLinux = os.contains("linux")

val libraryExtension = when {
    isWindows -> "dll"
    isMac -> "dylib"
    isLinux -> "so"
    else -> "so"
}
val libraryName = "libfsst.$libraryExtension"

// FSST build directory
val fsstBuildDir = file("fsst/build")
// On Windows with Visual Studio generators, the library is in Release/ subdirectory and uses .lib extension
val fsstStaticLib = if (isWindows) {
    fsstBuildDir.resolve("Release").resolve("fsst.lib")
} else {
    fsstBuildDir.resolve("libfsst.a")
}
val fsstSharedLib = fsstBuildDir.resolve(libraryName)

// Task to configure CMake
tasks.register<Exec>("configureFsst") {
    group = "build"
    description = "Configure FSST build with CMake"
    
    val fsstSourceDir = file("fsst")
    
    workingDir = fsstBuildDir
    
    // Force Release build for all platforms when publishing
    // Add -fPIC for Linux to enable creating shared libraries from static library
    val cmakeArgs = mutableListOf("..")
    cmakeArgs.add("-DCMAKE_BUILD_TYPE=Release")
    if (isLinux) {
        cmakeArgs.add("-DCMAKE_CXX_FLAGS=-fPIC")
    }
    
    commandLine("cmake", *cmakeArgs.toTypedArray())
    
    doFirst {
        fsstBuildDir.mkdirs()
    }
    
    outputs.dir(fsstBuildDir)
    inputs.dir(fsstSourceDir)
}

// Task to build FSST static library
tasks.register<Exec>("buildFsstStatic") {
    group = "build"
    description = "Build FSST static library"
    
    dependsOn("configureFsst")
    
    workingDir = fsstBuildDir
    // Force Release build configuration
    val buildArgs = if (isWindows) {
        listOf("--build", ".", "--config", "Release", "--target", "fsst")
    } else {
        listOf("--build", ".", "--target", "fsst")
    }
    commandLine("cmake", *buildArgs.toTypedArray())
    
    outputs.file(fsstStaticLib)
    inputs.files(
        file("fsst/libfsst.cpp"),
        file("fsst/fsst_avx512.cpp"),
        file("fsst/fsst_avx512_unroll1.inc"),
        file("fsst/fsst_avx512_unroll2.inc"),
        file("fsst/fsst_avx512_unroll3.inc"),
        file("fsst/fsst_avx512_unroll4.inc"),
        file("fsst/CMakeLists.txt")
    )
}

// Task to create shared library from static library
tasks.register<Exec>("buildFsstShared") {
    group = "build"
    description = "Create FSST shared library from static library"
    
    dependsOn("buildFsstStatic")
    
    workingDir = fsstBuildDir
    
    val cxx = if (isWindows) "g++" else "c++"
    val linkerFlags = when {
        isWindows -> listOf("-shared", "-o", fsstSharedLib.absolutePath, fsstStaticLib.absolutePath, "-std=c++17")
        isMac -> listOf("-shared", "-o", fsstSharedLib.absolutePath, "-Wl,-all_load", fsstStaticLib.absolutePath, "-std=c++17")
        isLinux -> listOf("-shared", "-o", fsstSharedLib.absolutePath, "-Wl,--whole-archive", fsstStaticLib.absolutePath, "-Wl,--no-whole-archive", "-std=c++17")
        else -> listOf("-shared", "-fPIC", "-o", fsstSharedLib.absolutePath, "-Wl,--whole-archive", fsstStaticLib.absolutePath, "-Wl,--no-whole-archive", "-std=c++17")
    }
    
    commandLine(cxx, *linkerFlags.toTypedArray())
    
    outputs.file(fsstSharedLib)
    inputs.file(fsstStaticLib)
}

// Task to copy native library to build/lib
tasks.register("copyNativeLibrary") {
    group = "build"
    description = "Copy FSST shared library to build/lib"
    
    dependsOn("buildFsstShared")
    
    doLast {
        val libDir = file("build/lib")
        libDir.mkdirs()
        
        if (fsstSharedLib.exists()) {
            copy {
                from(fsstSharedLib)
                into(libDir)
            }
            println("Copied native library to ${libDir.absolutePath}")
        } else {
            throw RuntimeException("Native library not found at ${fsstSharedLib.absolutePath}")
        }
    }
    
    outputs.dir(file("build/lib"))
    inputs.file(fsstSharedLib)
}

// Make build depend on native library
tasks.named("build") {
    dependsOn("copyNativeLibrary")
}

tasks.test {
    useJUnitPlatform()
    // Add library path for native library
    val libPath = file("build/lib").absolutePath
    systemProperty("java.library.path", libPath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Ensure library exists before tests
    doFirst {
        val libFile = file("build/lib/$libraryName")
        if (!libFile.exists()) {
            throw RuntimeException("Native library not found at ${libFile.absolutePath}. Please build it first.")
        }
        println("Library path: $libPath")
        println("Library exists: ${libFile.exists()}")
    }
    dependsOn("copyNativeLibrary")
}

tasks.compileJava {
    options.release.set(22)
}

tasks.compileTestJava {
    options.release.set(22)
}

// Task to embed native library in JAR resources
tasks.register<Copy>("embedNativeLibrary") {
    group = "build"
    description = "Copy native library to resources for JAR embedding"
    
    dependsOn("copyNativeLibrary")
    
    from("build/lib") {
        include(libraryName)
        into("META-INF/native")
        // Rename to include platform info
        rename(libraryName, "libfsst-${detectPlatform()}.$libraryExtension")
    }
    
    into("${layout.buildDirectory.get()}/resources/main")
    
    // Don't interfere with other tasks
    outputs.upToDateWhen { false }
}

// Fix task dependencies
tasks.named("javadoc") {
    mustRunAfter("embedNativeLibrary")
}

tasks.named("compileTestJava") {
    mustRunAfter("embedNativeLibrary")
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

// Include native library in JAR
tasks.named<Jar>("jar") {
    dependsOn("embedNativeLibrary")
    mustRunAfter("embedNativeLibrary")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("${layout.buildDirectory.get()}/resources/main/META-INF/native") {
        into("META-INF/native")
    }
}

// Maven publishing configuration
// Note: The vanniktech plugin automatically handles javadoc and sources jars
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    
    coordinates("nl.bartlouwers", "fsst4j", "0.0.2")
    
    pom {
        name = "FSST4J"
        description = "Java library wrapping FSST (Fast Static Symbol Table) compression using Foreign Function & Memory API"
        inceptionYear = "2024"
        url = "https://github.com/bartlouwers/fsst4j"
        
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
                distribution = "https://opensource.org/licenses/MIT"
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

// Add platform-specific JARs if provided (for multi-platform publishing)
// This needs to be done after the mavenPublishing block creates the publication
afterEvaluate {
    // Fix task dependency for maven-publish plugin
    tasks.findByName("generateMetadataFileForMavenPublication")?.let { metadataTask ->
        tasks.findByName("plainJavadocJar")?.let { javadocJarTask ->
            metadataTask.mustRunAfter(javadocJarTask)
        }
    }
    
    val platformJarsDir = project.findProperty("platformJarsDir") as String?
    if (platformJarsDir != null) {
        val platformDir = file(platformJarsDir)
        if (platformDir.exists()) {
            val publication = publishing.publications.findByName("maven") as? org.gradle.api.publish.maven.MavenPublication
            if (publication != null) {
                platformDir.listFiles()?.forEach { jarFile ->
                    if (jarFile.name.endsWith(".jar")) {
                        // Extract platform from filename
                        // Format options:
                        // - fsst4j-0.0.1-linux-x86_64.jar (with version)
                        // - fsst4j-linux-x86_64.jar (without version)
                        val name = jarFile.nameWithoutExtension
                        // Try pattern with version first: artifactId-version-platform
                        var pattern = Regex("^fsst4j-[0-9.]+-(.+)$")
                        var match = pattern.find(name)
                        // If no match, try without version: artifactId-platform
                        if (match == null) {
                            pattern = Regex("^fsst4j-(.+)$")
                            match = pattern.find(name)
                        }
                        if (match != null) {
                            val platform = match.groupValues[1]
                            println("Adding platform artifact: $platform from ${jarFile.name}")
                            
                            // Add as Maven artifact with classifier
                            publication.artifact(jarFile) {
                                classifier = platform
                            }
                        } else {
                            println("Warning: Could not extract platform from ${jarFile.name}")
                        }
                    }
                }
            }
        }
    }
}

// Configure signing with in-memory GPG keys
signing {
    val signingInMemoryKey = project.findProperty("signingInMemoryKey") as String?
    val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword") as String?
    
    if (signingInMemoryKey != null && signingInMemoryKeyPassword != null) {
        useInMemoryPgpKeys(signingInMemoryKey, signingInMemoryKeyPassword)
    }
}


