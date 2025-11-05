package nl.bartlouwers.fsst;

/**
 * Represents the result of FSST compression, containing the symbol table
 * and compressed data needed for decompression.
 * 
 * @param symbols The symbol table containing the compression symbols
 * @param symbolLengths Array of lengths for each symbol in the symbol table
 * @param compressedData The compressed data
 * @param decompressedLength The length of the original decompressed data
 */
public record SymbolTable(
    byte[] symbols,
    int[] symbolLengths,
    byte[] compressedData,
    int decompressedLength
) {
}

