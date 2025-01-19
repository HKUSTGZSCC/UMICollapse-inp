package umicollapse.util;

import java.util.Arrays;
import java.nio.ByteBuffer;

import htsjdk.samtools.fastq.FastqRecord;

import static umicollapse.util.Utils.toBitSet;
import static umicollapse.util.Utils.toPhred33ByteArray;
import static umicollapse.util.Utils.toPhred33String;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;

public class FASTQRead extends Read{
    private String desc;
    private BitSet seq;
    private volatile byte[] qual;
    private ByteBuffer qualBuffer;
    private volatile int avgQual = -1;
    private String cacheKey;

    public FASTQRead(String desc, String umi, String seq, String qual){
        this.desc = desc;
        this.seq = toBitSet(umi.toUpperCase() + seq.toUpperCase());
        this.cacheKey = qual;
        
        if (!quickIOEnabled) {
            lazyLoad();
            isLoaded = true;
        }
    }

    public FASTQRead(String desc, String seq, String qual) {
        this(desc, "", seq, qual);
    }

    @Override
    protected void lazyLoad() {
        if (quickIOEnabled) {
            qual = qualityCache.computeIfAbsent(cacheKey, k -> {
                ByteBuffer buf = ByteBufferPool.acquire();
                byte[] result = toPhred33ByteArray(k);
                buf.put(result);
                buf.flip();
                qualBuffer = buf;
                return result;
            });
        } else {
            qual = toPhred33ByteArray(cacheKey);
        }
        calculateAvgQual();
    }

    private void calculateAvgQual() {
        if (avgQual != -1) return;
        
        if (quickIOEnabled) {
            int length = qual.length;
            int sum = 0;
            int i = 0;
            
            // 使用SIMD向量化计算
            int upperBound = SPECIES.loopBound(length);
            ByteVector acc = ByteVector.zero(SPECIES);
            
            for (; i < upperBound; i += SPECIES.length()) {
                ByteVector v = ByteVector.fromArray(SPECIES, qual, i);
                acc = acc.add(v);
            }
            
            sum = acc.reduceLanes(VectorOperators.ADD);
            
            // 处理剩余元素
            for (; i < length; i++) {
                sum += qual[i];
            }
            
            avgQual = sum / length;
        } else {
            float avg = 0.0f;
            for (byte b : qual) {
                avg += b;
            }
            avgQual = (int)(avg / qual.length);
        }
    }

    @Override
    public BitSet getUMI(int maxLength){
        return seq;
    }

    @Override
    public int getUMILength(){
        return -1; // should never be called!
    }

    @Override
    public int getAvgQual(){
        return avgQual;
    }

    @Override
    public boolean equals(Object o){
        FASTQRead r = (FASTQRead)o;

        if(!seq.equals(r.seq))
            return false;

        if(!desc.equals(r.desc))
            return false;

        if(!Arrays.equals(qual, r.qual))
            return false;

        return true;
    }

    public FastqRecord toFASTQRecord(int length, int umiLength){
        return new FastqRecord(desc, Utils.toString(seq, length).substring(umiLength), "", Utils.toPhred33String(qual).substring(umiLength));
    }
}
