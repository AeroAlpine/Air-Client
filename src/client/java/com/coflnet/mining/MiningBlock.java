package com.coflnet.mining;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What a block is worth to a miner in the Dwarven Mines (and the gemstone caves below).
 * Strength values are SkyBlock's block strengths: break ticks = strength * 30 / mining speed,
 * at least 4 ticks unless the speed is high enough to instamine.
 */
public enum MiningBlock {
    GRAY_MITHRIL("Gray Mithril", Group.MITHRIL, 500, 0xFF9AA4AE, Items.WOOL.gray()),
    GREEN_MITHRIL("Green Mithril", Group.MITHRIL, 800, 0xFF5FD3B5, Items.PRISMARINE),
    BLUE_MITHRIL("Blue Mithril", Group.MITHRIL, 1500, 0xFF66C7FF, Items.WOOL.lightBlue()),
    TITANIUM("Titanium", Group.TITANIUM, 2000, 0xFFE8E8F0, Items.POLISHED_DIORITE),
    STONE("Stone", Group.STONE, 15, 0xFF8C8C8C, Items.STONE),
    COBBLESTONE("Cobblestone", Group.STONE, 20, 0xFF7A7A7A, Items.COBBLESTONE),
    COAL("Coal", Group.ORE, 30, 0xFF404040, Items.COAL),
    IRON("Iron", Group.ORE, 30, 0xFFD8AF93, Items.IRON_INGOT),
    GOLD("Gold", Group.ORE, 30, 0xFFF5D547, Items.GOLD_INGOT),
    LAPIS("Lapis", Group.ORE, 30, 0xFF3457D5, Items.LAPIS_LAZULI),
    REDSTONE("Redstone", Group.ORE, 30, 0xFFE03C3C, Items.REDSTONE),
    EMERALD("Emerald", Group.ORE, 30, 0xFF3CD26B, Items.EMERALD),
    DIAMOND("Diamond", Group.ORE, 30, 0xFF5CE6E0, Items.DIAMOND),
    PURE_COAL("Coal Block", Group.PURE, 600, 0xFF404040, Items.COAL_BLOCK),
    PURE_IRON("Iron Block", Group.PURE, 600, 0xFFD8D8D8, Items.IRON_BLOCK),
    PURE_GOLD("Gold Block", Group.PURE, 600, 0xFFF5D547, Items.GOLD_BLOCK),
    PURE_LAPIS("Lapis Block", Group.PURE, 600, 0xFF3457D5, Items.LAPIS_BLOCK),
    PURE_REDSTONE("Redstone Block", Group.PURE, 600, 0xFFE03C3C, Items.REDSTONE_BLOCK),
    PURE_EMERALD("Emerald Block", Group.PURE, 600, 0xFF3CD26B, Items.EMERALD_BLOCK),
    PURE_DIAMOND("Diamond Block", Group.PURE, 600, 0xFF5CE6E0, Items.DIAMOND_BLOCK),
    PURE_QUARTZ("Quartz Block", Group.PURE, 600, 0xFFEDE7DF, Items.QUARTZ_BLOCK),
    RUBY("Ruby", Group.GEMSTONE, 2300, 0xFFE0405A, Items.STAINED_GLASS.red()),
    AMBER("Amber", Group.GEMSTONE, 3000, 0xFFF39C35, Items.STAINED_GLASS.orange()),
    AMETHYST("Amethyst", Group.GEMSTONE, 3000, 0xFFB061E8, Items.STAINED_GLASS.purple()),
    JADE("Jade", Group.GEMSTONE, 3000, 0xFF7BE05A, Items.STAINED_GLASS.lime()),
    SAPPHIRE("Sapphire", Group.GEMSTONE, 3000, 0xFF5AB8F0, Items.STAINED_GLASS.lightBlue()),
    TOPAZ("Topaz", Group.GEMSTONE, 3800, 0xFFF0E04A, Items.STAINED_GLASS.yellow()),
    JASPER("Jasper", Group.GEMSTONE, 4800, 0xFFE05AC8, Items.STAINED_GLASS.magenta()),
    OPAL("Opal", Group.GEMSTONE, 3000, 0xFFF4F4F4, Items.STAINED_GLASS.white());

