package sweda.hanshu_item.client;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * NBT 标签的「显示成什么」「怎么从输入解析回来」。
 *
 * <p>抽成一个纯静态工具类，是为了让这部分能被单独核对 —— 它是最容易出错的一环：
 * 布尔在 NBT 里是 {@code byte}，输入 {@code true} 必须存回 {@code 1b} 而不是字符串；
 * 数值类型必须按原标签的类型还原，否则一个 {@code int} 被写成 {@code double}
 * 会让依赖它的原版逻辑（比如附魔等级）解析失败。
 *
 * <p>参照 IBE Editor 的做法：每个标签有<b>一个字母的类型徽章 + 一种颜色</b>，
 * 一眼能看出这行是什么类型，不用去数括号。
 */
public final class NbtValues {

    private NbtValues() {
    }

    /** 类型徽章颜色。跟 IBE Editor 的观感对齐：不同类型一眼可辨。 */
    public static int colorOf(Tag tag) {
        if (tag == null) {
            return EditorWidgets.COLOR_DIM;
        }
        return switch (tag.getId()) {
            case Tag.TAG_BYTE -> 0xFF6AC7FF;        // 布尔/字节：蓝
            case Tag.TAG_SHORT -> 0xFF7FD1A0;       // 短整：青绿
            case Tag.TAG_INT -> 0xFF8AB4F8;         // 整型：蓝紫
            case Tag.TAG_LONG -> 0xFFC79BFF;        // 长整：紫
            case Tag.TAG_FLOAT -> 0xFFFFC46B;       // 单精度：橙
            case Tag.TAG_DOUBLE -> 0xFFFFD479;      // 双精度：黄
            case Tag.TAG_STRING -> 0xFF9BE39B;      // 字符串：绿
            case Tag.TAG_LIST -> 0xFFFF9AA2;        // 列表：粉红
            case Tag.TAG_COMPOUND -> 0xFFFF8AD8;    // 复合：洋红
            case Tag.TAG_BYTE_ARRAY -> 0xFF8ED6D6;
            case Tag.TAG_INT_ARRAY -> 0xFF8ED6D6;
            case Tag.TAG_LONG_ARRAY -> 0xFF8ED6D6;
            default -> EditorWidgets.COLOR_DIM;
        };
    }

    /** 类型徽章上的文字。 */
    public static String badgeOf(Tag tag) {
        if (tag == null) {
            return "?";
        }
        return switch (tag.getId()) {
            case Tag.TAG_BYTE -> "B";
            case Tag.TAG_SHORT -> "S";
            case Tag.TAG_INT -> "I";
            case Tag.TAG_LONG -> "L";
            case Tag.TAG_FLOAT -> "F";
            case Tag.TAG_DOUBLE -> "D";
            case Tag.TAG_STRING -> "T";
            case Tag.TAG_LIST -> "[ ]";
            case Tag.TAG_COMPOUND -> "{ }";
            case Tag.TAG_BYTE_ARRAY -> "b[ ]";
            case Tag.TAG_INT_ARRAY -> "i[ ]";
            case Tag.TAG_LONG_ARRAY -> "l[ ]";
            default -> "?";
        };
    }

    /** 类型全名，给悬停提示与状态行用。 */
    public static String typeNameOf(Tag tag) {
        if (tag == null) {
            return "空";
        }
        return switch (tag.getId()) {
            case Tag.TAG_BYTE -> "字节/布尔";
            case Tag.TAG_SHORT -> "短整型";
            case Tag.TAG_INT -> "整型";
            case Tag.TAG_LONG -> "长整型";
            case Tag.TAG_FLOAT -> "单精度";
            case Tag.TAG_DOUBLE -> "双精度";
            case Tag.TAG_STRING -> "字符串";
            case Tag.TAG_LIST -> "列表";
            case Tag.TAG_COMPOUND -> "复合";
            case Tag.TAG_BYTE_ARRAY -> "字节数组";
            case Tag.TAG_INT_ARRAY -> "整型数组";
            case Tag.TAG_LONG_ARRAY -> "长整型数组";
            default -> "未知";
        };
    }

    /** 容器（可以展开）—— 复合、列表、各种数组。 */
    public static boolean isContainer(Tag tag) {
        return tag instanceof CompoundTag || tag instanceof ListTag
                || tag instanceof ByteArrayTag || tag instanceof IntArrayTag
                || tag instanceof LongArrayTag;
    }

    /** 可以就地编辑文本的标签（数字与字符串）。 */
    public static boolean isEditableLeaf(Tag tag) {
        return tag != null && !isContainer(tag);
    }

