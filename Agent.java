///SolidOne,h356205@stud.u-szeged.hu
import java.util.Random;

import game.mario.Direction;
import game.mario.MarioGame;
import game.mario.MarioPlayer;
import game.mario.utils.MarioState;

/**
 * Agent.java
 *
 * Adaptive Reflex Engine (ARE) - reflex + futáson belüli adaptáció.
 *
 * Jellemzők:
 * - Nincs fájlművelet, nincs szál, nincs reflection (beadási szabályoknak megfelel).
 * - Pit és wall detektálás state.map alapján.
 * - Beszorulás és hirtelen visszaesés detektálása state.distance alapján.
 * - Futáson belüli adaptáció: ha sok a "crash"/visszaesés, növeli az óvatosságot
 *   (nagyobb ugrások, nagyobb lookahead, alacsonyabb véletlen ugrás esély).
 * - Ha jól halad, fokozatosan visszaáll az agresszívebb, pontszerző viselkedésre.
 *
 * A paraméterek finomhangolhatók a kódban lévő konstansok módosításával.
 *
 * Magyar Javadoc és kommentek a beadási elvárások szerint.
 */
public class Agent extends MarioPlayer {

    // ----------- Alap paraméterek (kiindulási értékek) -------------
    private int LOOKAHEAD_MIN = 2;
    private int LOOKAHEAD_MAX = 5;
    private int PIT_DEPTH_CHECK = 6;    // hány alsó sort ellenőrzünk
    private int STUCK_THRESHOLD = 6;    // beszorulás detektáláshoz
    private int baseMaxJumpHold = 12;   // alap ugrás tartam
    private double baseRandomJumpProb = 0.01; // 1% alap véletlen ugrás

    // ----------- Adaptációs belső állapotok -------------
    private int jumpCounter = 0;
    private double prevDistance = 0.0;
    private int stuckFrames = 0;

    // Futáson belüli statisztikák (adaptációhoz)
    private int recentCrashes = 0;      // hirtelen visszaesések száma (short-term)
    private int recentGoodFrames = 0;   // egymás utáni előrehaladó frame-ek
    private int adaptStepCounter = 0;   // időzítő az adaptációhoz

    // Dinamikus, jelenlegi paraméterek (módosulhatnak futás közben)
    private int currLookaheadMax;
    private int currMaxJumpHold;
    private double currRandomJumpProb;
    private int currStuckThreshold;

    // Biztonsági korlátok az adaptációnál
    private static final int MAX_LOOKAHEAD_LIMIT = 8;
    private static final int MAX_JUMP_HOLD_LIMIT = 20;
    private static final double MIN_RANDOM_PROB = 0.002; // 0.2%
    private static final double MAX_RANDOM_PROB = 0.12;  // 12%

    // Adaptációs küszöbök
    private static final int CRASH_WINDOW = 40; // ennyi frame alatt vizsgáljuk a recentCrashes-t
    private int crashWindowCounter = 0;

    /**
     * Konstruktor — használja az ős által biztosított Random objektumot.
     *
     * @param color  szín paraméter a keretrendszer részére
     * @param random örökölt Random objektum (tilos seedet módosítani)
     * @param state  a MarioState objektum
     */
    public Agent(int color, Random random, MarioState state) {
        super(color, random, state);
        // Inicializáljuk a dinamikus paramétereket az alapértékekkel
        this.currLookaheadMax = LOOKAHEAD_MAX;
        this.currMaxJumpHold = baseMaxJumpHold;
        this.currRandomJumpProb = baseRandomJumpProb;
        this.currStuckThreshold = STUCK_THRESHOLD;
    }

