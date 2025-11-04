package nl.bartlouwers.fsst;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Implementation of the Fsst interface using Foreign Function & Memory API.
 */
public class FsstImpl implements Fsst {
    
    /**
     * Encode data using FSST compression.
     * 
     * @param data Input data to compress
     * @return SymbolTable containing the compressed data and symbol table
     */
    @Override
    public SymbolTable encode(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }
        
        // Use an arena to manage memory lifecycle
        try (Arena arena = Arena.ofConfined()) {
            // Create encoder from input data
            MemorySegment encoder = FsstFfm.createEncoder(data, arena);
            
            try {
                // Compress the data
                byte[] compressedData = FsstFfm.compress(encoder, data, arena);
                
                // Get decoder to extract symbol table
                FsstFfm.SymbolTableData symbolData = FsstFfm.getDecoder(encoder, arena);
                
                // Return SymbolTable with all required information
                return new SymbolTable(
                    symbolData.symbols,
                    symbolData.symbolLengths,
                    compressedData,
                    data.length  // decompressed length is the original input length
                );
            } finally {
                // Clean up encoder
                FsstFfm.destroy(encoder);
            }
        }
    }
    
    @Override
    public byte[] decode(SymbolTable encoded) {
        return decode(
            encoded.symbols(),
            encoded.symbolLengths(),
            encoded.compressedData(),
            encoded.decompressedLength());
    }
    
    @Override
    public byte[] decode(
            byte[] symbols, int[] symbolLengths, byte[] compressedData, int decompressedLength) {
        // optimized decoder that knows the output size so pre-allocates the array to avoid dynamically
        // allocating a ByteArrayOutputStream
        int idx = 0;
        byte[] output = new byte[decompressedLength];
        int[] symbolOffsets = new int[symbolLengths.length];
        for (int i = 1; i < symbolLengths.length; i++) {
            symbolOffsets[i] = symbolOffsets[i - 1] + symbolLengths[i - 1];
        }
        for (int i = 0; i < compressedData.length; i++) {
            // In Java a byte[] is signed [-128 to 127], whereas in C++ it is unsigned [0 to 255]
            // So we do a bit shifting operation to convert the values into unsigned values for easier
            // handling
            int symbolIndex = compressedData[i] & 0xFF;
            // 255 is our escape byte -> take the next symbol as it is
            if (symbolIndex == 255) {
                output[idx++] = compressedData[++i];
            } else if (symbolIndex < symbolLengths.length) {
                int len = symbolLengths[symbolIndex];
                System.arraycopy(symbols, symbolOffsets[symbolIndex], output, idx, len);
                idx += len;
            }
        }
        return output;
    }
    
    @Override
    @Deprecated
    public byte[] decode(byte[] symbols, int[] symbolLengths, byte[] compressedData) {
        ByteArrayOutputStream decodedData = new ByteArrayOutputStream();
        int[] symbolOffsets = new int[symbolLengths.length];
        for (int i = 1; i < symbolLengths.length; i++) {
            symbolOffsets[i] = symbolOffsets[i - 1] + symbolLengths[i - 1];
        }
        for (int i = 0; i < compressedData.length; i++) {
            // In Java a byte[] is signed [-128 to 127], whereas in C++ it is unsigned [0 to 255]
            // So we do a bit shifting operation to convert the values into unsigned values for easier
            // handling
            int symbolIndex = compressedData[i] & 0xFF;
            // 255 is our escape byte -> take the next symbol as it is
            if (symbolIndex == 255) {
                decodedData.write(compressedData[++i] & 0xFF);
            } else if (symbolIndex < symbolLengths.length) {
                decodedData.write(symbols, symbolOffsets[symbolIndex], symbolLengths[symbolIndex]);
            }
        }
        return decodedData.toByteArray();
    }
}

