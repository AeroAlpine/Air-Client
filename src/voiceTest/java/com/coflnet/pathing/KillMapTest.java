package com.coflnet.pathing;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The combat bot's kill map: dense kill areas are picked far more often, not always. */
public final class KillMapTest {
    private KillMapTest() {
    }

    public static void main(String[] args) {
        List<Vec3> kills = new ArrayList<>();
        // a hot spot of 12 kills around (100, 64, 100) and 2 lonely kills far away
        Random seed = new Random(7);
        for (int i = 0; i < 12; i++) {
            kills.add(new Vec3(100 + seed.nextGaussian() * 3, 64, 100 + seed.nextGaussian() * 3));
        }
        kills.add(new Vec3(-200, 64, 0));
        kills.add(new Vec3(0, 64, 300));

        check(KillMap.density(kills, new Vec3(100, 64, 100)) > 5 * KillMap.density(kills, new Vec3(-200, 64, 0)),
                "the hot spot is much denser than a lonely kill");

        Random random = new Random(42);
        int nearHotSpot = 0;
        int runs = 2000;
        for (int i = 0; i < runs; i++) {
            Vec3 pick = KillMap.pick(kills, new Vec3(0, 64, 0), 8.0, random::nextDouble);
            check(pick != null, "a spot is picked");
            if (Math.hypot(pick.x - 100, pick.z - 100) < 15) {
                nearHotSpot++;
            }
        }
        double share = nearHotSpot / (double) runs;
        check(share > 0.9 && share < 1.0, "mostly the hot spot, but not always: " + share);

        // standing on the hot spot already: still something to walk to
        Vec3 away = KillMap.pick(kills, new Vec3(100, 64, 100), 8.0, random::nextDouble);
        check(away != null, "standing in the hot spot still gives a target");
        check(KillMap.pick(List.of(), new Vec3(0, 64, 0), 8.0, random::nextDouble) == null, "no kills: no target");

        System.out.println("PASS: kill map density, weighted pick towards hot spots, empty map");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
