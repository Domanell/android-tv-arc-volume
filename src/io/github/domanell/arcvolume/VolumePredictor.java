package io.github.domanell.arcvolume;

/**
 * Turns the raw STREAM_MUSIC index sequence into the soundbar level to display.
 *
 * <p>With a CEC soundbar in full-volume mode a remote key press may first move the local index
 * by one, then {@code <Report Audio Status>} sets the soundbar's own level, which moves by the
 * soundbar's step per press. With a step of 1, or on a TV that writes only the soundbar's
 * report, one change arrives per press and is shown as is.
 *
 * <p>The step starts at 1 until it is determined, so every change is shown at once. It is
 * learned from isolated presses. A burst of exactly two changes in one direction that starts one
 * away from the confirmed level suggests the step as its total distance; a burst of one change
 * by one suggests a step of 1. A suggestion is accepted only after it repeats in consecutive
 * bursts, because two quick presses on a step-1 soundbar look like one press with a step of 2,
 * and a single change can also come from the soundbar's own remote. A determined step (learned
 * or measured) is replaced only after a longer run of agreeing bursts.
 *
 * <p>With a known step above 1, an index whose distance from the confirmed level is not a
 * multiple of the step is the local intermediate of a key press; the level one press ahead is
 * shown instead, kept within the stream's volume range.
 *
 * <p>With learning off (a step the user fixed) the step is used as given and never replaced.
 */
final class VolumePredictor {
    static final int UNKNOWN = 0;
    /** Step used until one is determined; shows every change as is. */
    static final int DEFAULT_STEP = 1;

    /** Consecutive bursts that must suggest a step of 1 before it is accepted. */
    private static final int SINGLE_STEP_EVIDENCE = 3;
    /** Consecutive bursts that must suggest a larger step before it is accepted. */
    private static final int MULTI_STEP_EVIDENCE = 2;
    /** Consecutive bursts that must suggest another step before a determined one is replaced. */
    private static final int REPLACE_EVIDENCE = 4;

    private final int min;
    private final int max;
    private int step;
    private boolean determined;
    private boolean learning;
    private int confirmed;
    private boolean keyPress;

    private int burstChanges;
    private int burstStart;
    private int burstFirst;
    private int burstLast;
    private boolean burstMonotonic;
    private int candidate = UNKNOWN;
    private int candidateBursts;

    VolumePredictor(int confirmed, int step, boolean determined, boolean learning, int min, int max) {
        this.confirmed = confirmed;
        this.min = min;
        this.max = max;
        configure(step, determined, learning);
    }

    int step() {
        return step;
    }

    /** False while the step is still the default and nothing has confirmed it. */
    boolean determined() {
        return determined;
    }

    boolean learning() {
        return learning;
    }

    /**
     * Sets the step to start from, whether it was determined (learned, measured or entered)
     * and whether observations may replace it. An unknown step falls back to the default.
     */
    void configure(int step, boolean determined, boolean learning) {
        this.step = step == UNKNOWN ? DEFAULT_STEP : step;
        this.determined = determined && step != UNKNOWN;
        this.learning = learning;
        candidate = UNKNOWN;
        candidateBursts = 0;
    }

    /** Whether the last {@link #onIndex} change looked like a remote key press. */
    boolean wasKeyPress() {
        return keyPress;
    }

    /** Handles one index change and returns the level to show. */
    int onIndex(int index) {
        trackBurst(index);
        int distance = index - confirmed;

        if (step == 1) {
            keyPress = true;
            confirmed = index;
            return index;
        }
        int offset = Math.floorMod(Math.abs(distance), step);
        if (offset == 0) {
            keyPress = false;
            confirmed = index;
            return index;
        }
        keyPress = true;
        int predicted = index + Integer.signum(distance) * (step - offset);
        return Math.max(min, Math.min(max, predicted));
    }

    /**
     * Called once the index has stopped changing; returns true if the step was learned, which
     * includes the default step being confirmed. With {@code learn} false the burst is not
     * used as evidence and breaks the run of agreeing bursts.
     */
    boolean settle(int index, boolean learn) {
        int learned = UNKNOWN;
        if (learning && learn) {
            learned = learnedStep(index);
        } else {
            candidate = UNKNOWN;
            candidateBursts = 0;
        }
        confirmed = index;
        burstChanges = 0;
        if (learned == UNKNOWN || (learned == step && determined)) {
            return false;
        }
        step = learned;
        determined = true;
        return true;
    }

    private void trackBurst(int index) {
        if (burstChanges == 0) {
            burstStart = confirmed;
            burstFirst = index;
            burstMonotonic = true;
        } else if (Integer.signum(index - burstLast) != Integer.signum(burstLast - burstStart)) {
            burstMonotonic = false;
        }
        burstLast = index;
        burstChanges++;
    }

    /** Returns the step once the current suggestion has repeated often enough, else UNKNOWN. */
    private int learnedStep(int index) {
        int suggested = suggestedStep(index);
        if (suggested == UNKNOWN) {
            // Bursts must agree in a row; one that is not an isolated press breaks the run.
            candidate = UNKNOWN;
            candidateBursts = 0;
            return UNKNOWN;
        }
        if (suggested == candidate) {
            candidateBursts++;
        } else {
            candidate = suggested;
            candidateBursts = 1;
        }
        int needed;
        if (determined && suggested != step) {
            needed = REPLACE_EVIDENCE;
        } else {
            needed = suggested == 1 ? SINGLE_STEP_EVIDENCE : MULTI_STEP_EVIDENCE;
        }
        return candidateBursts >= needed ? suggested : UNKNOWN;
    }

    /** The step this burst points to if it looks like one isolated press, else UNKNOWN. */
    private int suggestedStep(int index) {
        if (!burstMonotonic || burstChanges == 0 || Math.abs(burstFirst - burstStart) != 1) {
            return UNKNOWN;
        }
        int total = Math.abs(index - burstStart);
        if (burstChanges == 2 && total >= 2) {
            return total;
        }
        if (burstChanges == 1 && total == 1) {
            return 1;
        }
        return UNKNOWN;
    }
}
