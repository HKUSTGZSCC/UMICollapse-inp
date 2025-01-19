package umicollapse.util;

import htsjdk.samtools.SAMRecord;
import java.nio.ByteBuffer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;

public class SAMRead extends Read{
    private static Pattern defaultUMIPattern;
    private SAMRecord record;
    private volatile int avgQual = -1;
    private ByteBuffer qualBuffer;

    public SAMRead(SAMRecord record){
        this.record = record;
        if (!quickIOEnabled) {
            lazyLoad();
            isLoaded = true;
        }
    }

    public static void setDefaultUMIPattern(String sep){
        defaultUMIPattern = umiPattern(sep);
    }

    public static Pattern umiPattern(String sep){
        return Pattern.compile("^(.*)" + sep + "([ATCGN]+)(.*?)$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public BitSet getUMI(int maxLength){
        Matcher m = defaultUMIPattern.matcher(record.getReadName());
        m.find();
        String umi = m.group(2);
        if(maxLength >= 0 && umi.length() > maxLength)
            umi = umi.substring(0, maxLength);
        return Utils.toBitSet(umi.toUpperCase());
    }

    @Override
    public int getUMILength(){
        Matcher m = defaultUMIPattern.matcher(record.getReadName());
        m.find();
        return m.group(2).length();
    }

    @Override
    protected void lazyLoad() {
        if (quickIOEnabled) {
            qualBuffer = ByteBufferPool.acquire();
            qualBuffer.put(record.getBaseQualities());
            qualBuffer.flip();
        }
        calculateAvgQual();
    }

    private void calculateAvgQual() {
        if (avgQual != -1) return;
        
        byte[] quals = quickIOEnabled ? qualBuffer.array() : record.getBaseQualities();
        int length = quals.length;
        
        if (quickIOEnabled) {
            int sum = 0;
            int i = 0;
            
            // 使用SIMD向量化计算
            int upperBound = SPECIES.loopBound(length);
            ByteVector acc = ByteVector.zero(SPECIES);
            
            for (; i < upperBound; i += SPECIES.length()) {
                ByteVector v = ByteVector.fromArray(SPECIES, quals, i);
                acc = acc.add(v);
            }
            
            sum = acc.reduceLanes(VectorOperators.ADD);
            
            // 处理剩余元素
            for (; i < length; i++) {
                sum += quals[i];
            }
            
            avgQual = sum / length;
        } else {
            float avg = 0.0f;
            for (byte b : quals) {
                avg += b;
            }
            avgQual = (int)(avg / length);
        }
    }

    @Override
    public int getAvgQual(){
        ensureLoaded();
        return avgQual;
    }

    @Override
    public boolean equals(Object o){
        SAMRead r = (SAMRead)o;
        return record.equals(r.record);
    }

    public int getMapQual(){
        return record.getMappingQuality();
    }

    public SAMRecord toSAMRecord(){
        return record;
    }
}
