package julius.game.chessengine.evaluation.nn;

import julius.game.chessengine.evaluation.EvaluationContext;

import static julius.game.chessengine.helper.BitHelper.FileMasks;
import static julius.game.chessengine.helper.BitHelper.RankMasks;

/**
 * Extracts a fixed-size feature vector from an {@link EvaluationContext} for neural
 * evaluation. Features are computed directly from bitboards so no evaluation module
 * state needs to be touched.
 *
 * <p>Feature order is stable — once trained, the NN weights depend on the exact layout
 * defined in {@link #FEATURE_COUNT} and the {@code extract} method. Do not reorder.
 *
 * <p>Zero-allocation: the caller provides a {@code float[FEATURE_COUNT]} buffer that is
 * filled in place. The buffer should be reused across calls (e.g. as a thread-local).
 */
public final class FeatureExtractor {

    /**
     * Total number of features. Must match the NN input layer width.
     * Any change to the layout requires retraining the network.
     *
     * <p>Feature 70 is the classic evaluator's blended score, used as a prior
     * when the NN operates as a residual correction on top of the classic eval.
     */
    public static final int FEATURE_COUNT = 71;

    // ──────────── Normalization constants ────────────
    // Features are scaled so that typical values fall roughly into [-1, 1]. This helps
    // the NN converge faster and keeps the forward pass numerically stable.
    private static final float PIECE_COUNT_SCALE = 0.125f;     // 1 piece = 0.125 (8 pieces = 1.0)
    private static final float MATERIAL_SCALE = 1.0f / 1000.0f; // centipawns → pawn-equivalent
    private static final float MOBILITY_SCALE = 0.05f;         // 20 mobility squares ≈ 1.0
    private static final float COUNT_SCALE = 0.25f;            // generic count (4 = 1.0)
    private static final float PHASE_SCALE = 1.0f / 256.0f;    // phase is 0..256
    private static final float HALFMOVE_SCALE = 1.0f / 50.0f;  // 50-move rule scale

    // Centipawn-per-piece rough values for material totals.
    private static final int PAWN_CP = 100;
    private static final int KNIGHT_CP = 320;
    private static final int BISHOP_CP = 330;
    private static final int ROOK_CP = 500;
    private static final int QUEEN_CP = 900;

    // Center mask for center-control feature.
    private static final long CENTER_MASK = (1L << 27) | (1L << 28) | (1L << 35) | (1L << 36); // d4 e4 d5 e5

    private FeatureExtractor() {
    }

    /**
     * Fill {@code out} with {@value #FEATURE_COUNT} features derived from the given
     * evaluation context. The last feature (index 70) is set to 0; callers that have
     * a classic evaluation available should use
     * {@link #extract(EvaluationContext, float[], int)} instead.
     */
    public static void extract(EvaluationContext context, float[] out) {
        extract(context, out, 0);
    }

