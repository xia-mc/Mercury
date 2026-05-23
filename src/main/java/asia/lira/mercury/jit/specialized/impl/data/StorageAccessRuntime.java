package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.Mercury;
import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import com.google.common.collect.Iterables;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtShort;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;

public final class StorageAccessRuntime {
    private static final SimpleCommandExceptionType MERGE_FAILED_EXCEPTION = new SimpleCommandExceptionType(Text.translatable("commands.data.merge.failed"));
    private static final DynamicCommandExceptionType MODIFY_EXPECTED_OBJECT_EXCEPTION = new DynamicCommandExceptionType(
            nbt -> Text.stringifiedTranslatable("commands.data.modify.expected_object", nbt)
    );

    private StorageAccessRuntime() {
    }

    public static int setValue(Identifier storageId, NbtPathArgumentType.NbtPath targetPath, NbtElement value) throws CommandSyntaxException {
        NbtCompound root = getMutable(storageId);
        int changed = targetPath.put(root, value.copy());
        if (changed == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        save(storageId, root);
        MacroPrefetchRuntime.onStoreToMacroStorage(storageId);
        return changed;
    }

    public static int setFromStorage(
            Identifier targetStorageId,
            NbtPathArgumentType.NbtPath targetPath,
            Identifier sourceStorageId,
            NbtPathArgumentType.NbtPath sourcePath
    ) throws CommandSyntaxException {
        Collection<NbtElement> values = sourcePath.get(Mercury.SERVER.getDataCommandStorage().get(sourceStorageId));
        if (values.isEmpty()) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        NbtCompound root = getMutable(targetStorageId);
        int changed = targetPath.put(root, Iterables.getLast(values).copy());
        if (changed == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        save(targetStorageId, root);
        MacroPrefetchRuntime.onStoreToMacroStorage(targetStorageId);
        return changed;
    }

    public static int mergeValue(Identifier storageId, NbtPathArgumentType.NbtPath targetPath, NbtElement value) throws CommandSyntaxException {
        if (!(value instanceof NbtCompound compound)) {
            throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(value);
        }
        return mergeElements(storageId, targetPath, List.of(compound));
    }

    public static int mergeFromStorage(
            Identifier targetStorageId,
            NbtPathArgumentType.NbtPath targetPath,
            Identifier sourceStorageId,
            NbtPathArgumentType.NbtPath sourcePath
    ) throws CommandSyntaxException {
        Collection<NbtElement> values = sourcePath.get(Mercury.SERVER.getDataCommandStorage().get(sourceStorageId));
        return mergeElements(targetStorageId, targetPath, values);
    }

    public static void writeNumericValue(
            Identifier storageId,
            NbtPathArgumentType.NbtPath path,
            String numericType,
            double scale,
            int value
    ) throws CommandSyntaxException {
        NbtCompound root = getMutable(storageId);
        path.put(root, wrapNumber(value, numericType, scale));
        save(storageId, root);
        MacroPrefetchRuntime.onStoreToMacroStorage(storageId);
    }

    private static int mergeElements(Identifier storageId, NbtPathArgumentType.NbtPath targetPath, Collection<NbtElement> elements) throws CommandSyntaxException {
        NbtCompound merged = new NbtCompound();
        for (NbtElement element : elements) {
            if (!(element instanceof NbtCompound compound)) {
                throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(element);
            }
            merged.copyFrom(compound);
        }

        NbtCompound root = getMutable(storageId);
        Collection<NbtElement> targets = targetPath.getOrInit(root, NbtCompound::new);
        int changed = 0;
        for (NbtElement target : targets) {
            if (!(target instanceof NbtCompound compoundTarget)) {
                throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(target);
            }
            NbtCompound copy = compoundTarget.copy();
            compoundTarget.copyFrom(merged);
            changed += copy.equals(compoundTarget) ? 0 : 1;
        }

        if (changed == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        save(storageId, root);
        MacroPrefetchRuntime.onStoreToMacroStorage(storageId);
        return changed;
    }

    private static NbtCompound getMutable(Identifier storageId) {
        // Vanilla DataCommand mutates the storage NbtCompound in place: get the live reference,
        // mutate, then set marks dirty for persistence. Cloning on every write is O(root size)
        // and turns std:init_vm-style bulk writes into O(N^2) on heaps that grow to thousands of
        // entries — observed as multi-minute Server-thread stalls under JIT.
        return Mercury.SERVER.getDataCommandStorage().get(storageId);
    }

    private static void save(Identifier storageId, NbtCompound root) {
        Mercury.SERVER.getDataCommandStorage().set(storageId, root);
    }

    private static NbtElement wrapNumber(int value, String numericType, double scale) {
        double scaled = value * scale;
        return switch (numericType) {
            case "byte" -> NbtByte.of((byte) scaled);
            case "short" -> NbtShort.of((short) scaled);
            case "int" -> NbtInt.of((int) scaled);
            case "long" -> NbtLong.of((long) scaled);
            case "float" -> NbtFloat.of((float) scaled);
            case "double" -> NbtDouble.of(scaled);
            default -> NbtInt.of((int) scaled);
        };
    }
}
