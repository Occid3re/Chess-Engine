package julius.game.chessengine.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable container for numeric evaluation parameters that may be tuned. Each accessor returns
 * either an explicitly configured override or the engine default when no override is present.
 */
public final class EvaluationParameters {

    private static final EvaluationParameters DEFAULT = new EvaluationParameters(Collections.emptyMap());

    private final Map<String, Double> values;

    private EvaluationParameters(Map<String, Double> values) {
        this.values = values;
    }

    public static EvaluationParameters defaults() {
        return DEFAULT;
    }

    public static EvaluationParameters of(Map<String, Double> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return defaults();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        rawValues.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || !Double.isFinite(value)) {
                return;
            }
            normalized.put(normalize(key), value);
        });
        if (normalized.isEmpty()) {
            return defaults();
        }
        return new EvaluationParameters(Collections.unmodifiableMap(normalized));
    }

    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    private double getDouble(String key, double defaultValue) {
        Objects.requireNonNull(key, "key");
        if (values.isEmpty()) {
            return defaultValue;
        }
        Double override = values.get(normalize(key));
        return override != null ? override : defaultValue;
    }

    private int getInt(String key, int defaultValue) {
        return (int) Math.round(getDouble(key, defaultValue));
    }

    // Evaluation pipeline -------------------------------------------------

    public int evaluationBlendScale() {
        return Math.max(1, getInt("evaluation.blendScale", 256));
    }

    // Material module -----------------------------------------------------

    public int materialPawnValue() {
        return getInt("material.pawnValue", 100);
    }

    public int materialKnightValue() {
        return getInt("material.knightValue", 320);
    }

    public int materialBishopValue() {
        return getInt("material.bishopValue", 330);
    }

    public int materialRookValue() {
        return getInt("material.rookValue", 500);
    }

    public int materialQueenValue() {
        return getInt("material.queenValue", 900);
    }

    public int materialBishopPairBonus() {
        return getInt("material.bishopPairBonus", 40);
    }

    public int materialValueForPiece(int pieceTypeBits) {
        return switch (pieceTypeBits) {
            case 1 -> materialPawnValue();
            case 2 -> materialKnightValue();
            case 3 -> materialBishopValue();
            case 4 -> materialRookValue();
            case 5 -> materialQueenValue();
            default -> 0;
        };
    }

    // Pawn structure ------------------------------------------------------

    public int pawnStructureCenterPawnBonus() {
        return getInt("pawnStructure.centerPawnBonus", 15);
    }

    public int pawnStructurePassedPawnBonus() {
        return getInt("pawnStructure.passedPawnBonus", 60);
    }

    public int pawnStructureConnectedPawnBonus() {
        return getInt("pawnStructure.connectedPawnBonus", 8);
    }

    public int pawnStructurePawnIslandPenalty() {
        return getInt("pawnStructure.pawnIslandPenalty", -5);
    }

    public int pawnStructureDoubledPawnPenalty() {
        return getInt("pawnStructure.doubledPawnPenalty", -12);
    }

    public int pawnStructureIsolatedPawnPenalty() {
        return getInt("pawnStructure.isolatedPawnPenalty", -10);
    }

    public int pawnStructureAdvancedPawnBonus() {
        return getInt("pawnStructure.advancedPawnBonus", 8);
    }

    public int pawnStructureBlockedPawnPenalty() {
        return getInt("pawnStructure.blockedPawnPenalty", -10);
    }

    public int pawnStructureBackwardPawnPenalty() {
        return getInt("pawnStructure.backwardPawnPenalty", -12);
    }

    public int pawnStructureOwnKingBlocksPassedPawnPenalty() {
        return getInt("pawnStructure.ownKingBlocksPassedPawnPenalty", -150);
    }

    public int pawnStructurePassedPawnFreePathBonusPerRank() {
        return getInt("pawnStructure.passedPawnFreePathBonusPerRank", 12);
    }

    public int pawnStructureRookHalfOpenFileBonus() {
        return getInt("pawnStructure.rookHalfOpenFileBonus", 15);
    }

    public int pawnStructureRookOpenFileBonus() {
        return getInt("pawnStructure.rookOpenFileBonus", 25);
    }

    // King safety ---------------------------------------------------------

    public int kingSafetyMissingPawnShieldPenalty() {
        return getInt("kingSafety.missingPawnShieldPenalty", -15);
    }

    public int kingSafetyHalfOpenFilePenalty() {
        return getInt("kingSafety.halfOpenFilePenalty", -15);
    }

    public int kingSafetyOpenFilePenalty() {
        return getInt("kingSafety.openFilePenalty", -25);
    }

    public int kingSafetyDefenderBonus() {
        return getInt("kingSafety.defenderBonus", 5);
    }

    public int kingSafetyQueenAttackedPenalty() {
        return getInt("kingSafety.queenAttackedPenalty", -75);
    }

    public int kingSafetyBackrankWeaknessMidgamePenalty() {
        return getInt("kingSafety.backrankWeaknessMidgamePenalty", -100);
    }

    public int kingSafetyBackrankWeaknessEndgamePenalty() {
        return getInt("kingSafety.backrankWeaknessEndgamePenalty", -50);
    }

    public int kingSafetyAttackWeightPawn() {
        return getInt("kingSafety.attackWeightPawn", 5);
    }

    public int kingSafetyAttackWeightKnight() {
        return getInt("kingSafety.attackWeightKnight", 10);
    }

    public int kingSafetyAttackWeightBishop() {
        return getInt("kingSafety.attackWeightBishop", 10);
    }

    public int kingSafetyAttackWeightRook() {
        return getInt("kingSafety.attackWeightRook", 15);
    }

    public int kingSafetyAttackWeightQueen() {
        return getInt("kingSafety.attackWeightQueen", 20);
    }

    // Piece-square --------------------------------------------------------

    public int pieceSquareDevelopmentPhaseThreshold() {
        return getInt("pieceSquare.developmentPhaseThreshold", 64);
    }

    public int pieceSquareQueenDevelopmentPhaseThreshold() {
        return getInt("pieceSquare.queenDevelopmentPhaseThreshold", 80);
    }

    public int pieceSquareUndevelopedMinorPenalty() {
        return getInt("pieceSquare.undevelopedMinorPenalty", -20);
    }

    public int pieceSquareEarlyQueenDevelopmentPenaltyPerMinor() {
        return getInt("pieceSquare.earlyQueenDevelopmentPenaltyPerMinor", -15);
    }

    public int pieceSquareMinUndevelopedMinorsForQueenPenalty() {
        return Math.max(0, getInt("pieceSquare.minUndevelopedMinorsForQueenPenalty", 2));
    }

    public int pieceSquareStartPositionPenalty() {
        return getInt("pieceSquare.startPositionPenalty", -40);
    }

    public int pieceSquareBlendScale() {
        return Math.max(1, getInt("pieceSquare.blendScale", 256));
    }

    public int pieceSquareCastlingBonus() {
        return getInt("pieceSquare.castlingBonus", 20);
    }

    public int pieceSquareNotCastledRookMovePenalty() {
        return getInt("pieceSquare.notCastledRookMovePenalty", -10);
    }
}
