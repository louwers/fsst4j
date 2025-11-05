plugins {
    java
    `maven-publish`
    signing
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

// Fix task dependency for javadoc
tasks.named("javadoc") {
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
java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            groupId = "nl.bartlouwers"
            artifactId = "fsst4j"
            version = "1.0.0-SNAPSHOT"
            
            // Add platform-specific JARs if provided (for multi-platform publishing)
            val platformJarsDir = project.findProperty("platformJarsDir") as String?
            if (platformJarsDir != null) {
                val platformDir = file(platformJarsDir)
                if (platformDir.exists()) {
                    platformDir.listFiles()?.forEach { jarFile ->
                        if (jarFile.name.endsWith(".jar")) {
                            // Extract platform from filename
                            // Format: fsst4j-1.0.0-linux-x86_64.jar
                            val name = jarFile.nameWithoutExtension
                            val parts = name.split("-")
                            if (parts.size >= 3) {
                                // Skip artifactId and version, rest is platform
                                val platform = parts.drop(2).joinToString("-")
                                println("Adding platform artifact: $platform from ${jarFile.name}")
                                
                                // Add as Maven artifact with classifier
                                artifact(jarFile) {
                                    classifier = platform
                                }
                            }
                        }
                    }
                }
            }
            
            pom {
                name.set("FSST4J")
                description.set("Java library wrapping FSST (Fast Static Symbol Table) compression using Foreign Function & Memory API")
                url.set("https://github.com/bartlouwers/fsst4j")
                
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("bartlouwers")
                        name.set("Bart Louwers")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/bartlouwers/fsst4j.git")
                    developerConnection.set("scm:git:ssh://github.com/bartlouwers/fsst4j.git")
                    url.set("https://github.com/bartlouwers/fsst4j")
                }
            }
        }
    }
    
    repositories {
        maven {
            name = "Central"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = project.findProperty("sonatypeUsername") as String? 
                    ?: project.findProperty("mavenCentralUsername") as String? ?: ""
                password = project.findProperty("sonatypePassword") as String?
                    ?: project.findProperty("mavenCentralPassword") as String? ?: ""
            }
        }
        
        // Also publish to local Maven for testing
        maven {
            name = "local"
            url = uri("${layout.buildDirectory.get()}/local-repo")
        }
    }
}

signing {
    val signingInMemoryKey = project.findProperty("signingInMemoryKey") as String?
    val signingInMemoryKeyPassword = project.findProperty("signingInMemoryKeyPassword") as String?
    
    if (signingInMemoryKey != null && signingInMemoryKeyPassword != null) {
        useInMemoryPgpKeys(signingInMemoryKey, signingInMemoryKeyPassword)
    }
    
    sign(publishing.publications["maven"])
}


