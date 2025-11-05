# Publishing to Maven Central

This guide explains how to publish the FSST Java library to Maven Central (Sonatype OSSRH).

## Distribution Strategy

The library uses **embedded native libraries** in the JAR file. Each JAR contains the native library for the platform it was built on:

- Native library is embedded in `META-INF/native/` in the JAR
- Library loading code (`FsstFfm.java`) automatically extracts and loads the native library from JAR resources at runtime
- The library is extracted to a temporary directory and loaded dynamically
- This allows a single JAR per platform to work out-of-the-box

## Multi-Platform Distribution

For Maven Central, you need to build and publish separate JARs for each platform:

### Recommended Approach: Platform-Specific JARs

Build on each target platform and publish separately. The JAR built on each platform will contain that platform's native library:

**On macOS (aarch64):**
```bash
gradle clean build publish
# Creates: fsst4j-1.0.0.jar (contains macos-aarch64 native library)
```

**On Linux (x86_64) - via CI/CD:**
```bash
gradle clean build publish
# Creates: fsst4j-1.0.0.jar (contains linux-x86_64 native library)
```

Each JAR will have the same coordinates but contain different native libraries. Users on each platform will get the correct JAR automatically.

### Option 2: Universal JAR (All Platforms)

Include native libraries for all platforms in a single JAR. This requires building on multiple platforms and combining them. See the "Multi-Platform Build" section below.

## Setup for Maven Central

### 1. Sonatype Central Account Setup

1. Create an account at https://central.sonatype.com/
2. Create a new namespace for your groupId (`nl.bartlouwers`)
3. Verify ownership of the namespace (via domain, GitHub, etc.)
4. Wait for approval (usually same day)

### 2. GPG Key for Signing

1. Generate a GPG key if you don't have one:
   ```bash
   gpg --gen-key
   ```

2. Publish your public key:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <your-key-id>
   ```

3. Verify it's published:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --recv-keys <your-key-id>
   ```

### 3. Configure Gradle Properties

Create or edit `~/.gradle/gradle.properties`:

```properties
# Sonatype Central credentials (from https://central.sonatype.com/)
sonatypeUsername=your-sonatype-username
sonatypePassword=your-sonatype-password

# GPG signing
signing.keyId=your-gpg-key-id
signing.password=your-gpg-key-password
signing.secretKeyRingFile=/path/to/your/secring.gpg
```

For CI/CD (GitHub Actions), add these as secrets:
- `SONATYPE_USERNAME`
- `SONATYPE_PASSWORD`
- `GPG_PRIVATE_KEY`
- `GPG_PASSPHRASE`
- `GPG_FINGERPRINT`

**Security Note:** Never commit these credentials to version control!

### 4. Update Version in build.gradle.kts

Before publishing, update the version in `build.gradle.kts`:

```kotlin
version = "1.0.0"  // Remove -SNAPSHOT for release
```

## Publishing

### Local Testing

Test the publication locally first:

```bash
gradle clean build publishToMavenLocal
```

This will publish to `~/.m2/repository/nl/bartlouwers/fsst4j/`.

Then in another project, test with:

```xml
<dependency>
    <groupId>nl.bartlouwers</groupId>
    <artifactId>fsst4j</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Or with Gradle:

```kotlin
dependencies {
    implementation("nl.bartlouwers:fsst4j:1.0.0-SNAPSHOT")
}
```

The native library will be automatically extracted from the JAR at runtime.

### Publishing to Maven Central

1. **Build and publish:**
   ```bash
   gradle clean build publish
   ```

2. **Verify publication:**
   - Check https://central.sonatype.com/ for your published artifacts
   - Artifacts are automatically published (no manual staging required)
   - It may take a few minutes to appear on Maven Central

### Using GitHub Actions (Recommended)

The repository includes GitHub Actions workflows for automated publishing:

1. **Set up GitHub Secrets:**
   - `SONATYPE_USERNAME` - Your Sonatype Central username
   - `SONATYPE_PASSWORD` - Your Sonatype Central password
   - `GPG_PRIVATE_KEY` - Your GPG private key (export with `gpg --export-secret-keys --armor <key-id>`)
   - `GPG_PASSPHRASE` - Your GPG key passphrase
   - `GPG_FINGERPRINT` - Your GPG key fingerprint

2. **Trigger publication:**
   - Create a GitHub Release (automatically triggers `publish.yml`)
   - Or use "Run workflow" manually with a version number

The workflow will build on all platforms (macOS, Linux) and publish each platform-specific JAR.

### CI/CD Setup

For multi-platform builds, set up CI/CD pipelines:

#### GitHub Actions Example

```yaml
name: Publish to Maven Central

on:
  release:
    types: [created]

jobs:
  publish:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest]
        include:
          - os: ubuntu-latest
            platform: linux-x86_64
          - os: macos-latest
            platform: macos-aarch64
    
    runs-on: ${{ matrix.os }}
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 22
        uses: actions/setup-java@v3
        with:
          java-version: '22'
          distribution: 'temurin'
      
      - name: Setup CMake
        uses: jwlawson/actions-setup-cmake@v1
      
      - name: Build and Publish
        env:
          OSSRH_USERNAME: ${{ secrets.OSSRH_USERNAME }}
          OSSRH_PASSWORD: ${{ secrets.OSSRH_PASSWORD }}
          GPG_KEY_ID: ${{ secrets.GPG_KEY_ID }}
          GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
          GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
        run: |
          echo "$GPG_PRIVATE_KEY" | gpg --import
          gradle clean build publish -Pplatform=${{ matrix.platform }}
```

## Multi-Platform Build (Universal JAR)

To create a JAR with all platform libraries (optional advanced approach):

1. Build native libraries on each platform
2. Collect all platform libraries with platform-specific names:
   - `libfsst-macos-aarch64.dylib`
   - `libfsst-linux-x86_64.so`
3. Include them all in one JAR in `META-INF/native/`
4. The library loader (`FsstFfm.java`) already supports this - it detects the platform and loads the correct library

This provides a single JAR that works on all platforms, but increases JAR size. The current implementation already supports this approach if you include multiple platform libraries in the JAR.

## Alternative: Separate Native Artifacts

Instead of embedding, you could publish native libraries as separate Maven artifacts:

- `fsst4j-native-macos-aarch64:1.0.0`
- `fsst4j-native-linux-x86_64:1.0.0`
- etc.

Users would then depend on both:
```xml
<dependency>
    <groupId>nl.bartlouwers</groupId>
    <artifactId>fsst4j</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>nl.bartlouwers</groupId>
    <artifactId>fsst4j-native-${platform}</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Version Management

- Use `-SNAPSHOT` suffix for development versions
- Remove `-SNAPSHOT` for releases
- Follow [Semantic Versioning](https://semver.org/)

## Troubleshooting

### GPG Signing Issues

If signing fails:
```bash
# Verify GPG key is accessible
gpg --list-secret-keys

# Test signing manually
echo "test" | gpg --clearsign
```

### Staging Repository Issues

- Check that all required files are present (JAR, sources, javadoc, POM)
- Verify GPG signatures are valid
- Ensure POM metadata is correct

### Sync Delay

Maven Central sync can take 2-4 hours. Check status at:
- https://repo1.maven.org/maven2/nl/bartlouwers/fsst4j/

## References

- [Sonatype Central Publishing Guide](https://central.sonatype.com/publish/publish-guide/)
- [Gradle Publishing Plugin](https://docs.gradle.org/current/userguide/publishing_maven.html)
- [Maven Central Requirements](https://central.sonatype.com/publish/requirements/requirements/)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)

