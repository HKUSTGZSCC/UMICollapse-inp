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
            byte[] quals = record.getBaseQualities();
            if (quals.length > 0) {
                try {
                    qualBuffer = ByteBufferPool.acquire();
                    qualBuffer.put(quals);
                    qualBuffer.flip();
                } catch (OutOfMemoryError e) {
                    // 如果无法获取新的ByteBuffer，回退到普通模式
                    qualBuffer = null;
                    calculateAvgQual();
                }
            }
        }
        calculateAvgQual();
    }

    private void calculateAvgQual() {
        byte[] quals;
        int len;
        
        if (quickIOEnabled) {
            if (qualBuffer == null || !qualBuffer.hasRemaining()) {
                avgQual = 0;
                return;
            }
            len = qualBuffer.remaining();
            quals = new byte[len];
            int pos = qualBuffer.position();
            for (int i = 0; i < len; i++) {
                quals[i] = qualBuffer.get(pos + i);
            }
        } else {
            quals = record.getBaseQualities();
            len = quals.length;
            if (len == 0) {
                avgQual = 0;
                return;
            }
        }
        
        long sum = 0;
        for (int i = 0; i < len; i++) {
            sum += quals[i];
        }
        
        avgQual = (int)(sum / len);
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

    // 删除已弃用的finalize()方法
    
    // 提供显式清理方法
    public void cleanup() {
        if (qualBuffer != null) {
            ByteBufferPool.release(qualBuffer);
            qualBuffer = null;
        }
    }
}