    /**
     * Fill {@code out} with {@value #FEATURE_COUNT} features, using the given classic
     * evaluator score as feature 70.
     *
     * @param context         source of position data
     * @param out             destination buffer
     * @param classicScoreCp  classic evaluator score in centipawns (white-perspective)
     */
    public static void extract(EvaluationContext context, float[] out, int classicScoreCp) {
        if (out.length < FEATURE_COUNT) {
            throw new IllegalArgumentException("Output buffer too small: " + out.length);
        }

        long wp = context.getWhitePawns();
        long bp = context.getBlackPawns();
        long wn = context.getWhiteKnights();
        long bn = context.getBlackKnights();
        long wb = context.getWhiteBishops();
        long bbi = context.getBlackBishops();
        long wr = context.getWhiteRooks();
        long br = context.getBlackRooks();
        long wq = context.getWhiteQueens();
        long bq = context.getBlackQueens();
        long wk = context.getWhiteKing();
        long bk = context.getBlackKing();
        long allPieces = context.getAllPieces();
        long whiteAttacks = context.getWhiteAttackMap();
        long blackAttacks = context.getBlackAttackMap();

        int wPawnCount = Long.bitCount(wp);
        int bPawnCount = Long.bitCount(bp);
        int wKnightCount = Long.bitCount(wn);
        int bKnightCount = Long.bitCount(bn);
        int wBishopCount = Long.bitCount(wb);
        int bBishopCount = Long.bitCount(bbi);
        int wRookCount = Long.bitCount(wr);
        int bRookCount = Long.bitCount(br);
        int wQueenCount = Long.bitCount(wq);
        int bQueenCount = Long.bitCount(bq);

        int i = 0;

        // ──────────── Material (12 features) ────────────
        out[i++] = (wPawnCount - bPawnCount) * PIECE_COUNT_SCALE;
        out[i++] = (wKnightCount - bKnightCount) * PIECE_COUNT_SCALE;
        out[i++] = (wBishopCount - bBishopCount) * PIECE_COUNT_SCALE;
        out[i++] = (wRookCount - bRookCount) * PIECE_COUNT_SCALE;
        out[i++] = (wQueenCount - bQueenCount) * PIECE_COUNT_SCALE;

        int whiteMaterial = wPawnCount * PAWN_CP + wKnightCount * KNIGHT_CP + wBishopCount * BISHOP_CP
                + wRookCount * ROOK_CP + wQueenCount * QUEEN_CP;
        int blackMaterial = bPawnCount * PAWN_CP + bKnightCount * KNIGHT_CP + bBishopCount * BISHOP_CP
                + bRookCount * ROOK_CP + bQueenCount * QUEEN_CP;
        out[i++] = (whiteMaterial - blackMaterial) * MATERIAL_SCALE;          // material diff
        out[i++] = (whiteMaterial + blackMaterial) * MATERIAL_SCALE * 0.5f;   // total material (phase signal)

        // Bishop pair indicators
        out[i++] = wBishopCount >= 2 ? 1.0f : 0.0f;
        out[i++] = bBishopCount >= 2 ? 1.0f : 0.0f;

        // Light/dark bishop distribution (captures opposite-color bishop endgames)
        long lightSquares = 0x55AA55AA55AA55AAL;
        out[i++] = (Long.bitCount(wb & lightSquares) - Long.bitCount(bbi & lightSquares)) * PIECE_COUNT_SCALE;
        out[i++] = (Long.bitCount(wb & ~lightSquares) - Long.bitCount(bbi & ~lightSquares)) * PIECE_COUNT_SCALE;

        // Minor piece count diff (broad "development" feature)
        out[i++] = ((wKnightCount + wBishopCount) - (bKnightCount + bBishopCount)) * PIECE_COUNT_SCALE;

        // ──────────── Pawn structure (14 features) ────────────
        int wPassed = countPassedPawns(wp, bp, true);
        int bPassed = countPassedPawns(bp, wp, false);
        out[i++] = (wPassed - bPassed) * COUNT_SCALE;

        int wDoubled = countDoubledPawns(wp);
        int bDoubled = countDoubledPawns(bp);
        out[i++] = (wDoubled - bDoubled) * COUNT_SCALE;

        int wIsolated = countIsolatedPawns(wp);
        int bIsolated = countIsolatedPawns(bp);
        out[i++] = (wIsolated - bIsolated) * COUNT_SCALE;

        int wBackward = countBackwardPawns(wp, bp, true);
        int bBackward = countBackwardPawns(bp, wp, false);
        out[i++] = (wBackward - bBackward) * COUNT_SCALE;

        int wIslands = countPawnIslands(wp);
        int bIslands = countPawnIslands(bp);
        out[i++] = (wIslands - bIslands) * COUNT_SCALE;

        // Advanced pawns (rank 5+ for white, rank 4- for black)
        long wAdvanced = wp & (RankMasks[4] | RankMasks[5] | RankMasks[6]);
        long bAdvanced = bp & (RankMasks[1] | RankMasks[2] | RankMasks[3]);
        out[i++] = (Long.bitCount(wAdvanced) - Long.bitCount(bAdvanced)) * COUNT_SCALE;

        // Very advanced passed-pawn-promotion-threat feature
        out[i++] = (Long.bitCount(wp & (RankMasks[5] | RankMasks[6]))
                - Long.bitCount(bp & (RankMasks[1] | RankMasks[2]))) * COUNT_SCALE;

        // Rook file control
        int wOpenRooks = countRooksOnFiles(wr, wp | bp, true);
        int bOpenRooks = countRooksOnFiles(br, wp | bp, true);
        out[i++] = (wOpenRooks - bOpenRooks) * COUNT_SCALE;

        int wHalfOpenRooks = countRooksOnHalfOpenFiles(wr, wp, bp);
        int bHalfOpenRooks = countRooksOnHalfOpenFiles(br, bp, wp);
        out[i++] = (wHalfOpenRooks - bHalfOpenRooks) * COUNT_SCALE;

        // Doubled rooks on same file
        out[i++] = (hasDoubledRooks(wr) ? 1.0f : 0.0f) - (hasDoubledRooks(br) ? 1.0f : 0.0f);

        // Connected passed pawns (a simplified indicator: adjacent-file passed pawns)
        out[i++] = (hasConnectedPassedPawns(wp, bp, true) ? 1.0f : 0.0f)
                - (hasConnectedPassedPawns(bp, wp, false) ? 1.0f : 0.0f);

        // Center pawn presence
        out[i++] = (Long.bitCount(wp & CENTER_MASK) - Long.bitCount(bp & CENTER_MASK)) * COUNT_SCALE;

        // Protected passed pawn count
        out[i++] = (countProtectedPassers(wp, bp, true) - countProtectedPassers(bp, wp, false)) * COUNT_SCALE;

        // Total pawn count diff (a reliable late-game signal)
        out[i++] = (wPawnCount - bPawnCount) * COUNT_SCALE;

        // ──────────── Activity / mobility (14 features) ────────────
        // Attack map coverage
        out[i++] = (Long.bitCount(whiteAttacks) - Long.bitCount(blackAttacks)) * MOBILITY_SCALE;

        // Attacks on enemy half
        long blackHalf = 0xFFFFFFFF00000000L;
        long whiteHalf = 0x00000000FFFFFFFFL;
        out[i++] = (Long.bitCount(whiteAttacks & blackHalf) - Long.bitCount(blackAttacks & whiteHalf)) * MOBILITY_SCALE;

        // Center control by attack maps
        out[i++] = (Long.bitCount(whiteAttacks & CENTER_MASK) - Long.bitCount(blackAttacks & CENTER_MASK)) * COUNT_SCALE;

        // Extended center (c3-f3-c6-f6 box)
        long extCenter = 0x00003C3C3C3C0000L;
        out[i++] = (Long.bitCount(whiteAttacks & extCenter) - Long.bitCount(blackAttacks & extCenter)) * MOBILITY_SCALE;

        // Knight outposts (knight on 4th-6th rank, protected by own pawn, not attacked by enemy pawn)
        out[i++] = (countKnightOutposts(wn, wp, bp, true) - countKnightOutposts(bn, bp, wp, false)) * COUNT_SCALE;

        // Bishops attacking long diagonals
        long longDiag = 0x8040201008040201L | 0x0102040810204080L;
        out[i++] = (Long.bitCount(wb & longDiag) - Long.bitCount(bbi & longDiag)) * COUNT_SCALE;

        // Rooks on 7th rank
        out[i++] = (Long.bitCount(wr & RankMasks[6]) - Long.bitCount(br & RankMasks[1])) * COUNT_SCALE;

        // Queen mobility (rough approximation using attack squares)
        out[i++] = (queenApproxMobility(wq, allPieces) - queenApproxMobility(bq, allPieces)) * MOBILITY_SCALE;

        // Minor pieces still on back rank (development lag)
        long wBackRank = RankMasks[0];
        long bBackRank = RankMasks[7];
        int wUndeveloped = Long.bitCount((wn | wb) & wBackRank);
        int bUndeveloped = Long.bitCount((bn | bbi) & bBackRank);
        out[i++] = (wUndeveloped - bUndeveloped) * COUNT_SCALE;

        // Developed knights (not on back rank)
        out[i++] = (Long.bitCount(wn & ~wBackRank) - Long.bitCount(bn & ~bBackRank)) * COUNT_SCALE;

        // Developed bishops
        out[i++] = (Long.bitCount(wb & ~wBackRank) - Long.bitCount(bbi & ~bBackRank)) * COUNT_SCALE;

        // Knight on rim (a/h file — "knight on the rim is dim")
        long rimFiles = FileMasks[0] | FileMasks[7];
        out[i++] = (Long.bitCount(wn & rimFiles) - Long.bitCount(bn & rimFiles)) * COUNT_SCALE;

        // Pieces attacking enemy king zone
        long wKingZone = kingZone(Long.numberOfTrailingZeros(wk));
        long bKingZone = kingZone(Long.numberOfTrailingZeros(bk));
        out[i++] = (Long.bitCount(whiteAttacks & bKingZone) - Long.bitCount(blackAttacks & wKingZone)) * COUNT_SCALE;

        // Mobility-weighted material imbalance (rough "activity score")
        out[i++] = (Long.bitCount(whiteAttacks) * 0.01f) - (Long.bitCount(blackAttacks) * 0.01f);

        // ──────────── King safety (12 features) ────────────
        int wKingSq = wk == 0 ? 0 : Long.numberOfTrailingZeros(wk);
        int bKingSq = bk == 0 ? 0 : Long.numberOfTrailingZeros(bk);

        // Pawn shield count (pawns in front of king)
        out[i++] = pawnShieldCount(wp, wKingSq, true) * COUNT_SCALE;
        out[i++] = pawnShieldCount(bp, bKingSq, false) * COUNT_SCALE;

        // Open file on king
        out[i++] = isOpenFile(wp | bp, wKingSq & 7) ? 1.0f : 0.0f;
        out[i++] = isOpenFile(wp | bp, bKingSq & 7) ? 1.0f : 0.0f;

        // Half-open file on king
        out[i++] = isHalfOpenFile(wp, bp, wKingSq & 7) ? 1.0f : 0.0f;
        out[i++] = isHalfOpenFile(bp, wp, bKingSq & 7) ? 1.0f : 0.0f;

        // Enemy attacks around king
        out[i++] = Long.bitCount(blackAttacks & wKingZone) * COUNT_SCALE;
        out[i++] = Long.bitCount(whiteAttacks & bKingZone) * COUNT_SCALE;

        // King has castled flags
        out[i++] = context.isWhiteKingHasCastled() ? 1.0f : 0.0f;
        out[i++] = context.isBlackKingHasCastled() ? 1.0f : 0.0f;

        // King on back rank (safer early, exposed late)
        out[i++] = (wKingSq >>> 3) == 0 ? 1.0f : 0.0f;
        out[i++] = (bKingSq >>> 3) == 7 ? 1.0f : 0.0f;

        // ──────────── Threats (9 features) ────────────
        // Hanging pieces (attacked by enemy, not defended by self)
        out[i++] = hangingValue(wp, blackAttacks, whiteAttacks, PAWN_CP) * MATERIAL_SCALE;
        out[i++] = hangingValue(bp, whiteAttacks, blackAttacks, PAWN_CP) * MATERIAL_SCALE;

        out[i++] = hangingValue(wn, blackAttacks, whiteAttacks, KNIGHT_CP) * MATERIAL_SCALE;
        out[i++] = hangingValue(bn, whiteAttacks, blackAttacks, KNIGHT_CP) * MATERIAL_SCALE;

        out[i++] = hangingValue(wb, blackAttacks, whiteAttacks, BISHOP_CP) * MATERIAL_SCALE;
        out[i++] = hangingValue(bbi, whiteAttacks, blackAttacks, BISHOP_CP) * MATERIAL_SCALE;

        out[i++] = hangingValue(wr, blackAttacks, whiteAttacks, ROOK_CP) * MATERIAL_SCALE;
        out[i++] = hangingValue(br, whiteAttacks, blackAttacks, ROOK_CP) * MATERIAL_SCALE;

        // Queen attacked
        int wqAttacked = (wq & blackAttacks) != 0 ? 1 : 0;
        int bqAttacked = (bq & whiteAttacks) != 0 ? 1 : 0;
        out[i++] = wqAttacked - bqAttacked;

        // ──────────── Position meta (9 features) ────────────
        out[i++] = context.getPhase() * PHASE_SCALE;                    // game phase 0..1
        out[i++] = context.isWhiteToMove() ? 1.0f : -1.0f;              // side to move

        // Castling rights: 4 booleans
        out[i++] = !context.isWhiteKingMoved() && !context.isWhiteRookH1Moved() ? 1.0f : 0.0f;
        out[i++] = !context.isWhiteKingMoved() && !context.isWhiteRookA1Moved() ? 1.0f : 0.0f;
        out[i++] = !context.isBlackKingMoved() && !context.isBlackRookH8Moved() ? 1.0f : 0.0f;
        out[i++] = !context.isBlackKingMoved() && !context.isBlackRookA8Moved() ? 1.0f : 0.0f;

        // Halfmove clock (50-move rule proximity)
        out[i++] = Math.min(50, context.getHalfmoveClock()) * HALFMOVE_SCALE;

        // Total non-king piece count (alternative phase signal)
        int nonKings = wPawnCount + bPawnCount + wKnightCount + bKnightCount
                + wBishopCount + bBishopCount + wRookCount + bRookCount
                + wQueenCount + bQueenCount;
        out[i++] = nonKings * 0.03125f; // 32 = 1.0

        // Check state (side to move in check)
        var state = context.getGameState();
        boolean whiteInCheck = state != null && state.name().equals("WHITE_IN_CHECK");
        boolean blackInCheck = state != null && state.name().equals("BLACK_IN_CHECK");
        out[i++] = (whiteInCheck ? -1.0f : 0.0f) + (blackInCheck ? 1.0f : 0.0f);

        // Feature 70: classic eval score (strong prior for the NN to correct)
        out[i++] = classicScoreCp * MATERIAL_SCALE;

        // Safety: pad out to FEATURE_COUNT in case we miscounted during iteration.
        while (i < FEATURE_COUNT) {
            out[i++] = 0.0f;
        }
    }

