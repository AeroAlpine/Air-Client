package pingplus.voicechat.client.gui;

import com.coflnet.ahflipper.AhBidMacro;
import com.coflnet.ahflipper.AhFireSaleBacktest;
import com.coflnet.ahflipper.AhFireSaleSkinScreen;
import com.coflnet.ahflipper.AhFireSaleSkinTracker;
import com.coflnet.ahflipper.AhFlipperMod;
import com.coflnet.ahflipper.AhInvestmentScreen;
import com.coflnet.ahflipper.AhInvestmentTracker;
import com.coflnet.flipper.FlipperConfig;
import com.coflnet.flipper.FlipperMod;
import com.coflnet.lowballing.LowballingMod;
import com.coflnet.session.BotSessions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import pingplus.voicechat.client.FollowFeature;
import pingplus.voicechat.client.PlayerSettings;
import pingplus.voicechat.client.VoicechatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Everything in the SkyBlock menu: modules (cards) by section, each with groups of settings.
 * Pure description, drawn by {@link SkyblockScreen}; the settings write straight into the
 * existing configs, so the Controller Center, profiles and hotkeys see the same values.
 */
final class SkyblockModules {
    enum Section {
        GENERAL("General"), SKILLS("Skills"), TRADING("Trading"), MINING("Mining"), DUNGEONS("Dungeons"), MISC("Misc");

        final String title;

        Section(String title) {
            this.title = title;
        }
    }

    record Module(String id, String name, String description, Section section, Supplier<ItemStack> icon,
                  BooleanSupplier enabled, Runnable toggle, Consumer<Builder> settings) {
        boolean toggleable() {
            return enabled != null && toggle != null;
        }

        List<Group> groups() {
            Builder builder = new Builder();
            settings.accept(builder);
            return builder.groups;
        }
    }

    record Group(String title, List<Setting> settings) {
    }

    sealed interface Setting permits Toggle, Slider, Choice, Picker, Action, Info, Key {
        String name();

        String description();
    }

    record Toggle(String name, String description, BooleanSupplier get, Runnable flip) implements Setting {
    }

    record Slider(String name, String description, double min, double max, double step, String unit,
                  DoubleSupplier get, DoubleConsumer set) implements Setting {
    }

    record Choice(String name, String description, List<String> options, Supplier<String> get,
                  Consumer<String> set) implements Setting {
    }

    /**
     * A list of names: picked from {@code options} (searchable dropdown) and/or typed. With no
     * options it is a plain "type and press Enter" list; {@code add == null} = remove only.
     */
    record Picker(String name, String description, Supplier<List<Option>> options, Supplier<List<String>> selected,
                  Function<String, Boolean> add, Function<String, Boolean> remove, Runnable save,
                  String hint, boolean heads) implements Setting {
        boolean hasOptions() {
            return options != null;
        }
    }

    record Option(String value, String detail) {
    }

    record Action(String name, String description, String button, Runnable run) implements Setting {
    }

    record Info(String name, String description, Supplier<String> text, IntSupplier color) implements Setting {
    }

    record Key(String name, String description, Supplier<KeyMapping> key) implements Setting {
    }

    static final class Builder {
        final List<Group> groups = new ArrayList<>();
        private List<Setting> current;

        Builder group(String title) {
            current = new ArrayList<>();
            groups.add(new Group(title, current));
            return this;
        }

        private Builder add(Setting setting) {
            if (current == null) {
                group("Settings");
            }
            current.add(setting);
            return this;
        }

        Builder toggle(String name, String description, BooleanSupplier get, Runnable flip) {
            return add(new Toggle(name, description, get, flip));
        }

        Builder slider(String name, String description, double min, double max, double step, String unit,
                       DoubleSupplier get, DoubleConsumer set) {
            return add(new Slider(name, description, min, max, step, unit, get, set));
        }

        Builder choice(String name, String description, List<String> options, Supplier<String> get, Consumer<String> set) {
            return add(new Choice(name, description, options, get, set));
        }

        Builder picker(String name, String description, Supplier<List<Option>> options, Supplier<List<String>> selected,
                       Function<String, Boolean> add, Function<String, Boolean> remove, Runnable save, String hint) {
            return add(new Picker(name, description, options, selected, add, remove, save, hint, false));
        }

        /** Like {@link #picker}, with the mob's head next to every entry. */
        Builder mobPicker(String name, String description, Supplier<List<Option>> options, Supplier<List<String>> selected,
                          Function<String, Boolean> add, Function<String, Boolean> remove, Runnable save, String hint) {
            return add(new Picker(name, description, options, selected, add, remove, save, hint, true));
        }

        Builder action(String name, String description, String button, Runnable run) {
            return add(new Action(name, description, button, run));
        }

        Builder info(String name, String description, Supplier<String> text, IntSupplier color) {
            return add(new Info(name, description, text, color));
        }

        Builder key(String name, String description, Supplier<KeyMapping> key) {
            return add(new Key(name, description, key));
        }

        /** Session length and break of a bot. */
        Builder session(BotSessions.Bot bot) {
            BotSessions.Settings st = BotSessions.settings(bot);
            group("Session");
            slider("Session length", "Stops the bot after this many minutes. 0 = no limit.", 0, 600, 5, "min",
                    () -> st.sessionMinutes, v -> {
                        st.sessionMinutes = (int) Math.round(v);
                        BotSessions.save();
                    });
            toggle("Take a break", "After a session, pause and then start again on its own.",
                    () -> st.useBreak, () -> {
                        st.useBreak = !st.useBreak;
                        BotSessions.save();
                    });
            slider("Break length", "How long the break between two sessions is.", 1, 240, 1, "min",
                    () -> st.breakMinutes, v -> {
                        st.breakMinutes = (int) Math.round(v);
                        BotSessions.save();
                    });
            return this;
        }

        /** The on-screen panel of a bot. Its look (glass, edges, footer) is under General > Panel Style. */
        Builder panel(String name, BooleanSupplier on, Runnable flipOn, IntSupplier rows,
                      java.util.function.IntConsumer setRows) {
            group("On-screen panel");
            toggle(name, "Shows the bot's live status on screen. Move it with Customize.", on, flipOn);
            slider("Rows", "How many entries the panel lists.", 1, 5, 1, "",
                    rows::getAsInt, v -> setRows.accept((int) Math.round(v)));
            return this;
        }