    public enum Group { MITHRIL, TITANIUM, STONE, ORE, PURE, GEMSTONE }

    public final String label;
    public final Group group;
    public final int strength;
    public final int color;
    public final Item icon;

    MiningBlock(String label, Group group, int strength, int color, Item icon) {
        this.label = label;
        this.group = group;
        this.strength = strength;
        this.color = color;
        this.icon = icon;
    }

    /** Break time in ticks at the given mining speed (0 = unknown speed). */
    public int ticks(double miningSpeed) {
        if (miningSpeed <= 0) return -1;
        int instant = strength * (group == Group.STONE ? 30 : 60);
        if (miningSpeed >= instant) return 1;
        return Math.max(4, (int) Math.round(strength * 30.0 / miningSpeed));
    }

    /** Mining speed needed to break it one tick faster (0 when already at the 4-tick floor). */
    public double speedForNextTick(double miningSpeed) {
        int now = ticks(miningSpeed);
        if (now <= 4) return 0;
        return Math.ceil(strength * 30.0 / (now - 1 - 0.5)) - miningSpeed;
    }

    private static final Map<Block, MiningBlock> BY_BLOCK = new IdentityHashMap<>();

    static {
        put(GRAY_MITHRIL, Blocks.WOOL.gray(), Blocks.DYED_TERRACOTTA.cyan());
        put(GREEN_MITHRIL, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);
        put(BLUE_MITHRIL, Blocks.WOOL.lightBlue());
        put(TITANIUM, Blocks.POLISHED_DIORITE);
        put(STONE, Blocks.STONE);
        put(COBBLESTONE, Blocks.COBBLESTONE);
        put(COAL, Blocks.COAL_ORE);
        put(IRON, Blocks.IRON_ORE);
        put(GOLD, Blocks.GOLD_ORE);
        put(LAPIS, Blocks.LAPIS_ORE);
        put(REDSTONE, Blocks.REDSTONE_ORE);
        put(EMERALD, Blocks.EMERALD_ORE);
        put(DIAMOND, Blocks.DIAMOND_ORE);
        put(PURE_COAL, Blocks.COAL_BLOCK);
        put(PURE_IRON, Blocks.IRON_BLOCK);
        put(PURE_GOLD, Blocks.GOLD_BLOCK);
        put(PURE_LAPIS, Blocks.LAPIS_BLOCK);
        put(PURE_REDSTONE, Blocks.REDSTONE_BLOCK);
        put(PURE_EMERALD, Blocks.EMERALD_BLOCK);
        put(PURE_DIAMOND, Blocks.DIAMOND_BLOCK);
        put(PURE_QUARTZ, Blocks.QUARTZ_BLOCK);
    }

    private static void put(MiningBlock kind, Block... blocks) {
        for (Block block : blocks) BY_BLOCK.put(block, kind);
    }

    /** The kind of this block, or null when it is nothing a miner cares about. */
    public static MiningBlock of(BlockState state) {
        if (state == null) return null;
        Block block = state.getBlock();
        MiningBlock kind = BY_BLOCK.get(block);
        if (kind != null) return kind;
        DyeColor color = null;
        if (block instanceof StainedGlassBlock glass) color = glass.getColor();
        else if (block instanceof StainedGlassPaneBlock pane) color = pane.getColor();
        if (color == null) return null;
        return switch (color) {
            case RED -> RUBY;
            case ORANGE -> AMBER;
            case PURPLE -> AMETHYST;
            case LIME -> JADE;
            case LIGHT_BLUE -> SAPPHIRE;
            case YELLOW -> TOPAZ;
            case MAGENTA -> JASPER;
            case WHITE -> OPAL;
            default -> null;
        };
    }

    public boolean isMithril() {
        return group == Group.MITHRIL;
    }
}
