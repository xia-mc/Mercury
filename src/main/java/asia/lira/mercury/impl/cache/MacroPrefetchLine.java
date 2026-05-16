package asia.lira.mercury.impl.cache;

import net.minecraft.nbt.NbtElement;
import net.minecraft.server.function.MacroException;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public final class MacroPrefetchLine implements MacroArgumentProvider {
    private final MacroPrefetchKey key;
    private final List<String> argumentNames;
    private final NbtElement[] values;
    private boolean valid;
    private boolean active;
    private String lastInvalidationReason;
    private long hits;
    private long misses;
    private long recentMacroCalls;
    private long recentStoreWrites;
    private long storeThenCallMatches;
    private int lastStoreGeneration;
    private int lastCallGeneration;

    public MacroPrefetchLine(MacroPrefetchKey key, List<String> argumentNames) {
        this.key = key;
        this.argumentNames = List.copyOf(argumentNames);
        this.values = new NbtElement[argumentNames.size()];
        this.lastInvalidationReason = "cold";
    }

    public MacroPrefetchKey key() {
        return key;
    }

    public List<String> argumentNames() {
        return argumentNames;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isActive() {
        return active;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long recentMacroCalls() {
        return recentMacroCalls;
    }

    public long recentStoreWrites() {
        return recentStoreWrites;
    }

    public long storeThenCallMatches() {
        return storeThenCallMatches;
    }

    public String lastInvalidationReason() {
        return lastInvalidationReason;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate(String reason) {
        this.active = false;
        invalidate(reason);
    }

    public void invalidate(String reason) {
        this.valid = false;
        this.lastInvalidationReason = reason;
        Arrays.fill(this.values, null);
    }

    public void refresh(List<NbtElement> nextValues) {
        if (nextValues.size() != values.length) {
            invalidate("shape-mismatch");
            return;
        }
        for (int i = 0; i < nextValues.size(); i++) {
            NbtElement value = nextValues.get(i);
            values[i] = value == null ? null : value.copy();
        }
        this.valid = true;
        this.lastInvalidationReason = "";
    }

    public void recordStoreEvent(int generation) {
        recentStoreWrites++;
        lastStoreGeneration = generation;
    }

    public void recordMacroCall(int generation) {
        recentMacroCalls++;
        lastCallGeneration = generation;
        if (lastStoreGeneration > 0 && generation - lastStoreGeneration <= 8) {
            storeThenCallMatches++;
        }
    }

    public void recordHit() {
        hits++;
    }

    public void recordMiss() {
        misses++;
    }

    public @Nullable NbtElement valueAt(int index) {
        if (!valid || index < 0 || index >= values.length) {
            return null;
        }
        return values[index];
    }

    @Override
    public NbtElement resolveArgument(String name, int index) throws MacroException {
        if (!valid || index < 0 || index >= values.length || values[index] == null) {
            throw new MacroException(net.minecraft.text.Text.literal("Missing prefetched macro argument " + name));
        }
        return values[index];
    }
}
