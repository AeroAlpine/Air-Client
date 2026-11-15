package pingplus.voicechat.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SkyBlock mobs for the Combat target picker, by area. A target matches every mob whose name
 * contains it, so "Zealot" also hits "Special Zealot" and "Zombie" every zombie.
 */
final class SkyblockMobs {
    record Mob(String name, String area) {
    }

    static final List<Mob> ALL;

    static {
        List<Mob> mobs = new ArrayList<>();
        area(mobs, "Hub", "Zombie", "Zombie Villager", "Crypt Ghoul", "Golden Ghoul", "Wolf", "Old Wolf",
                "Sheep", "Cow", "Pig", "Chicken", "Rabbit", "Mushroom Cow");
        area(mobs, "The Park", "Howling Spirit", "Pack Spirit", "Soul of the Alpha");
        area(mobs, "Spider's Den", "Splitter Spider", "Weaver Spider", "Voracious Spider", "Dasher Spider",
                "Spider Jockey", "Cave Spider", "Silverfish", "Gravel Skeleton", "Rain Slime",
                "Arachne's Keeper", "Arachne's Brood", "Broodmother");
        area(mobs, "The End", "Enderman", "Zealot", "Special Zealot", "Endermite", "Nest Endermite",
                "Obsidian Defender", "Watcher", "Voidling Fanatic", "Voidling Extremist");
        area(mobs, "Crimson Isle", "Blaze", "Smoldering Blaze", "Millennia-Aged Blaze", "Mutated Blaze",
                "Magma Cube", "Ghast", "Wither Skeleton", "Wither Spectre", "Flaming Spider", "Kada Knight",
                "Mushroom Bull", "Barbarian", "Goliath Barbarian", "Krondor Necromancer", "Fire Mage",
                "Vanquisher", "Cinderbat", "Hellwisp", "Flare", "Bezal", "Matcho");
        area(mobs, "Deep Caverns", "Lapis Zombie", "Redstone Pigman", "Emerald Slime", "Diamond Zombie",
                "Diamond Skeleton", "Sneaky Creeper");
        area(mobs, "Dwarven Mines", "Goblin", "Weakling", "Knifethrower", "Fireslinger", "Golden Goblin",
                "Ice Walker", "Treasure Hoarder", "Star Sentry", "Ghost", "Powder Ghast");
        area(mobs, "Crystal Hollows", "Automaton", "Sludge", "Yog", "Thyst", "Team Treasurite",
                "Butterfly", "Worm", "Scatha", "Bal", "Key Guardian");
        area(mobs, "Glacite Tunnels", "Glacite Walker", "Glacite Bowman", "Glacite Caver", "Glacite Mage",
                "Glacite Mutt");
        area(mobs, "Garden", "Fly", "Mite", "Beetle", "Cricket", "Moth", "Locust", "Rat", "Slug",
                "Earthworm", "Mosquito", "Field Mouse");
        area(mobs, "Slayer bosses", "Revenant Horror", "Atoned Horror", "Tarantula Broodfather",
                "Sven Packmaster", "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend");
        area(mobs, "Slayer minibosses", "Revenant Sycophant", "Revenant Champion", "Deformed Revenant",
                "Atoned Champion", "Atoned Revenant", "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula",
                "Pack Enforcer", "Sven Follower", "Sven Alpha", "Voidling Devotee", "Voidling Radical",
                "Voidcrazed Maniac", "Flare Demon", "Kindleheart Demon", "Burningsoul Demon");
        area(mobs, "Dungeons", "Skeleton Master", "Crypt Lurker", "Crypt Dreadlord", "Crypt Souleater",
                "Lost Adventurer", "Shadow Assassin", "Angry Archaeologist", "Withermancer", "Zombie Commander",
                "Super Archer", "Fels", "Bat");
        ALL = Collections.unmodifiableList(mobs);
    }

    private static void area(List<Mob> into, String area, String... names) {
        for (String name : names) {
            into.add(new Mob(name, area));
        }
    }

