package nl.bartlouwers.fsst;

/**
 * Interface for FSST (Fast Static Symbol Table) compression operations.
 */
public interface Fsst {

  /**
   * Encode data using FSST compression.
   * 
   * @param data The input data to compress
   * @return SymbolTable containing the compression result
   */
  SymbolTable encode(byte[] data);

  /**
   * Decode compressed data using a SymbolTable.
   * 
   * @param encoded The SymbolTable containing compression information
   * @return The decompressed data
   */
  byte[] decode(SymbolTable encoded);

  /**
   * Decode compressed data using explicit parameters.
   * 
   * @param symbols The symbol table
   * @param symbolLengths Array of lengths for each symbol
   * @param compressedData The compressed data
   * @param decompressedLength The expected length of decompressed data
   * @return The decompressed data
   */
  byte[] decode(
      byte[] symbols, int[] symbolLengths, byte[] compressedData, int decompressedLength);
}