    /**
     * A döntési metódus. Minden hívásnak vissza kell térnie (nem dobunk kivételt).
     */
    @Override
    public Direction getDirection(long remainingTime) {
        adaptStepCounter++;
        // 0) Ha még tart az ugrás, folytassuk
        if (jumpCounter > 0) {
            jumpCounter--;
            return new Direction(MarioGame.UP);
        }

        // 1) distance alapú metrikák (beszorulás / visszaesés)
        double delta = 0.0;
        try {
            delta = state.distance - prevDistance;
            prevDistance = state.distance;
        } catch (Throwable t) {
            // fallback: ha nincs distance, feltételezzük nullát
            delta = 0.0;
            prevDistance = state.distance;
        }

        if (delta < 1.0) {
            stuckFrames++;
            recentGoodFrames = 0;
        } else {
            stuckFrames = 0;
            recentGoodFrames++;
        }

        // Hirtelen visszaesés (ütközés / instant veszteség jellegű)
        boolean suddenBackward = (delta < -0.4);

        // Track recentCrash időablakot
        if (suddenBackward || (stuckFrames > currStuckThreshold && delta < 1.0)) {
            recentCrashes++;
        }
        crashWindowCounter++;
        if (crashWindowCounter > CRASH_WINDOW) {
            // időablak elfutott -> csökkentjük a recentCrashes-őt idővel
            recentCrashes = Math.max(0, recentCrashes - 1);
            crashWindowCounter = 0;
        }

        // 2) Adaptáció: ha sok recentCrashes, legyünk óvatosabbak
        adaptBehavior();

        // 3) Ha beszorulás történt, panic-jump (elővigyázatos)
        if (stuckFrames > currStuckThreshold) {
            // ha van akadály előre, ugorjunk erősebben, egyébként közepes jump
            if (isObstacleAheadByMap(state, 2, currLookaheadMax)) {
                jumpCounter = Math.min(currMaxJumpHold, 12 + random.nextInt(6));
            } else {
                jumpCounter = Math.min(currMaxJumpHold, 8 + random.nextInt(6));
            }
            stuckFrames = 0;
            return new Direction(MarioGame.UP);
        }

        // 4) Vizsgáljuk a pályát map alapján: pit és wall detection
        boolean obstacleDetected = false;
        try {
            obstacleDetected = isObstacleAheadByMap(state, LOOKAHEAD_MIN, currLookaheadMax);
        } catch (Throwable t) {
            obstacleDetected = false; // ha hiba van, folytatjuk
        }

        if (obstacleDetected) {
            // erősebb ugrás a fal/pit esetén
            jumpCounter = Math.min(currMaxJumpHold, 10 + random.nextInt(8));
            return new Direction(MarioGame.UP);
        }

        // 5) Ha a short-term crash-ok gyakoriak és közeli veszélyos oszlop van, kötelező jump
        if (recentCrashes >= 2 && isLikelyDangerAhead(state, currLookaheadMax)) {
            jumpCounter = Math.min(currMaxJumpHold, 10 + random.nextInt(6));
            // Reset egy kicsit hogy ne ismételjen túl gyakran
            recentCrashes = Math.max(0, recentCrashes - 1);
            return new Direction(MarioGame.UP);
        }

        // 6) Véletlenszerű mikro-ugrás (nagyon kis eséllyel)
        if (random.nextDouble() < currRandomJumpProb) {
            jumpCounter = 3 + random.nextInt(5);
            return new Direction(MarioGame.UP);
        }

        // 7) Alap viselkedés: jobbra haladás
        return new Direction(MarioGame.RIGHT);
    }

    // ---------- Segédfüggvények ----------

    /**
     * Adaptálja a dinamikus paramétereket a recentCrashes / recentGoodFrames alapján.
     * Ha sok a crash -> növeli a lookahead-et, növeli a max jump hold-ot,
     * csökkenti a random jump esélyt, és szigorítja a stuck küszöböt.
     * Ha jól megy a játék, lassan visszahangolja az agresszívebb beállításokra.
     */
    private void adaptBehavior() {
        // Alap célértékek
        int targetLookahead = LOOKAHEAD_MAX;
        int targetMaxJump = baseMaxJumpHold;
        double targetRandomProb = baseRandomJumpProb;
        int targetStuck = STUCK_THRESHOLD;

        // Ha több visszaesés, legyünk óvatosabbak
        if (recentCrashes >= 3) {
            targetLookahead = Math.min(MAX_LOOKAHEAD_LIMIT, LOOKAHEAD_MAX + 2);
            targetMaxJump = Math.min(MAX_JUMP_HOLD_LIMIT, baseMaxJumpHold + 6);
            targetRandomProb = Math.max(MIN_RANDOM_PROB, baseRandomJumpProb * 0.5); // csökkentjük a véletlent
            targetStuck = Math.min(12, STUCK_THRESHOLD + 2);
        } else if (recentCrashes == 2) {
            targetLookahead = Math.min(MAX_LOOKAHEAD_LIMIT, LOOKAHEAD_MAX + 1);
            targetMaxJump = Math.min(MAX_JUMP_HOLD_LIMIT, baseMaxJumpHold + 4);
            targetRandomProb = Math.max(MIN_RANDOM_PROB, baseRandomJumpProb * 0.7);
            targetStuck = Math.min(10, STUCK_THRESHOLD + 1);
        } else {
            // ha jó sorozat, visszahangolódunk agresszívebbre, de lassan
            if (recentGoodFrames > 30) {
                targetLookahead = LOOKAHEAD_MAX;
                targetMaxJump = baseMaxJumpHold;
                targetRandomProb = Math.min(MAX_RANDOM_PROB, baseRandomJumpProb * 1.2);
                targetStuck = STUCK_THRESHOLD;
            }
        }

        // Lassan léptetjük a jelenlegi értékeket a target felé (sima átmenet)
        currLookaheadMax = gradualIntMove(currLookaheadMax, targetLookahead, 1);
        currMaxJumpHold = gradualIntMove(currMaxJumpHold, targetMaxJump, 1);
        currRandomJumpProb = gradualDoubleMove(currRandomJumpProb, targetRandomProb, 0.001);
        currStuckThreshold = gradualIntMove(currStuckThreshold, targetStuck, 1);

        // Biztonsági clamp
        if (currLookaheadMax < LOOKAHEAD_MIN) currLookaheadMax = LOOKAHEAD_MIN;
        if (currLookaheadMax > MAX_LOOKAHEAD_LIMIT) currLookaheadMax = MAX_LOOKAHEAD_LIMIT;
        if (currMaxJumpHold < 4) currMaxJumpHold = 4;
        if (currMaxJumpHold > MAX_JUMP_HOLD_LIMIT) currMaxJumpHold = MAX_JUMP_HOLD_LIMIT;
        if (currRandomJumpProb < MIN_RANDOM_PROB) currRandomJumpProb = MIN_RANDOM_PROB;
        if (currRandomJumpProb > MAX_RANDOM_PROB) currRandomJumpProb = MAX_RANDOM_PROB;
        if (currStuckThreshold < 2) currStuckThreshold = 2;
    }

