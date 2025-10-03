package asia.lira.mercury.stat;

@SuppressWarnings("unused")
public interface FastMacroStatsMBean {
    long getCacheHits();

    long getCacheMisses();

    double getHitRate();

    void resetStats();
}
