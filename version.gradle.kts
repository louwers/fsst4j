// Function to get version from Git tag - only called when publishing
fun getVersionFromGitTag(): String {
    val process = ProcessBuilder("git", "describe", "--tags", "--exact-match", "HEAD")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    
    if (exitCode == 0 && output.isNotEmpty()) {
        // Remove 'v' prefix if present
        val version = output.removePrefix("v")
        if (version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
            return version
        } else {
            throw GradleException("Git tag '$output' does not follow semantic versioning format (num.num.num)")
        }
    } else {
        throw GradleException("No valid git tag found at HEAD")
    }
}

// Export the function to the project
extra["getVersionFromGitTag"] = ::getVersionFromGitTag