    /**
     * 显示成输入框里的文本。
     *
     * <p>刻意<b>不带引号</b>：输入框里 {@code 下界合金剑} 比 {@code "下界合金剑"} 好读也好改。
     * 解析回来时按原类型还原，所以去掉引号不会改变类型。
     */
    public static String displayValue(Tag tag) {
        if (tag == null) {
            return "";
        }
        if (tag instanceof CompoundTag compound) {
            return compound.isEmpty() ? "{}" : "{ " + compound.size() + " 项 }";
        }
        if (tag instanceof ListTag list) {
            return list.isEmpty() ? "[]" : "[ " + list.size() + " 项 ]";
        }
        if (tag instanceof ByteArrayTag bytes) {
            return "[" + bytes.getAsByteArray().length + " 字节]";
        }
        if (tag instanceof IntArrayTag ints) {
            return "[" + ints.getAsIntArray().length + " 整数]";
        }
        if (tag instanceof LongArrayTag longs) {
            return "[" + longs.getAsLongArray().length + " 长整数]";
        }
        // 数字与字符串：asString 给出的就是裸值
        return tag.asString().orElseGet(tag::toString);
    }

    /**
     * 把输入文本按<b>原标签的类型</b>解析回来。
     *
     * <p>为什么必须按原类型：NBT 是强类型的。一个附魔等级本来是 {@code 1b}（byte），
     * 如果因为用户改了数字就写成 {@code 1}（int），原版读的时候会取不到值 ——
     * 表现为「明明写着附魔，游戏里没效果」，很难查。
     *
     * @param original 原来的标签，用来决定目标类型；可传 {@code null} 表示新建，此时按文本猜
     * @return 解析结果；{@code null} 表示文本不合法
     */
    public static @Nullable Tag parse(@Nullable Tag original, String text) {
        String trimmed = text == null ? "" : text.trim();

        if (original == null) {
            return guess(trimmed);
        }
        try {
            return switch (original.getId()) {
                case Tag.TAG_BYTE -> ByteTag.valueOf(parseBoolean(trimmed) ? (byte) 1 : Byte.parseByte(trimmed));
                case Tag.TAG_SHORT -> ShortTag.valueOf(Short.parseShort(trimmed));
                case Tag.TAG_INT -> IntTag.valueOf(Integer.parseInt(trimmed));
                case Tag.TAG_LONG -> LongTag.valueOf(Long.parseLong(trimmed));
                case Tag.TAG_FLOAT -> FloatTag.valueOf(Float.parseFloat(trimmed));
                case Tag.TAG_DOUBLE -> DoubleTag.valueOf(Double.parseDouble(trimmed));
                case Tag.TAG_STRING -> StringTag.valueOf(text == null ? "" : text);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 新建条目时按文本猜类型 —— 只做最基本的判断，剩下的交给用户改类型。 */
    public static Tag guess(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return ByteTag.valueOf(true);
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return ByteTag.valueOf(false);
        }
        // 带小数点 → 双精度；否则先试整型，再退化成字符串
        if (trimmed.matches("[-+]?\\d+\\.\\d+([eE][-+]?\\d+)?")) {
            try {
                return DoubleTag.valueOf(Double.parseDouble(trimmed));
            } catch (NumberFormatException ignored) {
                // 落到字符串
            }
        }
        if (trimmed.matches("[-+]?\\d+")) {
            try {
                return IntTag.valueOf(Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // 超出 int 范围：试长整，再不行当字符串
                try {
                    return LongTag.valueOf(Long.parseLong(trimmed));
                } catch (NumberFormatException ignoredAgain) {
                    // 落到字符串
                }
            }
        }
        return StringTag.valueOf(trimmed);
    }

    private static boolean parseBoolean(String trimmed) {
        return trimmed.equalsIgnoreCase("true") || trimmed.equals("1");
    }

    /** 给新建的复合/列表条目起个不重名的键。 */
    public static String uniqueKey(CompoundTag parent, String base) {
        if (!parent.contains(base)) {
            return base;
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "_" + i;
            if (!parent.contains(candidate)) {
                return candidate;
            }
        }
        return base + "_" + System.nanoTime();
    }

    /** 数组的紧凑显示（列表展开时用）。 */
    public static String describeArray(Tag tag) {
        if (tag instanceof ByteArrayTag bytes) {
            return Arrays.toString(bytes.getAsByteArray());
        }
        if (tag instanceof IntArrayTag ints) {
            return Arrays.toString(ints.getAsIntArray());
        }
        if (tag instanceof LongArrayTag longs) {
            return Arrays.toString(longs.getAsLongArray());
        }
        return "";
    }

    /** 供状态行显示：类型 + 值的简短描述。 */
    public static String describe(Tag tag) {
        return String.format(Locale.ROOT, "%s %s", typeNameOf(tag), displayValue(tag));
    }
}
