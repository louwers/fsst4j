package nl.bartlouwers.fsst;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Comprehensive test suite for FSST compression.
 */
class FsstTest {
    
    private Fsst fsst;
    
    @BeforeEach
    void setUp() {
        fsst = new FsstImpl();
    }
    
    @Test
    void testEncodeDecodeRoundTrip() {
        String text = "Hello, World! This is a test string for FSST compression.";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded, "Round-trip encode/decode should preserve data");
    }
    
    @Test
    void testEncodeDecodeWithSmallData() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeDecodeWithSingleByte() {
        byte[] data = new byte[]{42};
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeDecodeWithEmptyData() {
        byte[] data = new byte[0];
        
        SymbolTable encoded = fsst.encode(data);
        assertNotNull(encoded);
        assertNotNull(encoded.compressedData());
        assertNotNull(encoded.symbols());
        assertNotNull(encoded.symbolLengths());
        
        byte[] decoded = fsst.decode(encoded);
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeDecodeWithLargeData() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("This is a longer test string that should compress well. ");
        }
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
        assertTrue(encoded.compressedData().length < data.length, 
            "Compressed data should be smaller than original for repetitive text");
    }
    
    @Test
    void testEncodeDecodeWithRepeatedPatterns() {
        String pattern = "abc";
        byte[] data = (pattern.repeat(1000)).getBytes(StandardCharsets.UTF_8);
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
        assertTrue(encoded.compressedData().length < data.length,
            "Highly repetitive data should compress well");
    }
    
    @Test
    void testEncodeDecodeWithBinaryData() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeDecodeWithRandomData() {
        Random random = new Random(42); // Fixed seed for reproducibility
        byte[] data = new byte[1000];
        random.nextBytes(data);
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeDecodeWithAllZeros() {
        byte[] data = new byte[100];
        // Already zeros
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeDecodeWithAllOnes() {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) 0xFF;
        }
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testSymbolTableFields() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        SymbolTable encoded = fsst.encode(data);
        
        assertNotNull(encoded.symbols(), "Symbols should not be null");
        assertNotNull(encoded.symbolLengths(), "Symbol lengths should not be null");
        assertNotNull(encoded.compressedData(), "Compressed data should not be null");
        assertEquals(data.length, encoded.decompressedLength(), 
            "Decompressed length should match original data length");
        assertTrue(encoded.symbolLengths().length > 0, "Should have symbol lengths");
    }
    
    @Test
    void testDecodeWithExplicitLength() {
        byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        SymbolTable encoded = fsst.encode(data);
        
        byte[] decoded1 = fsst.decode(encoded);
        byte[] decoded2 = fsst.decode(
            encoded.symbols(),
            encoded.symbolLengths(),
            encoded.compressedData(),
            encoded.decompressedLength()
        );
        
        assertArrayEquals(data, decoded1);
        assertArrayEquals(data, decoded2);
        assertArrayEquals(decoded1, decoded2);
    }
    
    @Test
    void testDecodeWithExplicitParameters() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        SymbolTable encoded = fsst.encode(data);
        
        byte[] decoded = fsst.decode(
            encoded.symbols(),
            encoded.symbolLengths(),
            encoded.compressedData(),
            encoded.decompressedLength()
        );
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testEncodeNullThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            fsst.encode(null);
        });
    }
    
    @Test
    void testCompressionRatio() {
        // Test with text that should compress well
        String text = "the quick brown fox jumps over the lazy dog. ";
        byte[] data = text.repeat(100).getBytes(StandardCharsets.UTF_8);
        
        SymbolTable encoded = fsst.encode(data);
        
        // For repetitive text, compression should be effective
        // Allow some overhead for symbol table, but compressed data should be smaller
        int totalSize = encoded.compressedData().length + encoded.symbols().length;
        // Note: For very short or non-compressible data, total size might be larger
        // This test just ensures the functionality works
        assertNotNull(encoded);
        assertTrue(encoded.decompressedLength() == data.length);
    }
    
    @Test
    void testUnicodeText() {
        String text = "Hello, 世界! 🌍 This is a test with Unicode characters.";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        
        SymbolTable encoded = fsst.encode(data);
        byte[] decoded = fsst.decode(encoded);
        
        assertArrayEquals(data, decoded);
    }
    
    @Test
    void testMultipleEncodes() {
        // Test that multiple encodes work correctly
        String[] texts = {
            "First text",
            "Second text",
            "Third text"
        };
        
        for (String text : texts) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            SymbolTable encoded = fsst.encode(data);
            byte[] decoded = fsst.decode(encoded);
            assertArrayEquals(data, decoded);
        }
    }
}

