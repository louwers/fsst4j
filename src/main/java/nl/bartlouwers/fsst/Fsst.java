package nl.bartlouwers.fsst;

public interface Fsst {

  SymbolTable encode(byte[] data);

  byte[] decode(SymbolTable encoded);

  byte[] decode(
      byte[] symbols, int[] symbolLengths, byte[] compressedData, int decompressedLength);

  /**
   * @deprecated use {@link #decode(byte[], int[], byte[], int)} instead with an explicit length
   */
  @Deprecated
  byte[] decode(byte[] symbols, int[] symbolLengths, byte[] compressedData);
}

