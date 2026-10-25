package io.github.domanell.arcvolume;

/**
 * Guided one-time step measurement. The user presses volume up several times with pauses; each
 * press is measured once the level settles. Key presses seen by the app screen divide the
 * change; when the TV does not pass the keys to the app, each settled change counts as one
 * press. A held key, or more volume changes than one press makes, spoils that measurement.
 * The step is found once enough presses in a row agree.
 */
final class StepCalibration {
    static final int MATCHES_NEEDED = 3;
    /** Measured presses after which a missing agreement counts as failure. */
    static final int MAX_PRESSES = 6;
    private static final int MISMATCH = -1;
    /** One press changes the index at most twice: the TV's local step, then the soundbar report. */
    private static final int CHANGES_PER_PRESS = 2;

    private final int maxLevel;
    private final int maxStep;
    private final int[] samples = new int[MAX_PRESSES];
    private int count;
    private int level;
    private int keyPresses;
    private int changes;
    private boolean held;
    private boolean atLimit;

    StepCalibration(int level, int maxLevel, int maxStep) {
        this.level = level;
        this.maxLevel = maxLevel;
        this.maxStep = maxStep;
        atLimit = level >= maxLevel;
    }

    void onKeyPress() {
        keyPresses++;
    }

    /** A repeat event of a held volume key, seen when the TV passes keys to the app. */
    void onKeyRepeat() {
        held = true;
    }

    void onVolumeChange() {
        changes++;
    }

    /** Called once the level has stopped changing after a press. */
    void onSettled(int newLevel) {
        int delta = Math.abs(newLevel - level);
        int presses = keyPresses;
        boolean spoiled = held || changes > CHANGES_PER_PRESS * Math.max(1, presses);
        keyPresses = 0;
        changes = 0;
        held = false;
        if (delta == 0) {
            // A key press without a change, or a level already at the maximum: the volume is
            // at its limit.
            atLimit = presses > 0 || level >= maxLevel;
            return;
        }
        level = newLevel;
        // At the maximum the next press cannot change the level, and the TV may not pass the
        // key to the app to show it; a press that reached the maximum may also be cut short.
        atLimit = newLevel >= maxLevel;
        if (atLimit || count == MAX_PRESSES) {
            return;
        }
        int sample = spoiled ? MISMATCH
                : presses <= 1 ? delta : delta % presses == 0 ? delta / presses : MISMATCH;
        samples[count++] = sample > maxStep ? MISMATCH : sample;
    }

    /** Number of measured presses so far. */
    int presses() {
        return count;
    }

    /** Step of the last measured press, or {@link VolumePredictor#UNKNOWN}. */
    int lastStep() {
        return count == 0 || samples[count - 1] == MISMATCH ? VolumePredictor.UNKNOWN : samples[count - 1];
    }

    /** How many of the latest presses agree with the last one. */
    int matches() {
        int last = lastStep();
        if (last == VolumePredictor.UNKNOWN) {
            return 0;
        }
        int matches = 0;
        for (int i = count - 1; i >= 0 && samples[i] == last; i--) {
            matches++;
        }
        return matches;
    }

    /** The measured step, or {@link VolumePredictor#UNKNOWN} while it is not found. */
    int result() {
        return matches() >= MATCHES_NEEDED ? lastStep() : VolumePredictor.UNKNOWN;
    }

    boolean failed() {
        return result() == VolumePredictor.UNKNOWN && count == MAX_PRESSES;
    }

    /** True when the last press did not change the level. */
    boolean atLimit() {
        return atLimit;
    }
}
