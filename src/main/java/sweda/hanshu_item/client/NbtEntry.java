package sweda.hanshu_item.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.overture.runtime.ItemDataAccessor;

import java.util.ArrayList;
import java.util.List;

/** 原生数据组件树的节点。同步路径使用 components 前缀，键名和列表下标遵循 NBT 路径语义。 */
public final class NbtEntry {

    /** 原生数据组件树的路径前缀。 */
    public static final String SYNC_PREFIX = "components";

    private final NbtEntry parent;
    private Tag tag;

    /** 复合子节点，键名可变。 */
    private final List<Child> children = new ArrayList<>();

    /** 列表子节点的下标（复合节点为 {@code null}）。 */
    private final int listIndex;

    private String key;
    private String cachedPath;
    private boolean expanded = true;

    /** 复合节点的一个子项：名字 + 值。 */
    private record Child(String key, NbtEntry entry) {
    }

    private NbtEntry(@Nullable NbtEntry parent, @Nullable String key, int listIndex, Tag tag) {
        this.parent = parent;
        this.key = key;
        this.listIndex = listIndex;
        this.tag = tag;
    }

    /** 由物品的原始 NBT 建树。传入的 tag 会被直接引用（编辑即改原对象）。 */
    public static NbtEntry root(CompoundTag tag) {
        NbtEntry node = new NbtEntry(null, null, -1, tag);
        node.rebuildChildren();
        return node;
    }

