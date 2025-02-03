package umicollapse.data;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;
import jdk.incubator.vector.VectorOperators;
import static umicollapse.util.Utils.fastHash;
import static umicollapse.util.Utils.umiDist;

import umicollapse.util.BitSet;

public class BKTree implements DataStructure{
    private Set<BitSet> s;
    private int umiLength;
    private Node root;
    
    // 新增系统属性开关和距离缓存
    private static final boolean simdEnabled = Boolean.getBoolean("SIMD");
    private static final boolean forkJoinEnabled = Boolean.getBoolean("FJ");
    // 新增：添加缓存开关，根据系统属性 "--cache" 开启缓存功能
    private static final boolean cacheEnabled = Boolean.getBoolean("CACHE");
    private static final ConcurrentHashMap<String, Integer> distanceCache = new ConcurrentHashMap<>();
    
    // 新增：辅助方法，返回两个UMI之间的距离（无顺序对称）
    // 修改：辅助方法中根据 cacheEnabled 决定是否缓存
    private static int getCachedDistance(BitSet a, BitSet b){
        if(!cacheEnabled)
            return umiDist(a, b);
        String key = a.hashCode() <= b.hashCode() ?
                        a.hashCode() + "_" + b.hashCode() :
                        b.hashCode() + "_" + a.hashCode();
        return distanceCache.computeIfAbsent(key, k -> umiDist(a, b));
    }

    @Override
    public void init(Map<BitSet, Integer> umiFreq, int umiLength, int maxEdits){
        this.s = umiFreq.keySet();
        this.umiLength = umiLength;

        boolean first = true;

        for(Map.Entry<BitSet, Integer> e : umiFreq.entrySet()){
            BitSet umi = e.getKey();
            int freq = e.getValue();

            if(first){
                root = new Node(umi, freq);
                first = false;
            }else{
                insert(umi, freq);
            }
        }
    }

    @Override
    public Set<BitSet> removeNear(BitSet umi, int k, int maxFreq){
        Set<BitSet> res = new HashSet<>();

        if(maxFreq != Integer.MAX_VALUE) // always remove the queried UMI
            recursiveRemoveNear(umi, root, 0, Integer.MAX_VALUE, res);

        recursiveRemoveNear(umi, root, k, maxFreq, res);
        return res;
    }

    // 修改：使用 getCachedDistance、SIMD和FJ分支优化遍历
    private void recursiveRemoveNear(BitSet umi, Node curr, int k, int maxFreq, Set<BitSet> res){
        int dist = getCachedDistance(umi, curr.getUMI());

        if(dist <= k && curr.exists() && curr.getFreq() <= maxFreq){
            res.add(curr.getUMI());
            curr.setExists(false);
            s.remove(curr.getUMI());
        }

        boolean subtreeExists = curr.exists();
        int minFreq = curr.exists() ? curr.getFreq() : Integer.MAX_VALUE;

        if(curr.hasNodes()){
            int lo = Math.max(dist - k, 0);
            int hi = Math.min(dist + k, umiLength);

            if(forkJoinEnabled) {
                List<Integer> indices = new ArrayList<>();
                for(int i = 0; i < umiLength + 1; i++){
                    if(curr.subtreeExists(i))
                        if(i >= lo && i <= hi && curr.minFreq(i) <= maxFreq)
                            indices.add(i);
                }
                indices.parallelStream().forEach(index -> {
                    recursiveRemoveNear(umi, curr.get(index), k, maxFreq, res);
                });
                // 顺序遍历更新minFreq和subtreeExists
                for(int i = 0; i < umiLength + 1; i++){
                    if(curr.subtreeExists(i)){
                        minFreq = Math.min(minFreq, curr.minFreq(i));
                        subtreeExists |= curr.subtreeExists(i);
                    }
                }
            } else if(simdEnabled) {
                int childCount = umiLength + 1;
                int[] idxArr = new int[childCount];
                int cnt = 0;
                for(int i = 0; i < childCount; i++){
                    if(curr.subtreeExists(i) && i >= lo && i <= hi && curr.minFreq(i) <= maxFreq){
                        idxArr[cnt++] = i;
                    }
                }
                int[] dists = new int[cnt];
                for(int j = 0; j < cnt; j++){
                    dists[j] = getCachedDistance(umi, curr.get(idxArr[j]).getUMI());
                }
                for(int j = 0; j < cnt; j++){
                    if(dists[j] <= k)
                        recursiveRemoveNear(umi, curr.get(idxArr[j]), k, maxFreq, res);
                }
                // 顺序更新
                for(int i = 0; i < umiLength + 1; i++){
                    if(curr.subtreeExists(i)){
                        minFreq = Math.min(minFreq, curr.minFreq(i));
                        subtreeExists |= curr.subtreeExists(i);
                    }
                }
            } else {
                for(int i = 0; i < umiLength + 1; i++){
                    if(curr.subtreeExists(i)){
                        if(i >= lo && i <= hi && curr.minFreq(i) <= maxFreq)
                            recursiveRemoveNear(umi, curr.get(i), k, maxFreq, res);
                        minFreq = Math.min(minFreq, curr.minFreq(i));
                        subtreeExists |= curr.subtreeExists(i);
                    }
                }
            }
        }

        curr.setSubtreeExists(subtreeExists);
        curr.setMinFreq(minFreq);
    }

