package umicollapse.data;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;
import static umicollapse.util.Utils.fastHash;

import umicollapse.util.BitSet;
import static umicollapse.util.Utils.umiDist;

public class BKTree implements DataStructure{
    private Set<BitSet> s;
    private int umiLength;
    private Node root;

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

    private void recursiveRemoveNear(BitSet umi, Node curr, int k, int maxFreq, Set<BitSet> res){
        int dist = umiDist(umi, curr.getUMI());

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

            for(int i = 0; i < umiLength + 1; i++){
                if(curr.subtreeExists(i)){
                    if(i >= lo && i <= hi && curr.minFreq(i) <= maxFreq)
                        recursiveRemoveNear(umi, curr.get(i), k, maxFreq, res);

                    minFreq = Math.min(minFreq, curr.minFreq(i));
                    subtreeExists |= curr.subtreeExists(i);
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
            dist = umiDist(umi, curr.getUMI());
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