    /** 子节点结构变化后重建（增删键、增删列表项之后调用）。 */
    public void rebuildChildren() {
        // 结构编辑后保留原来的展开状态。旧实现每次都新建子节点，
        // 导致用户展开深层 NBT 后新增/删除一项，整棵树突然折叠。
        java.util.Map<String, NbtEntry> oldChildren = new java.util.LinkedHashMap<>();
        for (Child old : children) {
            String oldKey = old.entry().isListElement()
                    ? Integer.toString(old.entry().listIndex()) : old.entry().key();
            if (oldKey != null) {
                oldChildren.put(oldKey, old.entry());
            }
        }
        children.clear();
        if (tag instanceof CompoundTag compound) {
            for (String childKey : compound.keySet()) {
                Tag childTag = compound.get(childKey);
                if (childTag != null) {
                    NbtEntry child = oldChildren.get(childKey);
                    if (child == null || child.tag() != childTag) {
                        child = new NbtEntry(this, childKey, -1, childTag);
                    } else {
                        child.key = childKey;
                    }
                    child.rebuildChildren();
                    if (!oldChildren.containsKey(childKey)) {
                        child.expanded = NbtValues.isContainer(childTag) ? false : true;
                    }
                    children.add(new Child(childKey, child));
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                Tag childTag = list.get(i);
                if (childTag != null) {
                    String indexKey = Integer.toString(i);
                    NbtEntry child = oldChildren.get(indexKey);
                    if (child == null || child.tag() != childTag) {
                        child = new NbtEntry(this, null, i, childTag);
                    }
                    child.rebuildChildren();
                    if (!oldChildren.containsKey(indexKey)) {
                        child.expanded = NbtValues.isContainer(childTag) ? false : true;
                    }
                    children.add(new Child(null, child));
                }
            }
        }
        cachedPath = null;
    }


    // --- 基本信息 -----------------------------------------------------------

    public Tag tag() {
        return tag;
    }

    public @Nullable NbtEntry parent() {
        return parent;
    }

    /** 复合节点下的键名；列表项为 {@code null}。 */
    public @Nullable String key() {
        return key;
    }

    /** 列表项下标；复合节点为 {@code -1}。 */
    public int listIndex() {
        return listIndex;
    }

    public boolean isRoot() {
        return parent == null;
    }

    /** 是否是列表里的一项（键名不可改，只能改值）。 */
    public boolean isListElement() {
        return listIndex >= 0;
    }

    public List<NbtEntry> children() {
        return children.stream().map(Child::entry).toList();
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public boolean expanded() {
        return expanded;
    }

    public void setExpanded(boolean value) {
        this.expanded = value;
    }

    public void toggleExpanded() {
        this.expanded = !expanded;
    }

    /** 深度（根为 0），决定缩进层级。 */
    public int depth() {
        int depth = 0;
        NbtEntry cursor = parent;
        while (cursor != null) {
            depth++;
            cursor = cursor.parent;
        }
        return depth;
    }

    /**
     * 定义侧路径，例如 {@code components."minecraft:custom_data".AmmoCount}。
     *
     * <p>现算而不是构造时缓存：键名可以被重命名，缓存会让路径与现实脱节。
     * 结果本身缓存在 {@link #cachedPath}，重命名时清掉。
     */
    public String path() {
        if (cachedPath != null) {
            return cachedPath;
        }
        if (parent == null) {
            return "";
        }
        String parentPath = parent.path();
        String segment = sweda.hanshu_item.overture.runtime.NativeNbtSync.quote(key == null ? "" : key);
        cachedPath = isListElement() ? parentPath + "[" + listIndex + "]"
                : parentPath.isEmpty() ? segment : parentPath + "." + segment;
        return cachedPath;
    }

    /** 是否有「参与同步」勾选框 —— 所有原生组件子树均可设置。 */
    public boolean syncToggleable() {
        if (isRoot()) {
            return false;
        }
        String path = path();
        return path.startsWith(SYNC_PREFIX + ".");
    }

    /**
     * 是否是本模组<b>自己维护</b>的身份字段（{@code id} / {@code def} / {@code rev}）。
     *
     * <p>这三个键不该被手动编辑，所以编辑器里把它们显示成只读文本：
     * <ul>
     *   <li>{@code id} 是物品与定义的唯一联系 —— 改错了物品就「脱离管理」，
     *       既不会同步、也不会报错，只是从此不再跟着定义走；</li>
     *   <li>{@code def} / {@code rev} 是签名缓存，手改只会让同步判定错乱
     *       （要么反复重建，要么该更新时不更新）。</li>
     * </ul>
     * 签名本来就是「算出来的」，让用户手填没有任何正当用途。
     */
    public boolean managementField() {
        return !isRoot() && !path().equals(SYNC_PREFIX) && !path().startsWith(SYNC_PREFIX + ".");
    }

    public @Nullable String syncRelativePath() {
        return syncToggleable() ? path() : null;
    }
    // --- 编辑 ---------------------------------------------------------------

    /**
     * 改键名。列表项与根不可改名。
     *
     * <p>复合节点改名要同步到父节点：把旧键删掉、用新键放回同一个 tag 对象，
     * 这样子树的引用不会断（列表项持有的是对象引用，不是键名）。
     *
     * @return 是否成功（非法名、重名、空名都算失败）
     */
    public boolean rename(String newKey) {
        if (isRoot() || isListElement() || parent == null) {
            return false;
        }
        String trimmed = newKey == null ? "" : newKey.trim();
        if (trimmed.isEmpty() || trimmed.equals(key) || !parent.tag().asCompound().isPresent()) {
            return false;
        }
        CompoundTag parentTag = (CompoundTag) parent.tag();
        if (parentTag.contains(trimmed)) {
            return false;
        }
        parentTag.remove(key);
        parentTag.put(trimmed, tag);
        // 父节点里那个 Child 的键名也要跟着改，否则重建子节点时又变回旧名
        parent.renameChild(this, key, trimmed);
        this.key = trimmed;
        this.cachedPath = null;
        parent.invalidatePathCacheDownwards();
        return true;
    }

    private void renameChild(NbtEntry child, String oldKey, String newKey) {
        for (int i = 0; i < children.size(); i++) {
            Child existing = children.get(i);
            if (existing.entry() == child) {
                children.set(i, new Child(newKey, child));
                return;
            }
        }
    }

    private void invalidatePathCacheDownwards() {
        cachedPath = null;
        for (Child child : children) {
            child.entry().invalidatePathCacheDownwards();
        }
    }

    /**
     * 改值。
     *
     * <p>对于复合与列表节点，这里改的是「容器本身」—— 也就是往里面加一项/删一项，
     * 所以调用方要负责传对类型。文本编辑只对叶节点有意义。
     *
     * @return 是否成功
     */
    public boolean setValue(Tag newTag) {
        if (newTag == null || parent == null) {
            return false;
        }
        if (isListElement()) {
            if (!(parent.tag() instanceof ListTag list)) {
                return false;
            }
            list.set(listIndex, newTag);
            this.tag = newTag;
            rebuildChildren();
            return true;
        }
        if (parent.tag() instanceof CompoundTag compound && key != null) {
            compound.put(key, newTag);
            this.tag = newTag;
            rebuildChildren();
            return true;
        }
        return false;
    }

    /** 叶节点：把输入文本解析成同类型标签并写回。 */
    public boolean setValueFromText(String text) {
        Tag parsed = NbtValues.parse(tag, text);
        if (parsed == null) {
            return false;
        }
        return setValue(parsed);
    }

    /** 删除本节点（根不可删）。 */
    public boolean removeSelf() {
        if (parent == null) {
            return false;
        }
        boolean removed;
        if (isListElement()) {
            if (parent.tag() instanceof ListTag list) {
                list.remove(listIndex);
                removed = true;
            } else {
                removed = false;
            }
        } else if (parent.tag() instanceof CompoundTag compound && key != null) {
            compound.remove(key);
            removed = true;
        } else {
            removed = false;
        }
        if (removed) {
            parent.rebuildChildren();
            // 列表删了一项之后，后面各项的下标都变了，必须整棵重建
            if (parent.tag() instanceof ListTag) {
                parent.rebuildChildren();
            }
        }
        return removed;
    }

    /** 往容器里加一项（复合加空字符串，列表加空复合）。 */
    public boolean addChild() {
        if (tag instanceof CompoundTag compound) {
            String newKey = NbtValues.uniqueKey(compound, "new_key");
            compound.putString(newKey, "");
            rebuildChildren();
            return true;
        }
        if (tag instanceof ListTag list) {
            list.addTag(list.size(), new CompoundTag());
            rebuildChildren();
            return true;
        }
        return false;
    }

    /** 把本节点的路径与值拼成一行描述，供状态行显示。 */
    public String describe() {
        return path() + " = " + NbtValues.describe(tag);
    }

    /** 展平成一串可见行（尊重展开状态），供列表渲染。 */
    public void flattenInto(List<NbtEntry> out) {
        out.add(this);
        if (!expanded) {
            return;
        }
        for (Child child : children) {
            child.entry().flattenInto(out);
        }
    }

    /** 搜索用的完整展平，不受用户当前折叠状态影响。 */
    public void flattenAllInto(List<NbtEntry> out) {
        out.add(this);
        for (Child child : children) {
            child.entry().flattenAllInto(out);
        }
    }
}
