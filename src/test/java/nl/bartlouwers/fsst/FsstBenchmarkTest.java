package nl.bartlouwers.fsst;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark test suite for FSST compression on the dbtext corpus.
 * Tests compression performance and verifies round-trip correctness.
 */
class FsstBenchmarkTest {
    
    private Fsst fsst;
    
    @BeforeEach
    void setUp() {
        fsst = new FsstImpl();
    }
    
    @Test
    void testDbtextCorpusCompression() throws Exception {
        Path dbtextDir = Paths.get("fsst/paper/dbtext");
        if (!Files.exists(dbtextDir)) {
            // Try alternative path
            dbtextDir = Paths.get("../fsst/paper/dbtext");
            if (!Files.exists(dbtextDir)) {
                System.out.println("Skipping dbtext corpus test: directory not found");
                return;
            }
        }
        
        System.out.println("\n=== FSST Compression Report for dbtext Corpus ===\n");
        System.out.printf("%-20s %12s %12s %10s %10s%n", 
            "File", "Original (B)", "Compressed (B)", "Ratio", "Savings");
        System.out.println("------------------------------------------------------------");
        
        AtomicLong totalOriginal = new AtomicLong(0);
        AtomicLong totalCompressed = new AtomicLong(0);
        AtomicLong totalSymbolTable = new AtomicLong(0);
        AtomicInteger fileCount = new AtomicInteger(0);
        AtomicInteger failedFiles = new AtomicInteger(0);
        
        Files.list(dbtextDir)
            .filter(Files::isRegularFile)
            .sorted()
            .forEach(file -> {
                try {
                    byte[] originalData = Files.readAllBytes(file);
                    SymbolTable encoded = fsst.encode(originalData);
                    
                    // Decompress and verify it matches the original
                    byte[] decoded = fsst.decode(encoded);
                    assertArrayEquals(originalData, decoded, 
                        "Round-trip decompression failed for: " + file.getFileName());
                    
                    // Also test the explicit length decode method
                    byte[] decodedExplicit = fsst.decode(
                        encoded.symbols(),
                        encoded.symbolLengths(),
                        encoded.compressedData(),
                        encoded.decompressedLength());
                    assertArrayEquals(originalData, decodedExplicit,
                        "Explicit length decode failed for: " + file.getFileName());
                    
                    // Calculate sizes
                    int originalSize = originalData.length;
                    int compressedSize = encoded.compressedData().length;
                    int symbolTableSize = encoded.symbols().length;
                    int totalSize = compressedSize + symbolTableSize;
                    
                    double ratio = (double) totalSize / originalSize;
                    double savings = (1.0 - ratio) * 100.0;
                    
                    System.out.printf("%-20s %,12d %,12d %9.2fx %9.1f%%%n",
                        file.getFileName(),
                        originalSize,
                        totalSize,
                        ratio,
                        savings);
                    
                    // Accumulate totals
                    totalOriginal.addAndGet(originalSize);
                    totalCompressed.addAndGet(totalSize);
                    totalSymbolTable.addAndGet(symbolTableSize);
                    fileCount.incrementAndGet();
                } catch (AssertionError e) {
                    failedFiles.incrementAndGet();
                    System.err.println("FAILED: " + file.getFileName() + " - " + e.getMessage());
                    e.printStackTrace();
                } catch (Exception e) {
                    failedFiles.incrementAndGet();
                    System.err.println("Error processing " + file.getFileName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
        
        System.out.println("------------------------------------------------------------");
        long totalOriginalLong = totalOriginal.get();
        long totalCompressedLong = totalCompressed.get();
        long totalSymbolTableLong = totalSymbolTable.get();
        double overallRatio = (double) totalCompressedLong / totalOriginalLong;
        double overallSavings = (1.0 - overallRatio) * 100.0;
        
        System.out.printf("%-20s %,12d %,12d %9.2fx %9.1f%%%n",
            "TOTAL",
            totalOriginalLong,
            totalCompressedLong,
            overallRatio,
            overallSavings);
        
        System.out.printf("%nProcessed %d files%n", fileCount.get());
        System.out.printf("Symbol table overhead: %,d bytes (%.2f%% of total)%n",
            totalSymbolTableLong,
            (double) totalSymbolTableLong / totalCompressedLong * 100.0);
        
        if (failedFiles.get() > 0) {
            System.err.printf("\nWARNING: %d files failed to compress/decompress correctly%n", failedFiles.get());
        }
        System.out.println();
        
        // Assert that compression is effective overall (compressed + symbol table < original)
        assertTrue(totalCompressedLong < totalOriginalLong, 
            "Overall compression should reduce size");
        
        // Assert that all files were processed successfully
        assertEquals(0, failedFiles.get(), 
            "All files should compress and decompress correctly");
    }
}