    // ──────────── Helper methods ────────────

    private static int countPassedPawns(long ownPawns, long enemyPawns, boolean isWhite) {
        int count = 0;
        long pawns = ownPawns;
        while (pawns != 0) {
            int sq = Long.numberOfTrailingZeros(pawns);
            pawns &= pawns - 1;
            int file = sq & 7;
            long blockingMask = adjacentFilesAndFile(file);
            long forwardRanks = isWhite ? forwardRanksWhite(sq >>> 3) : forwardRanksBlack(sq >>> 3);
            if ((enemyPawns & blockingMask & forwardRanks) == 0) {
                count++;
            }
        }
        return count;
    }

    private static int countDoubledPawns(long pawns) {
        int count = 0;
        for (int file = 0; file < 8; file++) {
            int onFile = Long.bitCount(pawns & FileMasks[file]);
            if (onFile > 1) {
                count += onFile - 1;
            }
        }
        return count;
    }

    private static int countIsolatedPawns(long pawns) {
        int count = 0;
        for (int file = 0; file < 8; file++) {
            long fileMask = pawns & FileMasks[file];
            if (fileMask == 0) continue;
            long adjacent = 0L;
            if (file > 0) adjacent |= FileMasks[file - 1];
            if (file < 7) adjacent |= FileMasks[file + 1];
            if ((pawns & adjacent) == 0) {
                count += Long.bitCount(fileMask);
            }
        }
        return count;
    }

