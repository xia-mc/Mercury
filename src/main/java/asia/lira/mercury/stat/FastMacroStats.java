package asia.lira.mercury.stat;

import java.util.concurrent.atomic.AtomicLong;

public class FastMacroStats implements FastMacroStatsMBean {
    private static FastMacroStats instance;

    public static FastMacroStats getInstance() {
        if (instance == null) {
            instance = new FastMacroStats();
        }
        return instance;
    }

    private FastMacroStats() {
    }

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    @Override
    public long getCacheHits() {
        return hits.get();  // 返回实际数据
    }

    @Override
    public long getCacheMisses() {
        return misses.get();
    }

    @Override
    public double getHitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }

    @Override
    public void resetStats() {
        hits.set(0);
        misses.set(0);
    }

    public void recordHit() { hits.incrementAndGet(); }
    public void recordMiss() { misses.incrementAndGet(); }
}
