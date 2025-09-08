package julius.game.chessengine.board;

import julius.game.chessengine.helper.BishopHelper;
import julius.game.chessengine.helper.RookHelper;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

import java.util.Set;

@Log4j2
public class MagicTest {

    int totalTime = 420; //minutes

    @Test
    public void generateMagicMovesRook() {
        RookHelper rookHelper = RookHelper.getInstance();

        // Initialize and find magic numbers
        log.info("Rook Finding magic numbers...");
        rookHelper.findMagicNumbersParallel(totalTime / 2);
    }

    @Test
    public void generateMagicMovesBishop() {
        BishopHelper bishopHelper = BishopHelper.getInstance();

        // Initialize and find magic numbers
        log.info("Bishop Finding magic numbers...");
        bishopHelper.findMagicNumbersParallel(totalTime / 2);
    }

    @Test
    public void testMagicMoves() {
        RookHelper rookHelper = RookHelper.getInstance();
        BishopHelper bishopHelper = BishopHelper.getInstance();


        for (int i = 0; i < 64; i++) {
            if (bishopHelper.bishopMagics[i] != 0) {
                testBishopMovesForSquare(bishopHelper, i);
            }
        }

        for (int i = 0; i < 64; i++) {
            if (rookHelper.rookMagics[i] != 0) {
                testRookMovesForSquare(rookHelper, i);
            }
        }

    }

    private void testRookMovesForSquare(RookHelper rookHelper, int square) {
        long mask = rookHelper.generateOccupancyMask(square);
        Set<Long> occupancies = rookHelper.generateAllOccupancies(mask);
        log.info("Rook Testing square: {}", square);
        for (long occupancy : occupancies) {
            long moves = rookHelper.calculateRookMoves(square, occupancy);
            long magicMoves = calculateMovesUsingRookMagic(rookHelper, square, occupancy);
            long ownPieces = 0L; //lets assume the board is empty

            magicMoves = magicMoves & ~ownPieces;
            // Log the results for comparisom

            // Assert that the moves match
            assert moves == magicMoves : "Move generation mismatch for square " + square;
        }
    }

    private long calculateMovesUsingRookMagic(RookHelper rookHelper, int square, long occupancy) {
        //log.info(" ------------------------------------- ");
        //log.info("rook mask for square [{}]", rookHelper.rookMasks[square]);
        // Calculate the index using the magic number
        int index = rookHelper.transform(occupancy, rookHelper.rookMagics[square], rookHelper.rookMasks[square]);
        // Retrieve the moves from the rookAttacks table
        return rookHelper.rookAttacks[square][index];
    }

    private void testBishopMovesForSquare(BishopHelper bishopHelper, int square) {
        long mask = bishopHelper.generateOccupancyMask(square);
        Set<Long> occupancies = bishopHelper.generateAllOccupancies(mask);
        log.info("Bishop Testing square: {}", square);
        for (long occupancy : occupancies) {
            long moves = bishopHelper.calculateBishopMoves(square, occupancy);
            long magicMoves = calculateMovesUsingBishopMagic(bishopHelper, square, occupancy);

            long ownPieces = 0L; //lets assume the board is empty
            magicMoves = magicMoves & ~ownPieces;

/*            // Log the results for comparison
            log.info("Testing square: {}", square);
            log.info("Occupancy: {}", occupancy);
            log.info("Expected Moves: {}", moves);
            log.info("Magic Moves: {}", magicMoves);*/

            // Assert that the moves match
            assert moves == magicMoves : "Move generation mismatch for square " + square;
        }
    }

    private long calculateMovesUsingBishopMagic(BishopHelper bishopHelper, int square, long occupancy) {
        // Calculate the index using the magic number
        int index = bishopHelper.transform(occupancy, bishopHelper.bishopMagics[square], bishopHelper.bishopMasks[square]);
        // Retrieve the moves from the rookAttacks table
        return bishopHelper.bishopAttacks[square][index];
    }

}
