package umicollapse.util;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class ByteBufferPool {
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final long MAX_MEMORY = Runtime.getRuntime().maxMemory() / 2; // 使用最大内存的一半
    private static final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
    private static final AtomicLong allocatedMemory = new AtomicLong(0);
    
    public static ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer != null) {
            buffer.clear();
            return buffer;
        }
        
        long currentAllocated = allocatedMemory.get();
        if (currentAllocated >= MAX_MEMORY) {
            throw new OutOfMemoryError("ByteBufferPool memory limit reached: " + currentAllocated + " >= " + MAX_MEMORY);
        }
        
        buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        allocatedMemory.addAndGet(BUFFER_SIZE);
        return buffer;
    }
    
    public static void release(ByteBuffer buffer) {
        if (buffer != null) {
            buffer.clear();
            pool.offer(buffer);
        }
    }
    
    public static void clear() {
        pool.clear();
        allocatedMemory.set(0);
    }
    
    public static long getAllocatedMemory() {
        return allocatedMemory.get();
    }
}