        /** Look of one panel. */
        Builder panelStyle(String title, BooleanSupplier glass, Runnable flipGlass, BooleanSupplier edges,
                           Runnable flipEdges, BooleanSupplier footer, Runnable flipFooter) {
            group(title);
            toggle("Glass", "Liquid-glass background instead of a plain dark one.", glass, flipGlass);
            toggle("Edges", "Light rim around the panel.", edges, flipEdges);
            toggle("Footer", "Totals line at the bottom of the panel.", footer, flipFooter);
            return this;
        }
    }

    static final List<Module> MODULES = List.of(
            experimentTable(), visuals(), itemValue(), panelStyle(),
            combat(),
            auctionFlipper(), autoBid(), bazaarFlipper(), lowballing(), investments(), bazaarFlips(), fireSale(),
            pathfinder(), slayer());

    static List<Module> in(Section section) {
        return MODULES.stream().filter(m -> m.section() == section).toList();
    }

    static Module byId(String id) {
        return MODULES.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    /** "All off" in the Controller Center: every SkyBlock module and every toggle in it. */
    static void allOff() {
        for (Module module : MODULES) {
            for (Group group : module.groups()) {
                for (Setting setting : group.settings()) {
                    if (setting instanceof Toggle toggle && toggle.get().getAsBoolean()) {
                        toggle.flip().run();
                    }
                }
            }
            if (module.toggleable() && module.enabled().getAsBoolean()) {
                module.toggle().run();
            }
        }
    }

    // ---------------------------------------------------------------- General

    private static Module experimentTable() {
        var ec = com.coflnet.experiments.AutoExperimentTable.CONFIG;
        return new Module("experiments", "Experiment Table",
                "Solves the Experimentation Table: Chronomatron, Ultrasequencer and Superpairs.",
                Section.GENERAL, () -> new ItemStack(Items.ENCHANTING_TABLE),
                com.coflnet.experiments.AutoExperimentTable::isEnabled,
                com.coflnet.experiments.AutoExperimentTable::toggle, b -> b
                .group("Solver")
                .slider("Click delay", "Pause between two clicks. Higher looks more human.", 0,
                        com.coflnet.experiments.ExperimentConfig.MAX_CLICK_DELAY_MS, 10, "ms",
                        () -> ec.clickDelayMs, v -> {
                            ec.clickDelayMs = (int) Math.round(v);
                            ec.save();
                        })
                .slider("Chronomatron rounds", "Rounds to play before stopping. 0 skips this game.", 0,
                        com.coflnet.experiments.ExperimentConfig.MAX_ROUNDS, 1, "",
                        () -> ec.chronomatronRounds, v -> {
                            ec.chronomatronRounds = (int) Math.round(v);
                            ec.save();
                        })
                .slider("Ultrasequencer rounds", "Rounds to play before stopping. 0 skips this game.", 0,
                        com.coflnet.experiments.ExperimentConfig.MAX_ROUNDS, 1, "",
                        () -> ec.ultrasequencerRounds, v -> {
                            ec.ultrasequencerRounds = (int) Math.round(v);
                            ec.save();
                        })
                .info("Superpairs", "Always played until you are out of clicks.", () -> "Automatic", () -> 0xFFD0D0D0));
    }

    private static Module visuals() {
        return new Module("visuals", "Visuals", "Cleaner screen and outlines: particles, mob names, glow.",
                Section.GENERAL, () -> new ItemStack(Items.ENDER_EYE), null, null, b -> b
                .group("Clean screen")
                .toggle("Hide particles", "Removes particles: better FPS and a clear view in fights.",
                        () -> PlayerSettings.hideParticles, () -> PlayerSettings.hideParticles = !PlayerSettings.hideParticles)
                .toggle("Hide mob names", "Hides the floating name and health tags of mobs.",
                        () -> PlayerSettings.hideMobNames, () -> PlayerSettings.hideMobNames = !PlayerSettings.hideMobNames)
                .toggle("Mob health bar", "Small glass health bar above mobs. Only while mob names are hidden.",
                        () -> PlayerSettings.mobHealthBar, () -> PlayerSettings.mobHealthBar = !PlayerSettings.mobHealthBar)
                .slider("Health bar opacity", "How see-through the mob health bars are.", 0, 100, 5, "%",
                        () -> PlayerSettings.mobBarOpacity * 100.0, v -> PlayerSettings.mobBarOpacity = (float) (v / 100.0))
                .toggle("Order & auction colors", "Your Bazaar orders: red = outbid, green = still the best price. "
                                + "Manage Auctions: red = no longer the lowest BIN, green = still lowest BIN.",
                        () -> PlayerSettings.orderColors, () -> PlayerSettings.orderColors = !PlayerSettings.orderColors)
                .group("Outlines")
                .toggle("Self glow", "Outline around your own player in third person.",
                        () -> PlayerSettings.selfOutline, () -> PlayerSettings.selfOutline = !PlayerSettings.selfOutline)
                .toggle("Player outline", "Outline around other players.",
                        () -> PlayerSettings.playerOutline, () -> PlayerSettings.playerOutline = !PlayerSettings.playerOutline)
                .group("Helpers")
                .toggle("Etherwarp helper", "Shows where an Etherwarp would land before you use it.",
                        () -> PlayerSettings.etherwarpHelper, () -> PlayerSettings.etherwarpHelper = !PlayerSettings.etherwarpHelper)
                .toggle("AGG", "Shows the AGG overlay.",
                        pingplus.voicechat.client.hud.AggSettings::isEnabled,
                        () -> pingplus.voicechat.client.hud.AggSettings.setEnabled(!pingplus.voicechat.client.hud.AggSettings.isEnabled())));
    }

    private static Module itemValue() {
        return new Module("tooltip", "Item Value", "Adds the estimated market value to item tooltips.",
                Section.GENERAL, () -> new ItemStack(Items.BOOK),
                AhFlipperMod::isTooltipValueOn, AhFlipperMod::toggleTooltipValue, b -> b
                .group("Tooltip")
                .toggle("Details", "Also lists what the value is made of (enchants, stars, gems ...).",
                        AhFlipperMod::isTooltipDetailsOn, AhFlipperMod::toggleTooltipDetails));
    }

    // ---------------------------------------------------------------- Skills

    private static Module combat() {
        var pf = com.coflnet.pathing.PathfinderConfig.get();
        return new Module("combat", "Combat",
                "Walks to the chosen mobs with the pathfinder, fights them and moves on to the next.",
                Section.SKILLS, () -> new ItemStack(Items.DIAMOND_SWORD),
                () -> PlayerSettings.combatEnabled, FollowFeature::toggle, b -> b
                .group("Combat macro")
                .key("Toggle key", "Starts and stops Combat. Click, then press a key (Esc clears it).",
                        FollowFeature::toggleKey)
                .mobPicker("Targets", "Mobs to fight. Pick them from the list (search by name or area) or type any "
                                + "mob, player or entity type and press Enter. A name also matches longer names.",
                        SkyblockModules::mobOptions, FollowFeature::targets,
                        FollowFeature::addTarget, FollowFeature::removeTarget, PlayerSettings::save,
                        "Search mobs or type a name")
                .toggle("Attack targets", "Hits the target. Off: only walks after it.",
                        () -> PlayerSettings.followAttack, () -> PlayerSettings.followAttack = !PlayerSettings.followAttack)
                .toggle("One-hit mode", "One click per mob, then straight to the next (already chosen) target "
                                + "without waiting for the kill. Good for weak mobs.",
                        () -> PlayerSettings.combatOneHit, () -> PlayerSettings.combatOneHit = !PlayerSettings.combatOneHit)
                .group("Movement")
                .slider("Follow distance", "How close it walks to the target before attacking.", 1, 16, 1, "blocks",
                        () -> PlayerSettings.followDistance, v -> PlayerSettings.followDistance = (int) Math.round(v))
                .slider("Rotation speed", "How fast the camera turns towards the target.", 30, 1080, 10, "°/s",
                        () -> PlayerSettings.combatRotationSpeed, v -> PlayerSettings.combatRotationSpeed = (float) v)
                .slider("Calm movement", "Higher = smoother, calmer camera while walking. Lower = snappier.",
                        0, 100, 5, "%", () -> pf.movementCalmness, v -> {
                            pf.movementCalmness = v;
                            pf.save();
                        })
                .toggle("Show path and target", "Draws the planned path on the floor and boxes around the target "
                                + "and the next two targets.",
                        () -> pf.showPathOverlay, () -> {
                            pf.showPathOverlay = !pf.showPathOverlay;
                            pf.save();
                        })
                .group("Clicking")
                .slider("Min CPS", "Slowest clicks per second (each click is random between min and max).",
                        1, 20, .5, "", () -> PlayerSettings.followCpsMin, v -> {
                            PlayerSettings.followCpsMin = v;
                            if (PlayerSettings.followCpsMax < v) PlayerSettings.followCpsMax = v;
                        })
                .slider("Max CPS", "Fastest clicks per second.", 1, 20, .5, "",
                        () -> PlayerSettings.followCpsMax, v -> {
                            PlayerSettings.followCpsMax = v;
                            if (PlayerSettings.followCpsMin > v) PlayerSettings.followCpsMin = v;
                        })
                .panel("Combat panel", () -> PlayerSettings.combatPanel,
                        () -> PlayerSettings.combatPanel = !PlayerSettings.combatPanel,
                        () -> PlayerSettings.combatPanelRows, v -> PlayerSettings.combatPanelRows = v));
    }

    private static List<Option> mobOptions() {
        List<Option> options = new ArrayList<>(SkyblockMobs.ALL.size());
        for (SkyblockMobs.Mob mob : SkyblockMobs.ALL) {
            options.add(new Option(mob.name(), mob.area()));
        }
        return options;
    }

    // ---------------------------------------------------------------- Trading

    private static Module auctionFlipper() {
        var c = AhFlipperMod.CONFIG;
        return new Module("ah", "Auction Flipper",
                "Buys underpriced BIN auctions and lists them again for profit.",
                Section.TRADING, () -> new ItemStack(Items.GOLD_INGOT),
                AhFlipperMod::isRunning, AhFlipperMod::toggle, b -> {
            b.group("Flipping")
                    .info("Speed", "Premium players flip at full speed, others with a short delay.",
                            () -> com.coflnet.ahflipper.PriorityAccess.isLocalPriority()
                                    ? "Premium - full speed" : "Standard - " + c.standardDelayMs + " ms delay",
                            () -> com.coflnet.ahflipper.PriorityAccess.isLocalPriority() ? 0xFFA4F0C1 : 0xFFD0D0D0)
                    .toggle("Auto-buy", "Buys flips on its own. Off: only shows them.",
                            AhFlipperMod::isAutoBuyOn, AhFlipperMod::toggleAutoBuy)
                    .slider("Min profit", "Only flips that make at least this much after tax.", 0, 100, 1, "%",
                            () -> c.minProfitPercent, v -> {
                                c.minProfitPercent = v;
                                c.save();
                            })
                    .slider("Max bankroll", "Never spends more than this on flips at once.", 0, 1000, 1, "M",
                            () -> c.maxBankrollCoins / 1_000_000.0, v -> {
                                c.maxBankrollCoins = Math.round(v * 1_000_000.0);
                                c.save();
                            })
                    .slider("LBin min count", "How many lowest-BIN listings must agree on the price.", 1, 5, 1, "",
                            () -> c.coflLbinMinCount, v -> {
                                c.coflLbinMinCount = (int) Math.round(v);
                                c.save();
                            })
                    .slider("LBin match", "How close those listings must be to each other.", 0.5, 5, 0.5, "%",
                            () -> c.coflLbinMatchPercent, v -> {
                                c.coflLbinMatchPercent = v;
                                c.save();
                            });
            b.session(BotSessions.Bot.AH);
            b.panel("AH flip panel", () -> c.ahPanelEnabled, () -> { c.ahPanelEnabled = !c.ahPanelEnabled; c.save(); },
                    () -> c.ahPanelRows, v -> { c.ahPanelRows = v; c.save(); });
            b.group("Blocked items")
                    .picker("Blocked items", "Never flipped. Type an item name and press Enter. "
                                    + "Tip: hover an item and press K to block or unblock it.",
                            null, () -> c.blacklist == null ? List.of() : List.copyOf(c.blacklist),
                            c::addBlocked, c::removeBlocked, c::save, "Item name")
                    .picker("Blocked by ID", "Blocked with K: every variant of the item. Click one to unblock it.",
                            null, () -> {
                                List<String> shown = new ArrayList<>();
                                for (Map.Entry<String, String> e : c.blacklistTags.entrySet()) {
                                    shown.add(e.getValue() + " (" + e.getKey() + ")");
                                }
                                return shown;
                            }, null, entry -> {
                                int open = entry.lastIndexOf(" (");
                                String tag = open < 0 ? entry : entry.substring(open + 2, entry.length() - 1);
                                return c.blacklistTags.remove(com.coflnet.ahflipper.AhFlipperConfig.baseTag(tag)) != null;
                            }, c::save, "");
        });
    }

    private static Module autoBid() {
        var c = AhFlipperMod.CONFIG;
        return new Module("bids", "Auto-Bid",
                "Bids on normal auctions that end soon and are worth more than the bid.",
                Section.TRADING, () -> new ItemStack(Items.NAME_TAG),
                AhBidMacro::isRunning, () -> {
            boolean enable = !AhBidMacro.isRunning();
            c.bidMacro = enable;
            c.save();
            if (enable) AhBidMacro.start(); else AhBidMacro.stop();
        }, b -> {
            b.group("Bidding")
                    .slider("Min profit", "Only bids where the expected profit is at least this.", 1, 50, 1, "%",
                            () -> c.bidMacroMinProfitPercent, v -> { c.bidMacroMinProfitPercent = v; c.save(); })
                    .slider("Min item value", "Ignores cheap auctions.", 1, 20, 1, "M",
                            () -> c.bidMacroMinValueCoins / 1_000_000.0,
                            v -> { c.bidMacroMinValueCoins = Math.round(v * 1_000_000.0); c.save(); })
                    .slider("Max time left", "Only auctions that end within this time (max 120).", 10, 120, 5, "min",
                            () -> c.bidMacroMaxRemainingMinutes,
                            v -> { c.bidMacroMaxRemainingMinutes = (int) Math.round(v); c.save(); })
                    .slider("Bid delay", "Pause before each bid.", 0, 1000, 10, "ms",
                            () -> c.bidMacroDelayMs, v -> { c.bidMacroDelayMs = (int) Math.round(v); c.save(); })
                    .slider("Bids per scan", "Most bids placed in one scan.", 1, 15, 1, "",
                            () -> c.bidMacroMaxBidsPerScan, v -> { c.bidMacroMaxBidsPerScan = (int) Math.round(v); c.save(); })
                    .slider("Candidates per scan", "Auctions checked closely per scan.", 1, 15, 1, "",
                            () -> c.bidMacroMaxCandidatesPerScan,
                            v -> { c.bidMacroMaxCandidatesPerScan = (int) Math.round(v); c.save(); })
                    .slider("Bankroll share", "Part of your purse that may be tied up in bids.", 10, 100, 5, "%",
                            () -> c.bidMacroMaxBankrollFraction * 100.0,
                            v -> { c.bidMacroMaxBankrollFraction = v / 100.0; c.save(); })
                    .toggle("Auto-list won items", "Lists won items on the AH right away.",
                            () -> c.bidMacroAutoList, () -> { c.bidMacroAutoList = !c.bidMacroAutoList; c.save(); });
            b.session(BotSessions.Bot.BID);
            b.panel("Bids panel", () -> c.bidsPanelEnabled, () -> { c.bidsPanelEnabled = !c.bidsPanelEnabled; c.save(); },
                    () -> c.bidsPanelRows, v -> { c.bidsPanelRows = v; c.save(); });
            b.group("Players")
                    .picker("Don't outbid", "Friends: their bids are never outbid. Type a name and press Enter.",
                            null, () -> c.dontOutbidPlayers == null ? List.of() : List.copyOf(c.dontOutbidPlayers),
                            c::addDontOutbid, c::removeDontOutbid, c::save, "Player name");
        });
    }

    private static Module bazaarFlipper() {
        FlipperConfig fc = FlipperMod.CONFIG;
        Runnable changed = () -> {
            fc.save();
            com.coflnet.flipper.analysis.BazaarFlipTracker.requestRefresh();
        };
        return new Module("bazaar", "Bazaar Flipper",
                "Places buy orders and sells with sell offers, always 0.1 better than the best price.",
                Section.TRADING, () -> new ItemStack(Items.EMERALD),
                FlipperMod::isRunning, FlipperMod::toggle, b -> {
            b.group("Item mode")
                    .toggle("Item mode", "Flips only the items below instead of the analyzer's picks. "
                                    + "Empty list: the analyzer picks.",
                            () -> fc.onlyItemsMode, () -> { fc.onlyItemsMode = !fc.onlyItemsMode; fc.save(); })
                    .picker("Items", "Pick Bazaar items, or type part of a name and press Enter "
                                    + "(\"recomb\" -> Recombobulator 3000).",
                            SkyblockModules::bazaarOptions,
                            () -> fc.onlyItems == null ? List.of() : List.copyOf(fc.onlyItems),
                            fc::addOnlyItem, fc::removeOnlyItem, fc::save, "Search Bazaar items");
            b.group("Limits")
                    .slider("Max invested", "Coins tied up in orders at most. 0 = whole purse.", 0, 2000, 10, "M",
                            () -> fc.maxInvestedMillions, v -> { fc.maxInvestedMillions = v; fc.save(); })
                    .slider("Max per session", "Stops placing offers after this much. 0 = no limit.", 0, 1000, 5, "M",
                            () -> fc.maxCoinsPerSessionMillions, v -> { fc.maxCoinsPerSessionMillions = v; fc.save(); })
                    .info("Used this session", "Sell offers placed in this session.",
                            () -> com.coflnet.flipper.trading.ScreenUtil.formatCoins(
                                    com.coflnet.flipper.trading.BazaarLimits.sessionSellVolume()), () -> 0xFFD0D0D0)
                    .slider("Max per item", "Most coins in one item. 0 = no limit.", 0, 1000, 5, "M",
                            () -> fc.maxCoinsPerItem / 1_000_000.0, v -> { fc.maxCoinsPerItem = v * 1_000_000.0; changed.run(); });
            b.group("Filters")
                    .info("Note", "Also used by the Bazaar Flips screen.", () -> "Shared", () -> 0xFFD0D0D0)
                    .slider("Min coins/hour", "Expected profit per hour an item must reach.", 0, 100, .5, "M",
                            () -> fc.minCoinsPerHour / 1_000_000.0, v -> { fc.minCoinsPerHour = Math.round(v * 1_000_000.0); changed.run(); })
                    .slider("Min margin", "Smallest gap between buy and sell price after tax.", 0, 20, .5, "%",
                            () -> fc.minMarginPercent, v -> { fc.minMarginPercent = v; changed.run(); })
                    .slider("Max margin", "Bigger gaps are usually fake prices or dead items.", 5, 100, 5, "%",
                            () -> fc.maxMarginPercent, v -> { fc.maxMarginPercent = v; changed.run(); })
                    .slider("Min weekly volume", "Items that sell less are too slow.", 0, 10000, 50, "",
                            () -> fc.minWeeklyVolume, v -> { fc.minWeeklyVolume = Math.round(v); changed.run(); })
                    .slider("Min unit price", "Skips cheap items where the 0.1 steps eat the profit.", 0, 10000, 10, "k",
                            () -> fc.minUnitCoins / 1000.0, v -> { fc.minUnitCoins = v * 1000.0; changed.run(); })
                    .slider("Min order", "Smallest order worth placing.", 0, 100, 1, "M",
                            () -> fc.minOrderCoins / 1_000_000.0, v -> { fc.minOrderCoins = v * 1_000_000.0; changed.run(); })
                    .slider("Max price swing", "Skips items whose price moved more than this lately.", 5, 100, 5, "%",
                            () -> fc.maxHistoricalDeviationPercent, v -> { fc.maxHistoricalDeviationPercent = v; changed.run(); })
                    .toggle("Need price history", "Only items with known past prices (safer).",
                            () -> fc.requireHistoricalPrice, () -> { fc.requireHistoricalPrice = !fc.requireHistoricalPrice; changed.run(); });
            b.session(BotSessions.Bot.BAZAAR);
            b.panel("Bazaar panel", () -> fc.bazaarPanelEnabled, () -> { fc.bazaarPanelEnabled = !fc.bazaarPanelEnabled; fc.save(); },
                    () -> fc.bazaarPanelRows, v -> { fc.bazaarPanelRows = v; fc.save(); });
            b.group("Blocked items")
                    .picker("Blocked items", "Never bought, also in item mode.", SkyblockModules::bazaarOptions,
                            () -> fc.blacklist == null ? List.of() : List.copyOf(fc.blacklist),
                            fc::addBlocked, fc::removeBlocked, fc::save, "Search Bazaar items");
        });
    }

    private static List<Option> bazaarOptions() {
        List<Option> options = new ArrayList<>();
        for (String name : com.coflnet.flipper.data.ItemNames.allProducts().values()) {
            options.add(new Option(name, ""));
        }
        options.sort((a, b) -> a.value().compareToIgnoreCase(b.value()));
        return options;
    }

    private static Module lowballing() {
        var c = LowballingMod.CONFIG;
        return new Module("lowballing", "Lowballing",
                "Walks the hub, asks players for items and offers a low price.",
                Section.TRADING, () -> new ItemStack(Items.PAPER),
                LowballingMod::isRunning, LowballingMod::toggle, b -> {
            b.group("Offers")
                    .slider("Min profit", "Smallest profit an offer must leave.", 0, 50, 1, "%",
                            () -> c.minProfitPercent, v -> { c.minProfitPercent = v; c.save(); })
                    .slider("First offer discount", "How far under the value the first offer starts.", 0, 50, 1, "%",
                            () -> c.firstOfferDiscountPercent, v -> { c.firstOfferDiscountPercent = v; c.save(); })
                    .slider("Reserve", "Coins that always stay in the purse.", 0, 100, 1, "M",
                            () -> c.reserveCoins / 1_000_000.0, v -> { c.reserveCoins = Math.round(v * 1_000_000.0); c.save(); })
                    .toggle("Enforce bankroll", "Never offers more than the purse minus the reserve.",
                            () -> c.enforceBankroll, () -> { c.enforceBankroll = !c.enforceBankroll; c.save(); });
            b.group("Behaviour")
                    .toggle("Loop", "Starts the next round on its own.", () -> c.loop, () -> { c.loop = !c.loop; c.save(); })
                    .slider("Max cycles", "Rounds before it stops. 0 = no limit.", 0, 200, 1, "",
                            () -> c.maxCycles, v -> { c.maxCycles = (int) Math.round(v); c.save(); })
                    .slider("Hub forward", "Blocks it walks forward after joining a hub.", 0, 20, 1, "blocks",
                            () -> c.hubForwardBlocks, v -> { c.hubForwardBlocks = v; c.save(); })
                    .slider("Interact reach", "Distance for trading with a player.", 2, 5, .1, "blocks",
                            () -> c.interactReach, v -> { c.interactReach = v; c.save(); })
                    .toggle("Debug", "Writes more details to the log.", () -> c.debug, () -> { c.debug = !c.debug; c.save(); });
            b.session(BotSessions.Bot.LOWBALL);
            b.panel("Lowballing panel", () -> c.panelEnabled, () -> { c.panelEnabled = !c.panelEnabled; c.save(); },
                    () -> c.panelRows, v -> { c.panelRows = v; c.save(); });
        });
    }

    private static Module investments() {
        var c = AhFlipperMod.CONFIG;
        return new Module("investments", "AH Investments",
                "Finds auction items that are cheap now and rise in price over the next days.",
                Section.TRADING, () -> new ItemStack(Items.GOLD_BLOCK),
                () -> c.investmentGui, () -> {
            c.investmentGui = !c.investmentGui;
            c.save();
            if (c.investmentGui) AhInvestmentTracker.start(); else AhInvestmentTracker.stop();
        }, b -> {
            b.group("Investments")
                    .action("Investment screen", "The current best investments with expected profit.", "Open", () -> {
                        c.investmentGui = true;
                        c.save();
                        AhInvestmentTracker.start();
                        Minecraft.getInstance().gui.setScreen(new AhInvestmentScreen().withParent(Minecraft.getInstance().gui.screen()));
                    })
                    .slider("Max buy price", "Most an item may cost.", 0, 1000, 1, "M",
                            () -> c.investmentMaxBuyPrice / 1_000_000.0,
                            v -> { c.investmentMaxBuyPrice = Math.round(v * 1_000_000.0); c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Min price", "Cheaper items are ignored.", 0, 1000, 1, "M",
                            () -> c.investmentMinPrice / 1_000_000.0,
                            v -> { c.investmentMinPrice = Math.round(v * 1_000_000.0); c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Min coins/hour", "Expected profit per hour of holding.", 0, 500, 1, "k",
                            () -> c.investmentMinCoinsPerHour / 1000.0,
                            v -> { c.investmentMinCoinsPerHour = Math.round(v * 1000.0); c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Min margin", "Smallest expected profit after tax.", 1, 50, 1, "%",
                            () -> c.investmentMinMarginPercent,
                            v -> { c.investmentMinMarginPercent = v; c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Slippage", "Safety margin for prices moving while you buy.", 1, 20, 1, "%",
                            () -> c.investmentSlippagePercent,
                            v -> { c.investmentSlippagePercent = v; c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Max overpay", "How much over the usual price a buy may be.", 5, 100, 5, "%",
                            () -> c.investmentMaxBuyPremiumPercent,
                            v -> { c.investmentMaxBuyPremiumPercent = v; c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Max quantity", "Most pieces of one item.", 1, 20, 1, "",
                            () -> c.investmentMaxQty, v -> { c.investmentMaxQty = (int) Math.round(v); c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Items scanned", "How many items are analysed per refresh.", 20, 400, 10, "",
                            () -> c.investmentMaxTags, v -> { c.investmentMaxTags = (int) Math.round(v); c.save(); AhInvestmentTracker.requestRefresh(); })
                    .slider("Refresh", "Time between two scans.", 2, 60, 1, "min",
                            () -> c.investmentRefreshMinutes, v -> { c.investmentRefreshMinutes = (int) Math.round(v); c.save(); });
            b.group("On-screen panel")
                    .toggle("Investment panel", "Top investments on screen.", () -> c.investmentPanelEnabled,
                            () -> { c.investmentPanelEnabled = !c.investmentPanelEnabled; c.save(); })
                    .slider("Rows", "Entries shown.", 1, 5, 1, "", () -> c.investmentPanelRows,
                            v -> { c.investmentPanelRows = (int) Math.round(v); c.save(); });
        });
    }

    private static Module bazaarFlips() {
        FlipperConfig fc = FlipperMod.CONFIG;
        return new Module("bazaarflips", "Bazaar Flips",
                "The best Bazaar flips right now, sorted by coins per hour.",
                Section.TRADING, () -> new ItemStack(Items.EMERALD_BLOCK), null, null, b -> b
                .group("Bazaar flips")
                .action("Bazaar Flips screen", "Uses the Bazaar Flipper's filters: what it shows is what the bot would buy.",
                        "Open", () -> Minecraft.getInstance().gui.setScreen(new com.coflnet.ahflipper.BazaarFlipScreen()
                                .withParent(Minecraft.getInstance().gui.screen())))
                .slider("Rows", "How many flips the screen lists.", 3, 25, 1, "",
                        () -> fc.flipsTopCount, v -> {
                            fc.flipsTopCount = (int) Math.round(v);
                            fc.save();
                            com.coflnet.flipper.analysis.BazaarFlipTracker.resort();
                        })
                .slider("Refresh", "Time between two scans while the screen is open.", 1, 30, 1, "min",
                        () -> fc.flipsRefreshMinutes, v -> {
                            fc.flipsRefreshMinutes = (int) Math.round(v);
                            fc.save();
                        }));
    }

    private static Module fireSale() {
        var c = AhFlipperMod.CONFIG;
        Runnable refresh = AhFireSaleSkinTracker::requestRefresh;
        return new Module("firesale", "Fire Sale Skins",
                "Rates Fire Sale skins: which ones gain value after the sale ends.",
                Section.TRADING, () -> new ItemStack(Items.FIRE_CHARGE),
                () -> c.fireSaleSkins, () -> {
            c.fireSaleSkins = !c.fireSaleSkins;
            c.save();
            if (c.fireSaleSkins) AhFireSaleSkinTracker.start(); else AhFireSaleSkinTracker.stop();
        }, b -> b
                .group("Fire sale")
                .action("Fire Sale screen", "All rated skins with expected return.", "Open", () -> {
                    c.fireSaleSkins = true;
                    c.save();
                    AhFireSaleSkinTracker.start();
                    Minecraft.getInstance().gui.setScreen(new AhFireSaleSkinScreen().withParent(Minecraft.getInstance().gui.screen()));
                })
                .slider("Min price", "Cheaper skins are ignored.", 1, 1000, 1, "M",
                        () -> c.fireSaleMinPrice / 1_000_000.0, v -> { c.fireSaleMinPrice = Math.round(v * 1_000_000.0); c.save(); refresh.run(); })
                .slider("Max price", "More expensive skins are ignored.", 100, 20000, 100, "M",
                        () -> c.fireSaleMaxPrice / 1_000_000.0, v -> { c.fireSaleMaxPrice = Math.round(v * 1_000_000.0); c.save(); refresh.run(); })
                .slider("Min return", "Expected gain a skin must reach.", 0, 200, 5, "%",
                        () -> c.fireSaleMinRoiPercent, v -> { c.fireSaleMinRoiPercent = v; c.save(); refresh.run(); })
                .slider("Min trend", "Price must rise at least this much per day.", -2, 5, .5, "%/d",
                        () -> c.fireSaleMinTrendPercent, v -> { c.fireSaleMinTrendPercent = v; c.save(); refresh.run(); })
                .slider("Min confidence", "How sure the estimate must be.", 5, 90, 5, "%",
                        () -> c.fireSaleMinConfidence * 100.0, v -> { c.fireSaleMinConfidence = v / 100.0; c.save(); refresh.run(); })
                .slider("Min sales", "Past sales needed for a rating.", 1, 20, 1, "",
                        () -> c.fireSaleMinSales, v -> { c.fireSaleMinSales = (int) Math.round(v); c.save(); refresh.run(); })
                .slider("Max quantity", "Most pieces of one skin.", 1, 10, 1, "",
                        () -> c.fireSaleMaxQty, v -> { c.fireSaleMaxQty = (int) Math.round(v); c.save(); refresh.run(); })
                .slider("Skins scanned", "How many skins are analysed per refresh.", 5, 300, 5, "",
                        () -> c.fireSaleMaxTags, v -> { c.fireSaleMaxTags = (int) Math.round(v); c.save(); refresh.run(); })
                .slider("Refresh", "Time between two scans.", 5, 120, 5, "min",
                        () -> c.fireSaleRefreshMinutes, v -> { c.fireSaleRefreshMinutes = (int) Math.round(v); c.save(); })
                .slider("Backtest fill chance", "Assumed chance a buy order fills, for the backtest.", 10, 100, 10, "%",
                        () -> c.fireSaleBacktestFillProbability * 100.0,
                        v -> { c.fireSaleBacktestFillProbability = v / 100.0; c.save(); AhFireSaleBacktest.runAsync(); }));
    }

    private static Module panelStyle() {
        var ah = AhFlipperMod.CONFIG;
        FlipperConfig fc = FlipperMod.CONFIG;
        var lb = LowballingMod.CONFIG;
        return new Module("panels", "Panel Style", "Look of the bots' on-screen panels: glass, edges and footer.",
                Section.GENERAL, () -> new ItemStack(Items.GLASS_PANE), null, null, b -> b
                .panelStyle("Combat", () -> PlayerSettings.combatPanelGlass, () -> PlayerSettings.combatPanelGlass = !PlayerSettings.combatPanelGlass,
                        () -> PlayerSettings.combatPanelEdges, () -> PlayerSettings.combatPanelEdges = !PlayerSettings.combatPanelEdges,
                        () -> PlayerSettings.combatPanelFooter, () -> PlayerSettings.combatPanelFooter = !PlayerSettings.combatPanelFooter)
                .panelStyle("Auction Flipper", () -> ah.ahPanelGlass, () -> { ah.ahPanelGlass = !ah.ahPanelGlass; ah.save(); },
                        () -> ah.ahPanelEdges, () -> { ah.ahPanelEdges = !ah.ahPanelEdges; ah.save(); },
                        () -> ah.ahPanelFooter, () -> { ah.ahPanelFooter = !ah.ahPanelFooter; ah.save(); })
                .panelStyle("Auto-Bid", () -> ah.bidsPanelGlass, () -> { ah.bidsPanelGlass = !ah.bidsPanelGlass; ah.save(); },
                        () -> ah.bidsPanelEdges, () -> { ah.bidsPanelEdges = !ah.bidsPanelEdges; ah.save(); },
                        () -> ah.bidsPanelFooter, () -> { ah.bidsPanelFooter = !ah.bidsPanelFooter; ah.save(); })
                .panelStyle("Bazaar Flipper", () -> fc.bazaarPanelGlass, () -> { fc.bazaarPanelGlass = !fc.bazaarPanelGlass; fc.save(); },
                        () -> fc.bazaarPanelEdges, () -> { fc.bazaarPanelEdges = !fc.bazaarPanelEdges; fc.save(); },
                        () -> fc.bazaarPanelFooter, () -> { fc.bazaarPanelFooter = !fc.bazaarPanelFooter; fc.save(); })
                .panelStyle("Lowballing", () -> lb.panelGlass, () -> { lb.panelGlass = !lb.panelGlass; lb.save(); },
                        () -> lb.panelEdges, () -> { lb.panelEdges = !lb.panelEdges; lb.save(); },
                        () -> lb.panelFooter, () -> { lb.panelFooter = !lb.panelFooter; lb.save(); })
                .panelStyle("AH Investments", () -> ah.investmentPanelGlass, () -> { ah.investmentPanelGlass = !ah.investmentPanelGlass; ah.save(); },
                        () -> ah.investmentPanelEdges, () -> { ah.investmentPanelEdges = !ah.investmentPanelEdges; ah.save(); },
                        () -> ah.investmentPanelFooter, () -> { ah.investmentPanelFooter = !ah.investmentPanelFooter; ah.save(); }));
    }

    // ---------------------------------------------------------------- Misc

    private static Module pathfinder() {
        var pf = com.coflnet.pathing.PathfinderConfig.get();
        return new Module("pathfinder", "Pathfinder",
                "How the bots walk: jumps, parkour, safety distances, AOTV and learning from mistakes.",
                Section.MISC, () -> new ItemStack(Items.COMPASS), null, null, b -> b
                .group("Movement")
                .info("Stats", "Walking stats of this session.",
                        () -> com.coflnet.pathing.PathfinderTuning.stats().summary(), () -> 0xFFA4F0C1)
                .toggle("Allow jumps", "May jump up blocks. Off: only walks and takes stairs.",
                        () -> pf.allowJumps, () -> { pf.allowJumps = !pf.allowJumps; pf.save(); })
                .toggle("Allow parkour", "May jump over gaps.",
                        () -> pf.allowParkour, () -> { pf.allowParkour = !pf.allowParkour; pf.save(); })
                .toggle("Allow sprint", "Sprints on longer straight parts.",
                        () -> pf.allowSprint, () -> { pf.allowSprint = !pf.allowSprint; pf.save(); })
                .slider("Calm movement", "Higher = smoother, calmer camera while walking. Lower = snappier.",
                        0, 100, 5, "%", () -> pf.movementCalmness, v -> { pf.movementCalmness = v; pf.save(); })
                .toggle("Look at nearby players", "Glances at players that come close while walking, like a real player would.",
                        () -> PlayerSettings.followLookAtNearbyPlayers,
                        () -> PlayerSettings.followLookAtNearbyPlayers = !PlayerSettings.followLookAtNearbyPlayers)
                .toggle("Show path and target", "Draws the planned path on the floor and boxes around the targets.",
                        () -> pf.showPathOverlay, () -> { pf.showPathOverlay = !pf.showPathOverlay; pf.save(); })
                .group("Safety")
                .toggle("Avoid standing NPCs", "Walks around players and NPCs that stand still.",
                        () -> pf.avoidStandingEntities, () -> { pf.avoidStandingEntities = !pf.avoidStandingEntities; pf.save(); })
                .toggle("Setback guard", "Avoids spots where the server pulled you back before.",
                        () -> pf.setbackGuard, () -> { pf.setbackGuard = !pf.setbackGuard; pf.save(); })
                .slider("Edge distance", "How strongly it keeps away from drops.", 0, 10, .5, "",
                        () -> pf.edgeSafetyCost, v -> { pf.edgeSafetyCost = v; pf.save(); })
                .slider("Wall distance", "How strongly it keeps away from walls.", 0, 5, .25, "",
                        () -> pf.wallSafetyCost, v -> { pf.wallSafetyCost = v; pf.save(); })
                .group("Tuning")
                .slider("Max turn", "Fastest camera turn per tick while walking.", 10, 180, 5, "°/t",
                        () -> pf.maxTurnPerTick, v -> { pf.maxTurnPerTick = v; pf.save(); })
                .slider("Jump penalty", "Higher = prefers walking around over jumping.", 0, 10, .5, "",
                        () -> pf.jumpPenalty, v -> { pf.jumpPenalty = v; pf.save(); })
                .slider("Search speed", "Higher finds paths faster but they are less ideal.", 1, 5, .1, "",
                        () -> pf.costHeuristic, v -> { pf.costHeuristic = v; pf.save(); })
                .slider("Move timeout", "Gives up a step that takes longer than this.", 10, 200, 5, "ticks",
                        () -> pf.movementTimeoutTicks, v -> { pf.movementTimeoutTicks = (int) Math.round(v); pf.save(); })
                .slider("Search limit", "Longest time a path search may take.", 1, 10, .5, "s",
                        () -> pf.failureTimeoutMs / 1000.0, v -> { pf.failureTimeoutMs = Math.round(v * 1000); pf.save(); })
                .group("AOTV")
                .choice("Mode", "Aspect of the Void: teleport ahead on long paths, or Etherwarp, or off.",
                        java.util.Arrays.stream(com.coflnet.pathing.AotvAssist.Mode.values()).map(Enum::name).toList(),
                        () -> com.coflnet.pathing.AotvAssist.mode().name(), v -> { pf.aotvMode = v; pf.save(); })
                .slider("Teleport blocks", "How far one teleport goes.", 4, 20, 1, "blocks",
                        () -> pf.aotvTeleportBlocks, v -> { pf.aotvTeleportBlocks = (int) Math.round(v); pf.save(); })
                .slider("Min gain", "Only teleports that save at least this much walking.", 3, 40, 1, "blocks",
                        () -> pf.aotvMinGain, v -> { pf.aotvMinGain = (int) Math.round(v); pf.save(); })
                .group("Learning from faults")
                .toggle("Learn from faults", "Remembers spots where it got stuck and avoids them next time.",
                        () -> pf.learnFromFaults, () -> { pf.learnFromFaults = !pf.learnFromFaults; pf.save(); })
                .slider("Avoid strength", "How strongly known bad spots are avoided.", .5, 10, .5, "",
                        () -> pf.faultPenaltyStrength, v -> { pf.faultPenaltyStrength = v; pf.save(); })
                .slider("Forget after", "Half-life of a remembered fault.", 1, 240, 1, "h",
                        () -> pf.faultHalfLifeHours, v -> { pf.faultHalfLifeHours = v; pf.save(); })
                .slider("Faults to avoid", "How often a spot must fail before it is avoided.", 1, 5, 1, "",
                        () -> pf.faultMinCount, v -> { pf.faultMinCount = Math.round(v); pf.save(); })
                .toggle("Faults in chat", "Writes every learned fault to chat.",
                        () -> pf.showFaultsInChat, () -> { pf.showFaultsInChat = !pf.showFaultsInChat; pf.save(); })
                .info("Known faults", "Remembered here / in total.",
                        () -> com.coflnet.pathing.FaultMemory.activeSize() + " here / " + com.coflnet.pathing.FaultMemory.size() + " total",
                        () -> 0xFFD0D0D0)
                .action("Clear faults", "Forgets every remembered bad spot.", "Clear",
                        com.coflnet.pathing.FaultMemory::clear)
                .group("Path recorder")
                .toggle("Record paths", "Logs every run (plan vs. what really happened) to logs/pathfinder.",
                        () -> pf.recordPaths, () -> { pf.recordPaths = !pf.recordPaths; pf.save(); })
                .toggle("Summary in chat", "Short summary in chat after each run.",
                        () -> pf.recorderChat, () -> { pf.recorderChat = !pf.recorderChat; pf.save(); })
                .action("Log folder", "Opens logs/pathfinder.", "Open", () -> {
                    try {
                        java.nio.file.Files.createDirectories(com.coflnet.pathing.PathRecorder.folder());
                        net.minecraft.util.Util.getPlatform().openPath(com.coflnet.pathing.PathRecorder.folder());
                    } catch (Exception e) {
                        AhFlipperMod.message("§cCould not open the folder: " + e.getMessage());
                    }
                }));
    }

    private static Module slayer() {
        return new Module("slayer", "Slayer Highlight", "Outlines your slayer boss and its minibosses.",
                Section.MISC, () -> new ItemStack(Items.ROTTEN_FLESH),
                () -> PlayerSettings.slayerOutline, () -> {
            PlayerSettings.slayerOutline = !PlayerSettings.slayerOutline;
            PlayerSettings.slayerBossHighlight = PlayerSettings.slayerOutline;
            VoicechatClient.syncSlayerCfg();
        }, b -> b
                .group("Highlight")
                .toggle("Boss (yellow)", "Outlines the slayer boss in yellow.", () -> PlayerSettings.slayerBoss, () -> {
                    PlayerSettings.slayerBoss = !PlayerSettings.slayerBoss;
                    VoicechatClient.syncSlayerCfg();
                })
                .toggle("Minibosses (red)", "Outlines slayer minibosses in red.", () -> PlayerSettings.slayerMiniboss, () -> {
                    PlayerSettings.slayerMiniboss = !PlayerSettings.slayerMiniboss;
                    VoicechatClient.syncSlayerCfg();
                })
                .toggle("Line to boss", "Draws a line from you to the boss.", () -> PlayerSettings.slayerLine,
                        () -> PlayerSettings.slayerLine = !PlayerSettings.slayerLine));
    }

    private SkyblockModules() {
    }
}
