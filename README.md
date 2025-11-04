# FSST4J Library

A Java library that wraps the [FSST (Fast Static Symbol Table)](https://github.com/cwida/fsst) compression library using Java's Foreign Function & Memory (FFM) API.

FSST is a compression scheme focused on string/text data that allows random-access to compressed data. Unlike block-based compression, individual strings can be decompressed without touching surrounding data.

## Features

- **Native Performance**: Uses Java's Foreign Function & Memory API (Java 22+) for efficient native library integration
- **Fast Compression**: Leverages FSST's optimized C++ implementation with SIMD support
- **Random Access**: Compressed strings can be decompressed individually without decompressing entire blocks
- **Type Safety**: Java interface with proper error handling and memory management

## Requirements

- **Java 22 or later** (required for FFM API)
- **Gradle** (for building)
- **CMake** and a C++ compiler (for building the native fsst library)
  - On macOS: Xcode Command Line Tools
  - On Linux: `build-essential` or equivalent
  - On Windows: Visual Studio or MinGW

## Building

The Gradle build automatically handles building the native FSST library. Simply run:

```bash
gradle build
```

Or if you prefer to use the Gradle wrapper:

```bash
./gradlew build
```

This will:
1. Configure CMake for the FSST library
2. Build the FSST static library
3. Create a shared library from the static library (platform-specific)
4. Copy the shared library to `build/lib/`
5. Compile the Java code
6. Run tests

### Manual Build Steps (Optional)

If you need to build the native library separately, you can use these Gradle tasks:

```bash
# Configure CMake
gradle configureFsst

# Build static library
gradle buildFsstStatic

# Create shared library
gradle buildFsstShared

# Copy to build/lib
gradle copyNativeLibrary
```

The build system automatically detects your platform (macOS, Linux, Windows) and uses the appropriate commands and library extensions.

## Running Tests

### Run All Tests

```bash
gradle test
```

Or with the wrapper:

```bash
./gradlew test
```

### Run Unit Tests Only

```bash
gradle test --tests "nl.bartlouwers.fsst.FsstTest"
```

### Run Benchmark Test

The benchmark test compresses all files in the `fsst/paper/dbtext` corpus and reports compression statistics:

```bash
gradle test --tests "nl.bartlouwers.fsst.FsstBenchmarkTest"
```

This will output a compression report showing:
- Original and compressed sizes for each file
- Compression ratio and savings percentage
- Overall statistics across all files
- Symbol table overhead

### View Test Reports

After running tests, HTML reports are available at:

```
build/reports/tests/test/index.html
```

## Usage

### Basic Example

```java
import nl.bartlouwers.fsst.*;

// Create an FSST instance
Fsst fsst = new FsstImpl();

// Compress data
byte[] originalData = "Hello, World! This is a test string.".getBytes();
SymbolTable encoded = fsst.encode(originalData);

// Decompress data
byte[] decoded = fsst.decode(encoded);

// Verify round-trip
assert Arrays.equals(originalData, decoded);
```

### Working with SymbolTable

The `SymbolTable` record contains all information needed for decompression:

```java
SymbolTable encoded = fsst.encode(data);

// Access components
byte[] symbols = encoded.symbols();           // Symbol table bytes
int[] symbolLengths = encoded.symbolLengths(); // Length of each symbol
byte[] compressedData = encoded.compressedData(); // Compressed output
int decompressedLength = encoded.decompressedLength(); // Original size
```

### Decompression Methods

There are multiple ways to decompress:

```java
// Method 1: Using SymbolTable
byte[] decoded = fsst.decode(encoded);

// Method 2: Using explicit components with length
byte[] decoded = fsst.decode(
    encoded.symbols(),
    encoded.symbolLengths(),
    encoded.compressedData(),
    encoded.decompressedLength()
);

// Method 3: Deprecated method (without explicit length)
@Deprecated
byte[] decoded = fsst.decode(
    encoded.symbols(),
    encoded.symbolLengths(),
    encoded.compressedData()
);
```

## Project Structure

```
.
├── src/
│   ├── main/
│   │   └── java/
│   │       └── nl/
│   │           └── bartlouwers/
│   │               └── fsst/
│   │                   ├── Fsst.java              # Main interface
│   │                   ├── FsstImpl.java          # Implementation using FFM
│   │                   ├── FsstFfm.java           # FFM bindings for native library
│   │                   └── SymbolTable.java       # Result record
│   └── test/
│       └── java/
│           └── nl/
│               └── bartlouwers/
│                   └── fsst/
│                       ├── FsstTest.java          # Unit tests
│                       └── FsstBenchmarkTest.java # Benchmark tests
├── fsst/                                          # FSST submodule
├── build.gradle.kts                               # Gradle build configuration
└── settings.gradle.kts                            # Gradle settings
```

## API Reference

### `Fsst` Interface

- `SymbolTable encode(byte[] data)` - Compress input data and return a SymbolTable
- `byte[] decode(SymbolTable encoded)` - Decompress using a SymbolTable
- `byte[] decode(byte[] symbols, int[] symbolLengths, byte[] compressedData, int decompressedLength)` - Decompress using explicit components
- `byte[] decode(byte[] symbols, int[] symbolLengths, byte[] compressedData)` - Deprecated decompression method

### `SymbolTable` Record

- `byte[] symbols()` - Symbol table bytes
- `int[] symbolLengths()` - Length of each symbol
- `byte[] compressedData()` - Compressed output data
- `int decompressedLength()` - Original data length

## Troubleshooting

### Native Library Not Found

If you see an error about the native library not being found:

1. Run the full build: `gradle clean build` - this will automatically build the native library
2. Check that CMake is installed: `cmake --version`
3. Check that a C++ compiler is available: `c++ --version` (macOS) or `g++ --version` (Linux)
4. Verify the library was created: `ls -la build/lib/libfsst.*`
5. If issues persist, try building the native library manually:
   ```bash
   gradle configureFsst
   gradle buildFsstStatic
   gradle buildFsstShared
   gradle copyNativeLibrary
   ```

### Java Version Issues

This project requires Java 22 or later. Check your version:

```bash
java -version
```

You should see something like `openjdk version "22"` or higher.

### Platform-Specific Issues

- **macOS**: The library is named `libfsst.dylib`
- **Linux**: The library should be named `libfsst.so`
- **Windows**: The library should be named `fsst.dll`

The library loading code in `FsstFfm.java` will automatically detect the correct library name based on your platform.

## License

This project uses the FSST library, which is distributed under the MIT License. See the `fsst/LICENSE` file for details.

## CI/CD

The project includes GitHub Actions workflows for automated building and publishing:

- **`.github/workflows/build.yml`** - Builds and tests on macOS, Linux, and Windows on every push/PR
- **`.github/workflows/publish.yml`** - Publishes to Maven Central on release creation

## Publishing to Maven Central

For instructions on publishing this library to Maven Central, see [PUBLISHING.md](PUBLISHING.md).

The library is configured to:
- Embed native libraries in JAR resources
- Automatically extract and load native libraries at runtime
- Support multi-platform distribution (build on each platform)
- Automatically build and publish via GitHub Actions

## References

- [FSST GitHub Repository](https://github.com/cwida/fsst)
- [FSST Paper](https://github.com/cwida/fsst/raw/master/fsstcompression.pdf)
- [Java Foreign Function & Memory API](https://openjdk.org/jeps/454)

