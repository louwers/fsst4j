package nl.bartlouwers.fsst;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Foreign Function & Memory API bindings for the fsst C library.
 */
class FsstFfm {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;
    
    static {
        // Load the native library
        String libraryName = System.mapLibraryName("fsst");
        Path libraryPath = findLibrary(libraryName);
        if (libraryPath != null) {
            System.load(libraryPath.toAbsolutePath().toString());
        } else {
            // Try to load from system library path
            try {
                System.loadLibrary("fsst");
            } catch (UnsatisfiedLinkError e) {
                throw new RuntimeException("Failed to load fsst library: " + e.getMessage(), e);
            }
        }
        LOOKUP = SymbolLookup.loaderLookup();
    }
    
    private static Path findLibrary(String libraryName) {
        // First, try to extract from JAR resources (for Maven distribution)
        Path extractedLib = extractLibraryFromJar(libraryName);
        if (extractedLib != null) {
            return extractedLib;
        }
        
        // Try to find library in common development locations
        String userDir = System.getProperty("user.dir");
        String[] searchPaths = {
            userDir + "/build/lib/" + libraryName,
            userDir + "/fsst/build/lib/" + libraryName,
            "build/lib/" + libraryName,
            "fsst/build/lib/" + libraryName,
            "../fsst/build/lib/" + libraryName
        };
        
        for (String path : searchPaths) {
            Path libPath = Paths.get(path);
            if (java.nio.file.Files.exists(libPath)) {
                return libPath;
            }
        }
        return null;
    }
    
    /**
     * Extract native library from JAR resources.
     * This allows the library to be distributed as a single JAR file.
     */
    private static Path extractLibraryFromJar(String libraryName) {
        try {
            // Detect platform
            String os = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();
            
            String osName = os.contains("win") ? "windows" : 
                           os.contains("mac") ? "macos" : 
                           os.contains("linux") ? "linux" : "unknown";
            
            String archName = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" :
                             (arch.contains("x86_64") || arch.contains("amd64")) ? "x86_64" :
                             arch.contains("x86") ? "x86" : "unknown";
            
            String platformLibName = "libfsst-" + osName + "-" + archName + "." + 
                libraryName.substring(libraryName.lastIndexOf('.') + 1);
            
            // Try to find the library in JAR resources
            String resourcePath = "/META-INF/native/" + platformLibName;
            java.io.InputStream inputStream = FsstFfm.class.getResourceAsStream(resourcePath);
            
            if (inputStream == null) {
                // Try alternative naming
                resourcePath = "/META-INF/native/" + libraryName;
                inputStream = FsstFfm.class.getResourceAsStream(resourcePath);
            }
            
            if (inputStream != null) {
                // Extract to temporary file
                Path tempDir = java.nio.file.Files.createTempDirectory("fsst-native-");
                tempDir.toFile().deleteOnExit();
                
                Path tempLib = tempDir.resolve(libraryName);
                java.nio.file.Files.copy(inputStream, tempLib, 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                inputStream.close();
                
                // Make executable (Unix-like systems)
                if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                    tempLib.toFile().setExecutable(true);
                }
                
                return tempLib;
            }
        } catch (Exception e) {
            // Silently fail - will try other locations
        }
        return null;
    }
    
    // Memory layouts
    private static final AddressLayout POINTER = ValueLayout.ADDRESS;
    
