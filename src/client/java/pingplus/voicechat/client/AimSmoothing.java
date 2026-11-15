package pingplus.voicechat.client;

/** Time-based angular easing with a speed limit and no overshoot at the +/-180 seam. */
public final class AimSmoothing {
    public static float step(float current, float target, float degreesPerSecond, double seconds) {
        return step(current, target, degreesPerSecond, seconds, 10.0);
    }

    /** Same with an easing rate (1/s): lower = softer approach to the target angle. */
    public static float step(float current, float target, float degreesPerSecond, double seconds, double rate) {
        if (!Float.isFinite(current) || !Float.isFinite(target) || !Float.isFinite(degreesPerSecond)
                || !Double.isFinite(seconds)) return Float.isFinite(current) ? current : 0;
        double dt = Math.clamp(seconds, 0, .05);
        float delta = ((target - current) % 360 + 540) % 360 - 180;
        double eased = delta * (1 - Math.exp(-Math.max(0.5, rate) * dt));
        double limit = Math.max(0, degreesPerSecond) * dt;
        return current + (float)Math.clamp(eased, -limit, limit);
    }
    private AimSmoothing() {}
}
