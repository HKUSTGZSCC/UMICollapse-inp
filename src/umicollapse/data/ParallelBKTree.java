package umicollapse.data;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import umicollapse.util.BitSet;
import static umicollapse.util.Utils.umiDist;

public class ParallelBKTree implements ParallelDataStructure{
    private int umiLength;
    private Node root;
    private static final boolean simdEnabled = Boolean.getBoolean("SIMD");
    private static final boolean forkJoinEnabled = Boolean.getBoolean("FJ");
    // 新增：添加缓存开关，根据系统属性 "--cache" 开启缓存功能
    private static final boolean cacheEnabled = Boolean.getBoolean("CACHE");
    // 新增：距离缓存（空间换时间）
    private static final ConcurrentHashMap<String, Integer> distanceCache = new ConcurrentHashMap<>();

    // 修改：辅助方法中根据 cacheEnabled 决定是否缓存
    private static int getCachedDistance(BitSet a, BitSet b){
        if(!cacheEnabled)
            return umiDist(a, b);
        // 构造顺序一致的key
        String key = a.hashCode() <= b.hashCode() ?
                        a.hashCode() + "_" + b.hashCode() :
                        b.hashCode() + "_" + a.hashCode();
        return distanceCache.computeIfAbsent(key, k -> umiDist(a, b));
    }

    @Override
    public void init(Map<BitSet, Integer> umiFreq, int umiLength, int maxEdits){
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
    public Set<BitSet> near(BitSet umi, int k, int maxFreq){
        Set<BitSet> res = new HashSet<>();
        res.add(umi);
        recursiveNear(umi, root, k, maxFreq, res);
        return res;
    }

    // 修改：替换 umiDist 调用为 getCachedDistance
    private void recursiveNear(BitSet umi, Node curr, int k, int maxFreq, Set<BitSet> res){
        int dist = getCachedDistance(umi, curr.getUMI());

        if(dist <= k && curr.getFreq() <= maxFreq)
            res.add(curr.getUMI());

        if(curr.hasNodes()){
            int lo = Math.max(dist - k, 0);
            int hi = Math.min(dist + k, umiLength);

            if(forkJoinEnabled) {
                // 采用ForkJoin（parallelStream）方式并行遍历符合条件的子节点
                int childCount = umiLength + 1;
                List<Integer> indices = new ArrayList<>();
                for(int i = 0; i < childCount; i++){
                    if(curr.hasNode(i) && i >= lo && i <= hi && curr.minFreq(i) <= maxFreq){
                        indices.add(i);
                    }
                }
                indices.parallelStream().forEach(index -> {
                    Node child = curr.get(index);
                    int childDist = getCachedDistance(umi, child.getUMI());
                    if(childDist <= k) {
                        synchronized(res) {
                            res.add(child.getUMI());
                        }
                    }
                    recursiveNear(umi, child, k, maxFreq, res);
                });
            } else if(simdEnabled) {
                // 使用SIMD思路批量收集可以递归处理的子节点索引
                int childCount = umiLength + 1;
                int[] indices = new int[childCount];
                int validCount = 0;
                for(int i = 0; i < childCount; i++){
                    if(curr.hasNode(i) && i >= lo && i <= hi && curr.minFreq(i) <= maxFreq){
                        indices[validCount++] = i;
                    }
                }
                // 批量计算距离，利用内部BitSet中SIMD优化的 umiDist
                int[] dists = new int[validCount];
                for(int j = 0; j < validCount; j++){
                    dists[j] = getCachedDistance(umi, curr.get(indices[j]).getUMI());
                }
                // SIMD“过滤”：对符合距离条件的子节点递归调用
                for(int j = 0; j < validCount; j++){
                    if(dists[j] <= k){
                        res.add(curr.get(indices[j]).getUMI());
                        recursiveNear(umi, curr.get(indices[j]), k, maxFreq, res);
                    }
                }
            } else {
                // 原有串行遍历
                for(int i = 0; i < umiLength + 1; i++){
                    if(curr.hasNode(i)){
                        if(i >= lo && i <= hi && curr.minFreq(i) <= maxFreq)
                            recursiveNear(umi, curr.get(i), k, maxFreq, res);
                    }
                }
            }
        }
    }

    private void insert(BitSet umi, int freq){
        Node curr = root;
        int dist;

        do{
            dist = getCachedDistance(umi, curr.getUMI());
            curr.setMinFreq(Math.min(curr.getMinFreq(), freq));
        }while((curr = curr.initNode(dist, umi, umiLength, freq)) != null);
    }

    private static class Node{
        private BitSet umi;
        private Node[] c;
        private int freq, minFreq;

        Node(BitSet umi, int freq){
            this.c = null;
            this.umi = umi;
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
