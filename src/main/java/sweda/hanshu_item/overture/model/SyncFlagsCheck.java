package sweda.hanshu_item.overture.model;
import java.util.List;
import sweda.hanshu_item.overture.runtime.NativeNbtSync;

/** 原生 NBT 的同步规则自检。 */
public final class SyncFlagsCheck {
    public static void main(String[] args) {
        String parent = NativeNbtSync.normalizePath("nbt.Gun");
        String child = NativeNbtSync.normalizePath("nbt.Gun.AmmoCount");
        SyncFlags flags = SyncFlags.none().withSubtreeParticipates(parent, false);
        check(!flags.participates(child), "父节点排除覆盖子节点");
        check(flags.withSubtreeParticipates(parent, true).participates(child), "开启父节点清理子节点排除");
        check(flags.equals(SyncFlags.of(flags.toList())), "序列化保留规则");
        check(SyncFlags.none().participates(child), "默认参与同步");
        check(SyncFlags.of(List.of(child)).hasExcludedUnder(parent), "显示部分参与");
        System.out.println("Native NBT sync flag checks passed.");
    }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}