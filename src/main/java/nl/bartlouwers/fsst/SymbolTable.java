package nl.bartlouwers.fsst;

/**
 * Represents the result of FSST compression, containing the symbol table
 * and compressed data needed for decompression.
 */
public record SymbolTable(
    byte[] symbols,
    int[] symbolLengths,
    byte[] compressedData,
    int decompressedLength
) {
}