    /**
     * Gyors ellenőrzés: van-e valamilyen akadály vagy gödör a lookahead tartományban?
     * Csak a state.map-re támaszkodik (nem használ reflectiont).
     *
     * @param st        MarioState
     * @param minLook   minimum lookahead
     * @param maxLook   maximum lookahead
     * @return true, ha akadály vagy pit található a tartományban
     */
    private boolean isObstacleAheadByMap(MarioState st, int minLook, int maxLook) {
        try {
            int[][] map = st.map;
            double startStage = st.startStage;
            if (map == null) return false;

            int mj = (int) Math.floor(st.distance - startStage);
            if (mj < 0) mj = 0;
            if (mj >= map[0].length) mj = map[0].length - 1;

            int mi = Math.max(0, map.length - 4); // becsült Mario magasság (alap)

            for (int look = minLook; look <= maxLook; look++) {
                int c = mj + look;
                if (c < 0 || c >= map[0].length) continue;

                // 1) wall detection körül Mario magasságán
                for (int r = Math.max(0, mi - 2); r <= Math.min(map.length - 1, mi + 1); r++) {
                    if (map[r][c] != 0) return true;
                }

                // 2) pit detection: alsó sorokat vizsgáljuk
                boolean groundFound = false;
                int bottomStart = Math.max(0, map.length - PIT_DEPTH_CHECK);
                for (int r = map.length - 1; r >= bottomStart; r--) {
                    if (map[r][c] != 0) {
                        groundFound = true;
                        break;
                    }
                }
                if (!groundFound) return true;
            }

        } catch (Throwable t) {
            // Ha bármi hiba van, ne dobjunk kivételt, csak ne jelezzünk akadályt
        }
        return false;
    }

    /**
     * Heurisztikus ellenőrzés, hogy valószínűleg veszélyes-e előre egy oszlop
     * (pl. korábbi suddenBackward-ok vagy pit-esetek jelzik)
     * Itt egyszerűsítünk: ha a map alapján az adott lookahead közelében nincsen talaj,
     * azt veszélyesnek tekintjük.
     */
    private boolean isLikelyDangerAhead(MarioState st, int maxLook) {
        try {
            int[][] map = st.map;
            double startStage = st.startStage;
            if (map == null) return false;

            int mj = (int) Math.floor(st.distance - startStage);
            if (mj < 0) mj = 0;
            if (mj >= map[0].length) mj = map[0].length - 1;

            int bottomStart = Math.max(0, map.length - PIT_DEPTH_CHECK);
            for (int look = 1; look <= maxLook; look++) {
                int c = mj + look;
                if (c < 0 || c >= map[0].length) continue;
                boolean groundFound = false;
                for (int r = map.length - 1; r >= bottomStart; r--) {
                    if (map[r][c] != 0) {
                        groundFound = true;
                        break;
                    }
                }
                if (!groundFound) return true;
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    // ---------- Kis segédfüggvények a sima átmenetekhez ----------
    private int gradualIntMove(int current, int target, int step) {
        if (current == target) return current;
        if (current < target) return Math.min(current + step, target);
        return Math.max(current - step, target);
    }

    private double gradualDoubleMove(double current, double target, double step) {
        if (Math.abs(current - target) <= step) return target;
        if (current < target) return current + step;
        return current - step;
    }
}