    /** Front of a mob's head on its vanilla skin: texture, region and texture size. */
    record Face(net.minecraft.resources.Identifier texture, int u, int v, int w, int h, int texW, int texH,
                net.minecraft.resources.Identifier overlay) {
    }

    private static Face face(String file, int u, int v, int w, int h, int texW, int texH) {
        return face(file, u, v, w, h, texW, texH, null);
    }

    private static Face face(String file, int u, int v, int w, int h, int texW, int texH, String overlay) {
        return new Face(net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/" + file),
                u, v, w, h, texW, texH,
                overlay == null ? null : net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/" + overlay));
    }

    private static final Face ZOMBIE = face("zombie/zombie.png", 8, 8, 8, 8, 64, 64);
    private static final Face ZOMBIE_VILLAGER = face("zombie_villager/zombie_villager.png", 8, 8, 8, 10, 64, 64);
    private static final Face SKELETON = face("skeleton/skeleton.png", 8, 8, 8, 8, 64, 32);
    private static final Face WITHER_SKELETON = face("skeleton/wither_skeleton.png", 8, 8, 8, 8, 64, 32);
    private static final Face SPIDER = face("spider/spider.png", 40, 12, 8, 8, 64, 32);
    private static final Face CAVE_SPIDER = face("spider/cave_spider.png", 40, 12, 8, 8, 64, 32);
    private static final Face ENDERMAN = face("enderman/enderman.png", 8, 8, 8, 8, 64, 32, "enderman/enderman_eyes.png");
    private static final Face BLAZE = face("blaze/blaze.png", 8, 8, 8, 8, 64, 32);
    private static final Face CREEPER = face("creeper/creeper.png", 8, 8, 8, 8, 64, 32);
    private static final Face WOLF = face("wolf/wolf.png", 4, 4, 6, 6, 64, 32);
    private static final Face SLIME = face("slime/slime.png", 8, 8, 8, 8, 64, 32);
    private static final Face MAGMA = face("slime/magmacube.png", 8, 20, 8, 8, 64, 64);
    private static final Face GHAST = face("ghast/ghast.png", 32, 32, 32, 32, 128, 64);
    private static final Face SILVERFISH = face("silverfish/silverfish.png", 2, 2, 3, 2, 64, 32);
    private static final Face ENDERMITE = face("endermite/endermite.png", 2, 2, 4, 3, 64, 32);
    private static final Face PIG = face("pig/pig_temperate.png", 8, 8, 8, 8, 64, 64);
    private static final Face COW = face("cow/cow_temperate.png", 6, 6, 8, 8, 64, 64);
    private static final Face MOOSHROOM = face("cow/mooshroom_red.png", 6, 6, 8, 8, 64, 64);
    private static final Face SHEEP = face("sheep/sheep.png", 8, 8, 6, 6, 64, 32);
    private static final Face CHICKEN = face("chicken/chicken_temperate.png", 3, 3, 4, 6, 64, 32);
    private static final Face PIGLIN_BRUTE = face("piglin/piglin_brute.png", 8, 8, 10, 8, 64, 64);
    private static final Face ZOMBIFIED_PIGLIN = face("piglin/zombified_piglin.png", 8, 8, 10, 8, 64, 64);
    private static final Face IRON_GOLEM = face("iron_golem/iron_golem.png", 8, 8, 8, 10, 128, 128);
    private static final Face WITCH = face("witch/witch.png", 8, 8, 8, 10, 64, 128);
    private static final Face BEE = face("bee/bee.png", 10, 10, 7, 7, 64, 64);
    private static final Face STEVE = face("player/wide/steve.png", 8, 8, 8, 8, 64, 64);

    /** Keyword (whole words of the mob name) -> head; first match wins. */
    private static final List<Map.Entry<String, Face>> RULES = List.of(
            Map.entry("zombie villager", ZOMBIE_VILLAGER), Map.entry("pigman", ZOMBIFIED_PIGLIN),
            Map.entry("wither", WITHER_SKELETON), Map.entry("withermancer", WITHER_SKELETON),
            Map.entry("obsidian defender", WITHER_SKELETON), Map.entry("vanquisher", WITHER_SKELETON),
            Map.entry("kada knight", WITHER_SKELETON),
            Map.entry("skeleton", SKELETON), Map.entry("archer", SKELETON),
            Map.entry("zombie", ZOMBIE), Map.entry("ghoul", ZOMBIE), Map.entry("revenant", ZOMBIE),
            Map.entry("atoned", ZOMBIE), Map.entry("crypt", ZOMBIE), Map.entry("commander", ZOMBIE),
            Map.entry("cave spider", CAVE_SPIDER),
            Map.entry("spider", SPIDER), Map.entry("arachne's", SPIDER), Map.entry("broodmother", SPIDER),
            Map.entry("tarantula", SPIDER), Map.entry("broodfather", SPIDER),
            Map.entry("endermite", ENDERMITE), Map.entry("thyst", ENDERMITE),
            Map.entry("enderman", ENDERMAN), Map.entry("zealot", ENDERMAN), Map.entry("voidling", ENDERMAN),
            Map.entry("voidgloom", ENDERMAN), Map.entry("voidcrazed", ENDERMAN), Map.entry("watcher", ENDERMAN),
            Map.entry("fels", ENDERMAN),
            Map.entry("blaze", BLAZE), Map.entry("demon", BLAZE), Map.entry("demonlord", BLAZE),
            Map.entry("flare", BLAZE), Map.entry("hellwisp", BLAZE), Map.entry("bezal", BLAZE),
            Map.entry("cinderbat", BLAZE),
            Map.entry("magma", MAGMA), Map.entry("yog", MAGMA), Map.entry("bal", MAGMA), Map.entry("matcho", MAGMA),
            Map.entry("ghast", GHAST), Map.entry("slime", SLIME), Map.entry("sludge", SLIME),
            Map.entry("creeper", CREEPER), Map.entry("ghost", CREEPER),
            Map.entry("wolf", WOLF), Map.entry("spirit", WOLF), Map.entry("alpha", WOLF), Map.entry("sven", WOLF),
            Map.entry("pack enforcer", WOLF),
            Map.entry("silverfish", SILVERFISH), Map.entry("worm", SILVERFISH), Map.entry("earthworm", SILVERFISH),
            Map.entry("scatha", SILVERFISH), Map.entry("mite", SILVERFISH), Map.entry("rat", SILVERFISH),
            Map.entry("slug", SILVERFISH), Map.entry("mouse", SILVERFISH),
            Map.entry("fly", BEE), Map.entry("moth", BEE), Map.entry("mosquito", BEE), Map.entry("locust", BEE),
            Map.entry("cricket", BEE), Map.entry("beetle", BEE), Map.entry("butterfly", BEE),
            Map.entry("mushroom cow", MOOSHROOM), Map.entry("mushroom bull", MOOSHROOM),
            Map.entry("pig", PIG), Map.entry("cow", COW), Map.entry("sheep", SHEEP), Map.entry("chicken", CHICKEN),
            Map.entry("barbarian", PIGLIN_BRUTE),
            Map.entry("automaton", IRON_GOLEM), Map.entry("golem", IRON_GOLEM),
            Map.entry("fire mage", WITCH), Map.entry("necromancer", WITCH), Map.entry("witch", WITCH));

    /** Head shown next to a mob in the target picker (Steve for players and unknown names). */
    static Face face(String mob) {
        String name = " " + (mob == null ? "" : mob.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z' ]", " ")) + " ";
        for (Map.Entry<String, Face> rule : RULES) {
            if (name.contains(" " + rule.getKey() + " ")) {
                return rule.getValue();
            }
        }
        return STEVE;
    }

    private SkyblockMobs() {
    }
}
