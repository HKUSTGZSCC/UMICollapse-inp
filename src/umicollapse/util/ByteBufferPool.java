package umicollapse.util;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ByteBufferPool {
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
    
    public static ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer == null) {
            buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        }
        return buffer;
    }
    
    public static void release(ByteBuffer buffer) {
        buffer.clear();
        pool.offer(buffer);
    }
}