    // fsst_decoder_t structure layout
    private static final GroupLayout FSST_DECODER_T = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("version"),        // unsigned long long
        ValueLayout.JAVA_BYTE.withName("zeroTerminated"), // unsigned char
        MemoryLayout.sequenceLayout(255, ValueLayout.JAVA_BYTE).withName("len"), // unsigned char len[255]
        MemoryLayout.sequenceLayout(255, ValueLayout.JAVA_LONG).withName("symbol") // unsigned long long symbol[255]
    );
    
    // VarHandles for accessing decoder structure
    private static final VarHandle DECODER_VERSION = FSST_DECODER_T.varHandle(
        MemoryLayout.PathElement.groupElement("version"));
    private static final VarHandle DECODER_ZERO_TERMINATED = FSST_DECODER_T.varHandle(
        MemoryLayout.PathElement.groupElement("zeroTerminated"));
    private static final VarHandle DECODER_LEN = MemoryLayout.sequenceLayout(255, ValueLayout.JAVA_BYTE).varHandle(
        MemoryLayout.PathElement.sequenceElement());
    private static final VarHandle DECODER_SYMBOL = MemoryLayout.sequenceLayout(255, ValueLayout.JAVA_LONG).varHandle(
        MemoryLayout.PathElement.sequenceElement());
    
    // Function handles
    private static final MethodHandle FSST_CREATE;
    private static final MethodHandle FSST_COMPRESS;
    private static final MethodHandle FSST_DECODER;
    private static final MethodHandle FSST_EXPORT;
    private static final MethodHandle FSST_IMPORT;
    private static final MethodHandle FSST_DESTROY;
    
    static {
        try {
            FSST_CREATE = LINKER.downcallHandle(
                LOOKUP.find("fsst_create").orElseThrow(),
                FunctionDescriptor.of(POINTER,
                    ValueLayout.JAVA_LONG,     // size_t n
                    POINTER,                   // const size_t lenIn[]
                    POINTER,                   // const unsigned char *strIn[]
                    ValueLayout.JAVA_INT)      // int zeroTerminated
            );
            
            FSST_COMPRESS = LINKER.downcallHandle(
                LOOKUP.find("fsst_compress").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    POINTER,                   // fsst_encoder_t *encoder
                    ValueLayout.JAVA_LONG,     // size_t nstrings
                    POINTER,                   // const size_t lenIn[]
                    POINTER,                   // const unsigned char *strIn[]
                    ValueLayout.JAVA_LONG,     // size_t outsize
                    POINTER,                   // unsigned char *output
                    POINTER,                   // size_t lenOut[]
                    POINTER)                   // unsigned char *strOut[]
            );
            
            // For struct returns, FFM automatically handles allocation
            // The function descriptor should match the C signature, FFM adds allocator automatically
            FSST_DECODER = LINKER.downcallHandle(
                LOOKUP.find("fsst_decoder").orElseThrow(),
                FunctionDescriptor.of(FSST_DECODER_T,
                    POINTER)                  // fsst_encoder_t *encoder
            );
            
            FSST_EXPORT = LINKER.downcallHandle(
                LOOKUP.find("fsst_export").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    POINTER,                   // fsst_encoder_t *encoder
                    POINTER)                   // unsigned char *buf
            );
            
            FSST_IMPORT = LINKER.downcallHandle(
                LOOKUP.find("fsst_import").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    POINTER,                   // fsst_decoder_t *decoder
                    POINTER)                   // unsigned char const *buf
            );
            
            FSST_DESTROY = LINKER.downcallHandle(
                LOOKUP.find("fsst_destroy").orElseThrow(),
                FunctionDescriptor.ofVoid(
                    POINTER)                   // fsst_encoder_t *encoder
            );
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize fsst function handles", e);
        }
    }
    
    /**
     * Create an encoder from input data.
     * @param data Input data to create encoder from
     * @param arena Arena for memory allocation
     * @return Memory address of the encoder
     */
    static MemorySegment createEncoder(byte[] data, Arena arena) {
        try {
            // Allocate memory for length array
            MemorySegment lenIn = arena.allocate(ValueLayout.JAVA_LONG);
            lenIn.set(ValueLayout.JAVA_LONG, 0, (long) data.length);
            
            // Allocate memory for input data
            MemorySegment inputData = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            
            // Allocate memory for string pointer array
            MemorySegment strIn = arena.allocate(POINTER);
            strIn.set(POINTER, 0, inputData);
            
            // Call fsst_create
            MemorySegment encoderPtr = (MemorySegment) FSST_CREATE.invoke(
                (long) 1,              // n = 1 string
                lenIn,                 // lenIn
                strIn,                 // strIn
                0                      // zeroTerminated = false
            );
            
            if (encoderPtr == null) {
                throw new RuntimeException("fsst_create returned null");
            }
            
            // Store encoder pointer (we'll allocate wrapper when needed)
            // Return a MemorySegment that wraps the encoder pointer
            MemorySegment encoder = arena.allocate(POINTER);
            encoder.set(POINTER, 0, encoderPtr);
            return encoder;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create fsst encoder", e);
        }
    }
    
    /**
     * Compress data using the encoder.
     * @param encoder Memory address of the encoder
     * @param data Input data to compress
     * @param arena Arena for memory allocation
     * @return Compressed data
     */
    static byte[] compress(MemorySegment encoder, byte[] data, Arena arena) {
        try {
            // Get encoder pointer
            MemorySegment encoderPtr = encoder.get(POINTER, 0);
            
            // Allocate memory for input length
            MemorySegment lenIn = arena.allocate(ValueLayout.JAVA_LONG, 1);
            lenIn.set(ValueLayout.JAVA_LONG, 0, (long) data.length);
            
            // Allocate memory for input data
            MemorySegment inputData = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            
            // Allocate memory for string pointer array
            MemorySegment strIn = arena.allocate(POINTER);
            strIn.set(POINTER, 0, inputData);
            
            // Estimate output size (conservative: 7 + 2*inputLength)
            long outputSize = 7 + 2L * data.length;
            MemorySegment output = arena.allocate((int) outputSize);
            
            // Allocate memory for output length
            MemorySegment lenOut = arena.allocate(ValueLayout.JAVA_LONG);
            
            // Allocate memory for output string pointer
            MemorySegment strOut = arena.allocate(POINTER);
            
            // Call fsst_compress
            long compressedCount = (long) FSST_COMPRESS.invoke(
                encoderPtr,
                (long) 1,              // nstrings = 1
                lenIn,                // lenIn
                strIn,                // strIn
                outputSize,           // outsize
                output,               // output
                lenOut,              // lenOut
                strOut               // strOut
            );
            
            if (compressedCount == 0) {
                throw new RuntimeException("fsst_compress failed or output buffer too small");
            }
            
            // Read compressed length
            long compressedLen = lenOut.get(ValueLayout.JAVA_LONG, 0);
            
            // Extract compressed data
            byte[] compressed = new byte[(int) compressedLen];
            MemorySegment.copy(output, ValueLayout.JAVA_BYTE, 0, compressed, 0, (int) compressedLen);
            
            return compressed;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to compress data", e);
        }
    }
    
    /**
     * Get decoder from encoder and extract symbol table.
     * @param encoder Memory address of the encoder
     * @param arena Arena for memory allocation
     * @return Symbol table data (symbols, lengths)
     */
    static SymbolTableData getDecoder(MemorySegment encoder, Arena arena) {
        try {
            // Get encoder pointer
            MemorySegment encoderPtr = encoder.get(POINTER, 0);
            
            // Call fsst_decoder (returns struct by value)
            // FFM automatically adds SegmentAllocator as first parameter for struct returns
            MemorySegment decoder = (MemorySegment) FSST_DECODER.invoke(arena, encoderPtr);
            
            // Extract symbol lengths
            // len array starts after version (8 bytes) + zeroTerminated (1 byte) = 9 bytes
            long lenOffset = 9;
            MemorySegment lenSegment = decoder.asSlice(lenOffset, 255);
            
            int[] symbolLengths = new int[255];
            for (int i = 0; i < 255; i++) {
                symbolLengths[i] = Byte.toUnsignedInt(lenSegment.get(ValueLayout.JAVA_BYTE, i));
            }
            
            // Extract symbols
            // Calculate total symbol bytes
            int totalSymbolBytes = 0;
            for (int i = 0; i < 255; i++) {
                totalSymbolBytes += symbolLengths[i];
            }
            
            // Get offset to symbol array within decoder structure
            // symbol array starts after version (8) + zeroTerminated (1) + len[255] (255) = 264 bytes
            long symbolOffset = 264;
            MemorySegment symbolSegment = decoder.asSlice(symbolOffset);
            
            byte[] symbols = new byte[totalSymbolBytes];
            int offset = 0;
            for (int i = 0; i < 255; i++) {
                long symbolValue = symbolSegment.get(ValueLayout.JAVA_LONG, i * 8);
                int len = symbolLengths[i];
                // Copy bytes from little-endian long
                for (int j = 0; j < len; j++) {
                    symbols[offset + j] = (byte) ((symbolValue >> (j * 8)) & 0xFF);
                }
                offset += len;
            }
            
            return new SymbolTableData(symbols, symbolLengths);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get decoder", e);
        }
    }
    
    /**
     * Export symbol table to byte array.
     * @param encoder Memory address of the encoder
     * @param arena Arena for memory allocation
     * @return Exported symbol table bytes
     */
    static byte[] export(MemorySegment encoder, Arena arena) {
        try {
            // Get encoder pointer
            MemorySegment encoderPtr = encoder.get(POINTER, 0);
            
            // Allocate buffer for export (max size is FSST_MAXHEADER = 8+1+8+2048+1 = 2066)
            int maxHeaderSize = 2066;
            MemorySegment buf = arena.allocate(maxHeaderSize);
            
            // Call fsst_export
            int exportedSize = (int) FSST_EXPORT.invoke(encoderPtr, buf);
            
            if (exportedSize == 0) {
                throw new RuntimeException("fsst_export failed");
            }
            
            // Extract exported data
            byte[] exported = new byte[exportedSize];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, exported, 0, exportedSize);
            
            return exported;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to export symbol table", e);
        }
    }
    
    /**
     * Import symbol table from byte array.
     * @param data Exported symbol table bytes
     * @param arena Arena for memory allocation
     * @return Symbol table data (symbols, lengths)
     */
    static SymbolTableData import_(byte[] data, Arena arena) {
        try {
            // Allocate decoder structure
            MemorySegment decoder = arena.allocate(FSST_DECODER_T);
            
            // Allocate buffer and copy data
            MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            
            // Call fsst_import
            int importedSize = (int) FSST_IMPORT.invoke(decoder, buf);
            
            if (importedSize == 0) {
                throw new RuntimeException("fsst_import failed");
            }
            
            // Extract symbol lengths
            // len array starts after version (8 bytes) + zeroTerminated (1 byte) = 9 bytes
            long lenOffset = 9;
            MemorySegment lenSegment = decoder.asSlice(lenOffset, 255);
            
            int[] symbolLengths = new int[255];
            for (int i = 0; i < 255; i++) {
                symbolLengths[i] = Byte.toUnsignedInt(lenSegment.get(ValueLayout.JAVA_BYTE, i));
            }
            
            // Extract symbols
            // Calculate total symbol bytes
            int totalSymbolBytes = 0;
            for (int i = 0; i < 255; i++) {
                totalSymbolBytes += symbolLengths[i];
            }
            
            // Get offset to symbol array within decoder structure
            // symbol array starts after version (8) + zeroTerminated (1) + len[255] (255) = 264 bytes
            long symbolOffset = 264;
            MemorySegment symbolSegment = decoder.asSlice(symbolOffset);
            
            byte[] symbols = new byte[totalSymbolBytes];
            int offset = 0;
            for (int i = 0; i < 255; i++) {
                long symbolValue = symbolSegment.get(ValueLayout.JAVA_LONG, i * 8);
                int len = symbolLengths[i];
                // Copy bytes from little-endian long
                for (int j = 0; j < len; j++) {
                    symbols[offset + j] = (byte) ((symbolValue >> (j * 8)) & 0xFF);
                }
                offset += len;
            }
            
            return new SymbolTableData(symbols, symbolLengths);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to import symbol table", e);
        }
    }
    
    /**
     * Destroy encoder and free memory.
     * @param encoder Memory address of the encoder
     */
    static void destroy(MemorySegment encoder) {
        try {
            // Get encoder pointer
            MemorySegment encoderPtr = encoder.get(POINTER, 0);
            FSST_DESTROY.invoke(encoderPtr);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to destroy encoder", e);
        }
    }
    
    /**
     * Helper class to hold symbol table data.
     */
    static class SymbolTableData {
        final byte[] symbols;
        final int[] symbolLengths;
        
        SymbolTableData(byte[] symbols, int[] symbolLengths) {
            this.symbols = symbols;
            this.symbolLengths = symbolLengths;
        }
    }
}