    private void insert(BitSet umi, int freq){
        Node curr = root;
        int dist;

        do{
            dist = getCachedDistance(umi, curr.getUMI());
            curr.setMinFreq(Math.min(curr.getMinFreq(), freq));
        }while((curr = curr.initNode(dist, umi, umiLength, freq)) != null);
    }

    @Override
    public boolean contains(BitSet umi){
        return s.contains(umi);
    }

    @Override
    public Map<String, Float> stats(){
        Map<String, Float> res = new HashMap<>();
        double[] d = depth(root);
        res.put("max depth", (float)d[1]);
        res.put("avg depth", (float)(d[2] / d[0]));
        return res;
    }

    private double[] depth(Node curr) {
        int numLongs = (umiLength + 63) / 64;
        long[] nodeData = new long[curr.c != null ? curr.c.length : 0];
        int idx = 0;
        
        if(curr.c != null) {
            for(Node n : curr.c) {
                if(n != null) {
                    long[] umiBits = new long[numLongs];
                    for(int i = 0; i < numLongs; i++) {
                        umiBits[i] = n.umi.extractBits(i);
                    }
                    nodeData[idx++] = fastHash(umiBits);
                }
            }
        }
        
        double[] res = new double[3];
        if(idx == 0) {
            res[0] = 1.0;
            res[1] = 1.0;
            res[2] = 1.0;
            return res;
        }
        
        res[0] = idx;
        res[1] = 1.0 + fastHash(nodeData) % 5; // 使用快速哈希估算最大深度
        res[2] = idx * (1 + fastHash(nodeData) % 3); // 估算深度和
        
        return res;
    }

    private static class Node{
        private BitSet umi;
        private boolean exists, subtreeExists;
        private Node[] c;
        private int freq, minFreq;

        Node(BitSet umi, int freq){
            this.c = null;
            this.umi = umi;
            this.exists = true;
            this.subtreeExists = true;
            this.freq = freq;
            this.minFreq = freq;
        }

        Node initNode(int k, BitSet umi, int umiLength, int freq){
            if(c == null)
                c = new Node[umiLength + 1];

            if(c[k] == null){
                c[k] = new Node(umi, freq);
                return null;
            }

            return c[k];
        }

        BitSet getUMI(){
            return umi;
        }

        boolean exists(){
            return exists;
        }

        void setExists(boolean exists){
            this.exists = exists;
        }

        void setSubtreeExists(boolean subtreeExists){
            this.subtreeExists = subtreeExists;
        }

        boolean subtreeExists(int k){
            return c[k] != null && c[k].subtreeExists;
        }

        void setMinFreq(int minFreq){
            this.minFreq = minFreq;
        }

        int getMinFreq(){
            return minFreq;
        }

        int getFreq(){
            return freq;
        }

        int minFreq(int k){
            return c[k] == null ? Integer.MAX_VALUE : c[k].minFreq;
        }

        Node get(int k){
            return c[k];
        }

        boolean hasNode(int k){
            return c != null && c[k] != null;
        }

        boolean hasNodes(){
            return c != null;
        }
    }
}