    private static int countBackwardPawns(long ownPawns, long enemyPawns, boolean isWhite) {
        int count = 0;
        long pawns = ownPawns;
        while (pawns != 0) {
            int sq = Long.numberOfTrailingZeros(pawns);
            pawns &= pawns - 1;
            int file = sq & 7;
            int rank = sq >>> 3;
            long adjacentFiles = 0L;
            if (file > 0) adjacentFiles |= FileMasks[file - 1];
            if (file < 7) adjacentFiles |= FileMasks[file + 1];
            // "Backward" = no pawn on adjacent files at same-or-lower rank (from side's perspective).
            long backward = isWhite ? adjacentFiles & ((RankMasks[rank]) | forwardRanksBlack(rank))
                                    : adjacentFiles & ((RankMasks[rank]) | forwardRanksWhite(rank));
            if ((ownPawns & backward) == 0) {
                // Also not a passed pawn (we only count real backwards).
                long ahead = isWhite ? forwardRanksWhite(rank) : forwardRanksBlack(rank);
                if ((enemyPawns & FileMasks[file] & ahead) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countPawnIslands(long pawns) {
        if (pawns == 0) return 0;
        int islands = 0;
        boolean inIsland = false;
        for (int file = 0; file < 8; file++) {
            if ((pawns & FileMasks[file]) != 0) {
                if (!inIsland) {
                    islands++;
                    inIsland = true;
                }
            } else {
                inIsland = false;
            }
        }
        return islands;
    }

    private static int countRooksOnFiles(long rooks, long pawns, boolean openFilesOnly) {
        int count = 0;
        long r = rooks;
        while (r != 0) {
            int sq = Long.numberOfTrailingZeros(r);
            r &= r - 1;
            int file = sq & 7;
            if ((pawns & FileMasks[file]) == 0) {
                count++;
            }
        }
        return count;
    }

    private static int countRooksOnHalfOpenFiles(long rooks, long ownPawns, long enemyPawns) {
        int count = 0;
        long r = rooks;
        while (r != 0) {
            int sq = Long.numberOfTrailingZeros(r);
            r &= r - 1;
            int file = sq & 7;
            if ((ownPawns & FileMasks[file]) == 0 && (enemyPawns & FileMasks[file]) != 0) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasDoubledRooks(long rooks) {
        for (int file = 0; file < 8; file++) {
            if (Long.bitCount(rooks & FileMasks[file]) >= 2) return true;
        }
        return false;
    }

    private static boolean hasConnectedPassedPawns(long ownPawns, long enemyPawns, boolean isWhite) {
        long pawns = ownPawns;
        long connected = 0L;
        while (pawns != 0) {
            int sq = Long.numberOfTrailingZeros(pawns);
            pawns &= pawns - 1;
            int file = sq & 7;
            long blockingMask = adjacentFilesAndFile(file);
            long forwardRanks = isWhite ? forwardRanksWhite(sq >>> 3) : forwardRanksBlack(sq >>> 3);
            if ((enemyPawns & blockingMask & forwardRanks) == 0) {
                connected |= 1L << sq;
            }
        }
        // Connected = at least two passers on adjacent files
        for (int file = 0; file < 7; file++) {
            long pair = (connected & FileMasks[file]) | (connected & FileMasks[file + 1]);
            if (Long.bitCount(pair) >= 2
                    && (connected & FileMasks[file]) != 0
                    && (connected & FileMasks[file + 1]) != 0) {
                return true;
            }
        }
        return false;
    }

    private static int countProtectedPassers(long ownPawns, long enemyPawns, boolean isWhite) {
        int count = 0;
        long pawns = ownPawns;
        while (pawns != 0) {
            int sq = Long.numberOfTrailingZeros(pawns);
            pawns &= pawns - 1;
            int file = sq & 7;
            long blockingMask = adjacentFilesAndFile(file);
            long forwardRanks = isWhite ? forwardRanksWhite(sq >>> 3) : forwardRanksBlack(sq >>> 3);
            if ((enemyPawns & blockingMask & forwardRanks) != 0) continue;

            // Protected = defended by another pawn diagonally behind
            int behindRank = isWhite ? (sq >>> 3) - 1 : (sq >>> 3) + 1;
            if (behindRank < 0 || behindRank > 7) continue;
            int behindLeft = behindRank * 8 + file - 1;
            int behindRight = behindRank * 8 + file + 1;
            if (file > 0 && (ownPawns & (1L << behindLeft)) != 0) count++;
            else if (file < 7 && (ownPawns & (1L << behindRight)) != 0) count++;
        }
        return count;
    }

    private static int countKnightOutposts(long knights, long ownPawns, long enemyPawns, boolean isWhite) {
        int count = 0;
        long n = knights;
        // Outpost ranks: for White ranks 4-6 (index 3-5), for Black ranks 2-4 (index 2-4)
        long outpostMask = isWhite ? (RankMasks[3] | RankMasks[4] | RankMasks[5])
                                   : (RankMasks[2] | RankMasks[3] | RankMasks[4]);
        while (n != 0) {
            int sq = Long.numberOfTrailingZeros(n);
            n &= n - 1;
            if (((1L << sq) & outpostMask) == 0) continue;
            int file = sq & 7;
            int rank = sq >>> 3;
            // Check no enemy pawn can attack this square.
            int attackerRank = isWhite ? rank + 1 : rank - 1;
            if (attackerRank < 0 || attackerRank > 7) continue;
            long attackerSquares = 0L;
            if (file > 0) attackerSquares |= 1L << (attackerRank * 8 + file - 1);
            if (file < 7) attackerSquares |= 1L << (attackerRank * 8 + file + 1);
            long enemyPawnsOnForwardFiles = 0L;
            long forwardFiles = 0L;
            if (file > 0) forwardFiles |= FileMasks[file - 1];
            if (file < 7) forwardFiles |= FileMasks[file + 1];
            long aheadEnemy = isWhite ? (forwardFiles & forwardRanksWhite(rank))
                                      : (forwardFiles & forwardRanksBlack(rank));
            if ((enemyPawns & aheadEnemy) != 0) continue;
            count++;
        }
        return count;
    }

    private static int queenApproxMobility(long queens, long allPieces) {
        if (queens == 0) return 0;
        // Approximate queen mobility as number of empty squares adjacent along ray directions (cheap).
        int total = 0;
        long q = queens;
        while (q != 0) {
            int sq = Long.numberOfTrailingZeros(q);
            q &= q - 1;
            int file = sq & 7;
            int rank = sq >>> 3;
            int[] df = {-1, -1, -1, 0, 0, 1, 1, 1};
            int[] dr = {-1, 0, 1, -1, 1, -1, 0, 1};
            for (int d = 0; d < 8; d++) {
                int f = file + df[d];
                int r = rank + dr[d];
                int steps = 0;
                while (f >= 0 && f < 8 && r >= 0 && r < 8 && steps < 3) {
                    int s = r * 8 + f;
                    if ((allPieces & (1L << s)) != 0) break;
                    total++;
                    f += df[d];
                    r += dr[d];
                    steps++;
                }
            }
        }
        return total;
    }

    private static long kingZone(int kingSquare) {
        int file = kingSquare & 7;
        int rank = kingSquare >>> 3;
        long zone = 0L;
        for (int df = -1; df <= 1; df++) {
            for (int dr = -1; dr <= 1; dr++) {
                int f = file + df;
                int r = rank + dr;
                if (f >= 0 && f < 8 && r >= 0 && r < 8) {
                    zone |= 1L << (r * 8 + f);
                }
            }
        }
        return zone;
    }

    private static int pawnShieldCount(long ownPawns, int kingSquare, boolean isWhite) {
        int file = kingSquare & 7;
        int rank = kingSquare >>> 3;
        int shield = 0;
        int targetRank = isWhite ? rank + 1 : rank - 1;
        if (targetRank < 0 || targetRank > 7) return 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            if ((ownPawns & (1L << (targetRank * 8 + f))) != 0) shield++;
        }
        return shield;
    }

    private static boolean isOpenFile(long allPawns, int file) {
        return (allPawns & FileMasks[file]) == 0;
    }

    private static boolean isHalfOpenFile(long ownPawns, long enemyPawns, int file) {
        return (ownPawns & FileMasks[file]) == 0 && (enemyPawns & FileMasks[file]) != 0;
    }

    private static int hangingValue(long pieces, long enemyAttacks, long ownAttacks, int pieceValue) {
        long attacked = pieces & enemyAttacks;
        long undefended = attacked & ~ownAttacks;
        return Long.bitCount(undefended) * pieceValue;
    }

    private static long adjacentFilesAndFile(int file) {
        long mask = FileMasks[file];
        if (file > 0) mask |= FileMasks[file - 1];
        if (file < 7) mask |= FileMasks[file + 1];
        return mask;
    }

    private static long forwardRanksWhite(int rankFromZero) {
        long mask = 0L;
        for (int r = rankFromZero + 1; r < 8; r++) {
            mask |= RankMasks[r];
        }
        return mask;
    }

    private static long forwardRanksBlack(int rankFromZero) {
        long mask = 0L;
        for (int r = rankFromZero - 1; r >= 0; r--) {
            mask |= RankMasks[r];
        }
        return mask;
    }
}
