package asia.lira.mcfunctionplus.object;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Sharded SLRU cache: each shard holds a probation LRU and a protected LRU.
 * Simple and practical for caches where many keys are one-shot.
 */
public final class LongShardedSLRUCache<V> {
    private final Long2ObjectLinkedOpenHashMap<V> probation;
    private final Long2ObjectLinkedOpenHashMap<V> protectedMap;
    private final int probationCap;
    private final int protectedCap;

    public LongShardedSLRUCache(int totalCapacity, int probationFractionPercent) {
        if (probationFractionPercent <= 0 || probationFractionPercent >= 100) {
            throw new IllegalArgumentException("probationFractionPercent in (0,100)");
        }
        int probation = Math.max(1, totalCapacity * probationFractionPercent / 100);
        int prot = Math.max(1, totalCapacity - probation);
        this.probationCap = probation;
        this.protectedCap = prot;

        // fastutil maps: initialCapacity should be > expected to avoid rehashing.
        int initProb = nextPowerOfTwo(probation * 2);
        int initProt = nextPowerOfTwo(prot * 2);

        this.probation = new Long2ObjectLinkedOpenHashMap<>(initProb);
        this.protectedMap = new Long2ObjectLinkedOpenHashMap<>(initProt);
    }

    @Nullable
    public V get(long key) {
        // check protected first; move-to-last on hit
        V v = protectedMap.getAndMoveToLast(key);
        if (v != null) return v;
        v = probation.getAndMoveToLast(key);
        if (v != null) {
            // promote
            probation.remove(key);
            promoteToProtected(key, v);
            return v;
        }
        return null;
    }

    public V getOrCompute(long key, Supplier<V> loader) {
        // single-threaded: compute under this flow is fine
        V v = protectedMap.getAndMoveToLast(key);
        if (v != null) return v;
        v = probation.getAndMoveToLast(key);
        if (v != null) {
            probation.remove(key);
            promoteToProtected(key, v);
            return v;
        }
        // miss: compute value and insert into probation
        V value = loader.get();
        putUnsafe(key, value);
        return value;
    }

    private void promoteToProtected(long key, V value) {
        protectedMap.put(key, value);
        if (protectedMap.size() > protectedCap) {
            // evict eldest from protected (fastutil iteration gives in-order)
            var it = protectedMap.long2ObjectEntrySet().iterator();
            var eldest = it.next();
            long ek = eldest.getLongKey();
            V ev = eldest.getValue();
            it.remove();
            // demote eldest to probation (optional, matches many SLRU designs)
            putUnsafe(ek, ev);
        }
    }

    /**
     * Only call this method to put the value if you're sure the key is not exists.
     */
    public void putUnsafe(long key, V value) {
        probation.put(key, value);
        if (probation.size() > probationCap) {
            // evict eldest from probation (drop)
            var it = probation.long2ObjectEntrySet().iterator();
            it.next();
            it.remove();
            // drop eldest
        }
    }

    private static int nextPowerOfTwo(int x) {
        int r = 1;
        while (r < x) r <<= 1;
        return r;
    }
}
