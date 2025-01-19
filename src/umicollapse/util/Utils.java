package umicollapse.util;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorOperators;

public class Utils {
    public static final int HASH_CONST = 31;
    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
    private static final int VECTOR_LENGTH = SPECIES.length();

    // fast Hamming distance by using pairwise equidistant encodings for each nucleotide
    public static int umiDist(BitSet a, BitSet b){
        // divide by the pairwise Hamming distance in the encoding
        return a.bitCountXOR(b) / Read.ENCODING_DIST;
    }

    public static boolean charEquals(BitSet a, int idx, int b){
        for(int i = 0; i < Read.ENCODING_LENGTH; i++){
            if(a.get(idx * Read.ENCODING_LENGTH + i) != ((b & (1 << i)) != 0))
                return false;
        }

        return true;
    }

    public static BitSet charSet(BitSet a, int idx, int b) {
        int baseIndex = idx * Read.ENCODING_LENGTH;
        long encodingMask = 0L;
        for(int i = 0; i < Read.ENCODING_LENGTH; i++) {
            if ((b & (1 << i)) != 0) {
                encodingMask |= (1L << i);
            }
        }
        
        int chunkIdx = baseIndex / 64;
        int bitOffset = baseIndex % 64;
        
        long mask = ~(((1L << Read.ENCODING_LENGTH) - 1L) << bitOffset);
        long shiftedMask = encodingMask << bitOffset;
        a.applyEncodingMask(chunkIdx, mask, shiftedMask);
        
        return a;
    }

    private static BitSet charSetNBit(BitSet a, int idx){
        for(int i = 0; i < Read.ENCODING_LENGTH; i++)
            a.setNBit(idx * Read.ENCODING_LENGTH + i, true);

        return a;
    }

    public static int charGet(BitSet a, int idx) {
        int baseIndex = idx * Read.ENCODING_LENGTH;
        int chunkIdx = baseIndex / 64;
        int bitOffset = baseIndex % 64;
        
        long bitsVal = a.extractBits(chunkIdx) >>> bitOffset;
        return (int)(bitsVal & ((1L << Read.ENCODING_LENGTH) - 1));
    }

    public static BitSet toBitSet(String s) {
        int length = s.length();
        BitSet res = new BitSet(length * Read.ENCODING_LENGTH);
        
        // Process in chunks that align with vector boundaries
        int i = 0;
        for (; i + VECTOR_LENGTH <= length; i += VECTOR_LENGTH) {
            long[] encodedChunk = new long[VECTOR_LENGTH];
            for (int j = 0; j < VECTOR_LENGTH; j++) {
                char c = s.charAt(i + j);
                int encoded = Read.ENCODING_MAP.get(c);
                encodedChunk[j] = encoded;
                if (c == Read.UNDETERMINED_CHAR) {
                    charSetNBit(res, i + j);
                }
            }
            
            LongVector vec = LongVector.fromArray(SPECIES, encodedChunk, 0);
            for (int j = 0; j < VECTOR_LENGTH; j++) {
                charSet(res, i + j, (int)encodedChunk[j]);
            }
        }
        
        // Handle remaining elements
        for (; i < length; i++) {
            char c = s.charAt(i);
            charSet(res, i, Read.ENCODING_MAP.get(c));
            if (c == Read.UNDETERMINED_CHAR) {
                charSetNBit(res, i);
            }
        }
        
        return res;
    }

    public static String toString(BitSet a, int length){
        char[] res = new char[length];

        for(int i = 0; i < length; i++)
            res[i] = Read.ALPHABET[Read.ENCODING_IDX.get(charGet(a, i))];

        return new String(res);
    }

    // converts quality string to byte array, using the Phred+33 format
    public static byte[] toPhred33ByteArray(String q){
        byte[] res = new byte[q.length()];

        for(int i = 0; i < q.length(); i++)
            res[i] = (byte)(q.charAt(i) - '!');

        return res;
    }

    // converts byte array to quality string, using the Phred+33 format
    public static String toPhred33String(byte[] q){
        char[] res = new char[q.length];

        for(int i = 0; i < q.length; i++)
            res[i] = (char)(q[i] + '!');

        return new String(res);
    }
}
