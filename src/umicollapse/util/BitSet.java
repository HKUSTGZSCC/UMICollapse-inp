package umicollapse.util;

import java.util.Arrays;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorOperators;

public class BitSet implements Comparable{
    private static final int CHUNK_SIZE = 64;

    private long[] bits;
    private long[] nBits;
    private boolean recalcHash;
    private int hash;

    public BitSet(int length){
        this.bits = new long[length / CHUNK_SIZE + (length % CHUNK_SIZE == 0 ? 0 : 1)];
        this.recalcHash = true;
    }

    private BitSet(long[] bits){
        this.bits = bits;
        this.recalcHash = true;
    }

    private BitSet(long[] bits, int hash){
        this.bits = bits;
        this.recalcHash = false;
        this.hash = hash;
    }

    public boolean get(int idx){
        return (bits[idx / CHUNK_SIZE] & (1L << (idx % CHUNK_SIZE))) != 0L;
    }

    // does not set the nBits array, so distance calculations could be wrong if not careful!
    public void set(int idx, boolean bit){
        recalcHash = true;
        int i = idx / CHUNK_SIZE;
        int j = idx % CHUNK_SIZE;
        bits[i] = bit ? (bits[i] | (1L << j)) : (bits[i] & ~(1L << j));
    }

    public void setNBit(int idx, boolean bit){
        if(nBits == null)
            nBits = new long[bits.length];

        int i = idx / CHUNK_SIZE;
        int j = idx % CHUNK_SIZE;
        nBits[i] = bit ? (nBits[i] | (1L << j)) : (nBits[i] & ~(1L << j));
    }

    public int bitCountXOR(BitSet o){
        int res = 0;

        // 向量化处理
        final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
        int i = 0;
        int upperBound = bits.length - (bits.length % SPECIES.length());
        for(; i < upperBound; i += SPECIES.length()){
            LongVector v1 = LongVector.fromArray(SPECIES, bits, i);
            long mask1 = (nBits == null ? 0L : nBits[i]);
            LongVector m1 = LongVector.broadcast(SPECIES, mask1);

            LongVector v2 = LongVector.fromArray(SPECIES, o.bits, i);
            long mask2 = (o.nBits == null ? 0L : o.nBits[i]);
            LongVector m2 = LongVector.broadcast(SPECIES, mask2);

            LongVector xorMask = m1.lanewise(VectorOperators.XOR, m2);
            LongVector xorBits = v1.lanewise(VectorOperators.XOR, v2);
            LongVector combined = xorMask.lanewise(VectorOperators.OR, xorBits);
            LongVector popCombined = combined.lanewise(VectorOperators.BIT_COUNT);
            long sumCombined = popCombined.reduceLanes(VectorOperators.ADD);
            LongVector popMask = xorMask.lanewise(VectorOperators.BIT_COUNT);
            long sumMask = popMask.reduceLanes(VectorOperators.ADD);
            int count = (int)(sumCombined - sumMask / Read.ENCODING_LENGTH);
            res += count;
        }

        // 处理剩余部分
        for(; i < bits.length; i++){
            long xor = (nBits == null ? 0L : nBits[i]) ^ (o.nBits == null ? 0L : o.nBits[i]);
            long combined = (xor | (bits[i] ^ o.bits[i]));
            res += Long.bitCount(combined) - Long.bitCount(xor) / Read.ENCODING_LENGTH;
        }

        return res;
    }

    @Override
    public boolean equals(Object obj){
        if(!(obj instanceof BitSet))
            return false;

        BitSet o = (BitSet)obj;

        if(this == o)
            return true;

        if(bits.length != o.bits.length)
            return false;

        for(int i = 0; i < bits.length; i++){
            if(bits[i] != o.bits[i])
                return false;
        }

        return true;
    }

    @Override
    public int compareTo(Object o){
        BitSet other = (BitSet)o;

        if(bits.length != other.bits.length)
            return bits.length - other.bits.length;

        for(int i = 0; i < bits.length; i++){
            if(bits[i] != other.bits[i])
                return Long.compare(bits[i], other.bits[i]);
        }

        return 0;
    }

    public BitSet clone(){
        if(recalcHash)
            return new BitSet(Arrays.copyOf(bits, bits.length));
        else
            return new BitSet(Arrays.copyOf(bits, bits.length), hash);
    }

    @Override
    public int hashCode(){
        if(recalcHash){
            long h = 1234L; // same as Java's built-in BitSet hash function

            for(int i = bits.length; --i >= 0;)
                h ^= bits[i] * (i + 1L);

            hash = (int)((h >> 32) ^ h);
            recalcHash = false;
        }

        return hash;
    }

    @Override
    public String toString(){
        StringBuilder res = new StringBuilder();

        for(int i = 0; i < bits.length; i++){
            String s = Long.toBinaryString(bits[i]);
            res.append(reverse(s));
            res.append(make('0', CHUNK_SIZE - s.length()));
        }

        return res.toString();
    }

    private String make(char c, int n){
        char[] res = new char[n];

        for(int i = 0; i < n; i++)
            res[i] = c;

        return new String(res);
    }

    private String reverse(String s){
        char[] res = new char[s.length()];

        for(int i = 0; i < s.length(); i++)
            res[i] = s.charAt(s.length() - 1 - i);

        return new String(res);
    }

    public void applyEncodingMask(int chunkIdx, long maskClear, long maskSet) {
        recalcHash = true;
        bits[chunkIdx] &= maskClear;
        bits[chunkIdx] |= maskSet;
    }

    public long extractBits(int chunkIdx) {
        return bits[chunkIdx];
    }
}
