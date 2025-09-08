package julius.game.chessengine.board;

import julius.game.chessengine.engine.Engine;
import julius.game.chessengine.utils.Color;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

import static julius.game.chessengine.board.MoveHelper.convertStringToIndex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Log4j2
public class BitBoardTest {

    final long[] RankMasks = new long[]{
            0x00000000000000FFL, // Rank 1
            0x000000000000FF00L, // Rank 2
            0x0000000000FF0000L, // Rank 3
            0x00000000FF000000L, // Rank 4
            0x000000FF00000000L, // Rank 5
            0x0000FF0000000000L, // Rank 6
            0x00FF000000000000L, // Rank 7
            0xFF00000000000000L  // Rank 8
    };

    final long[] FileMasks = new long[]{
            0x0101010101010101L, // File A
            0x0202020202020202L, // File B
            0x0404040404040404L, // File C
            0x0808080808080808L, // File D
            0x1010101010101010L, // File E
            0x2020202020202020L, // File F
            0x4040404040404040L, // File G
            0x8080808080808080L  // File H
    };


    @Test
    public void HashTest() {
        Engine a = new Engine();
        Engine b = a.createSimulation();

        a.moveRandomFigure(true);
        a.undoLastMove();

        a.logBoard();
        b.logBoard();

        assertEquals(a.getBoardStateHash(), b.getBoardStateHash());
    }

    @Test
    public void PERFT() {
        long startTime = System.nanoTime(); // Start timing

        Engine engine = new Engine(); // The chess engine

        // Depth 1
        PerftNode d1 = perft(1, engine);
        assertEquals(0, d1.getCaptures());
        assertEquals(0, d1.getEnPassant());
        assertEquals(0, d1.getCastles());
        assertEquals(0, d1.getPromotions());
        assertEquals(0, d1.getChecks());
        assertEquals(0, d1.getCheckmates());
        assertEquals(20, d1.getNodes());

        // Depth 2
        PerftNode d2 = perft(2, engine);
        assertEquals(0, d2.getCaptures());
        assertEquals(0, d2.getEnPassant());
        assertEquals(0, d2.getCastles());
        assertEquals(0, d2.getPromotions());
        assertEquals(0, d2.getChecks());
        assertEquals(0, d2.getCheckmates());
        assertEquals(400, d2.getNodes());

        // Depth 3
        PerftNode d3 = perft(3, engine);
        assertEquals(34, d3.getCaptures());
        assertEquals(0, d3.getEnPassant());
        assertEquals(0, d3.getCastles());
        assertEquals(0, d3.getPromotions());
        assertEquals(12, d3.getChecks());
        assertEquals(0, d3.getCheckmates());
        assertEquals(8902, d3.getNodes());

        // Depth 4
        PerftNode d4 = perft(4, engine);
        assertEquals(197281, d4.getNodes());
        assertEquals(8, d4.getCheckmates());
        assertEquals(469, d4.getChecks());
        assertEquals(1576, d4.getCaptures());
        assertEquals(0, d4.getEnPassant());
        assertEquals(0, d4.getCastles());
        assertEquals(0, d4.getPromotions());

        long endTime = System.nanoTime();
        log.info("(1-4) Time taken for move calculation: {} ms", (endTime - startTime) / 1e6);

        // Depth 5
        PerftNode d5 = perft(5, engine);
        assertEquals(4865609, d5.getNodes());
        assertEquals(347, d5.getCheckmates());
        assertEquals(27351, d5.getChecks());
        assertEquals(82719, d5.getCaptures());
        assertEquals(258, d5.getEnPassant());
        assertEquals(0, d5.getCastles());
        assertEquals(0, d5.getPromotions());

        endTime = System.nanoTime();
        log.info("(1-5) Time taken for move calculation: {} ms", (endTime - startTime) / 1e6);

        // Depth 6
        PerftNode d6 = perft(6, engine);
        assertEquals(119060324, d6.getNodes());
        assertEquals(10828, d6.getCheckmates());
        assertEquals(809099, d6.getChecks());
        assertEquals(2812008, d6.getCaptures());
        assertEquals(5248, d6.getEnPassant());
        assertEquals(0, d6.getCastles());
        assertEquals(0, d6.getPromotions());

        endTime = System.nanoTime();
        log.info("(1-6) Time taken for move calculation: {} ms", (endTime - startTime) / 1e6);
    }

    @Test
//    r . . . k . . r   8
//    p . p p q p b .   7
//    b n . . p n p .   6
//    . . . P N . . .   5
//    . p . . P . . .   4
//    . . N . . Q . p   3
//    P P P B B P P P   2
//    R . . . K . . R   1
//    a b c d e f g h
    //https://www.chessprogramming.org/Perft_Results
    public void PERFTPos_2() {

        long startTime = System.nanoTime(); // Start timing

        Engine engine = new Engine();
        engine.importBoardFromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -");// The chess engine

        // Depth 1
        PerftNode d1 = perft(1, engine);
        assertEquals(48, d1.getNodes());
        assertEquals(8, d1.getCaptures());
        assertEquals(0, d1.getEnPassant());
        assertEquals(2, d1.getCastles());
        assertEquals(0, d1.getPromotions());
        assertEquals(0, d1.getChecks());
        assertEquals(0, d1.getCheckmates());

        // Depth 2
        PerftNode d2 = perft(2, engine);
        assertEquals(3, d2.getChecks());
        assertEquals(1, d2.getEnPassant());
        assertEquals(351, d2.getCaptures());
        assertEquals(91, d2.getCastles());
        assertEquals(0, d2.getPromotions());
        assertEquals(0, d2.getCheckmates());
        assertEquals(2039, d2.getNodes());

        // Depth 3
        PerftNode d3 = perft(3, engine);
        assertEquals(45, d3.getEnPassant());
        assertEquals(3162, d3.getCastles());
        assertEquals(993, d3.getChecks());
        assertEquals(1, d3.getCheckmates());
        assertEquals(97862, d3.getNodes());
        assertEquals(0, d3.getPromotions());
        assertEquals(17102, d3.getCaptures());

        // Depth 4
        PerftNode d4 = perft(4, engine);
        assertEquals(43, d4.getCheckmates());
        assertEquals(1929, d4.getEnPassant());
        assertEquals(15172, d4.getPromotions());
        assertEquals(25523, d4.getChecks());
        assertEquals(128013, d4.getCastles());
        assertEquals(757163, d4.getCaptures());
        assertEquals(4085603, d4.getNodes());

        long endTime = System.nanoTime();
        log.info("(1-4) Time taken for move calculation: {} ms", (endTime - startTime) / 1e6);

/*        PerftNode d5 = perft(5, engine);
        assertEquals(30171, d5.getCheckmates());
        assertEquals(73365, d5.getEnPassant());
        assertEquals(8392, d5.getPromotions());
        assertEquals(3309887, d5.getChecks());
        assertEquals(4993637, d5.getCastles());
        assertEquals(35043416, d5.getCaptures());
        assertEquals(193690690, d5.getNodes());

        endTime = System.nanoTime();
        log.info("(1-5) Time taken for move calculation: {} ms", (endTime - startTime) / 1e6);*/

    }

    @Test
    public void PERFTPos_3() {

        Engine engine = new Engine();
        engine.importBoardFromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - ");// The chess engine

        // Depth 1
        PerftNode d1 = perft(1, engine);
        assertEquals(2, d1.getChecks());
        assertEquals(14, d1.getNodes());
        assertEquals(1, d1.getCaptures());
        assertEquals(0, d1.getEnPassant());
        assertEquals(0, d1.getCastles());
        assertEquals(0, d1.getPromotions());
        assertEquals(0, d1.getCheckmates());

        // Depth 2
        PerftNode d2 = perft(2, engine);
        assertEquals(10, d2.getChecks());
        assertEquals(14, d2.getCaptures());
        assertEquals(191, d2.getNodes());
        assertEquals(0, d2.getEnPassant());
        assertEquals(0, d2.getCastles());
        assertEquals(0, d2.getPromotions());
        assertEquals(0, d2.getCheckmates());

        // Depth 3
        PerftNode d3 = perft(3, engine);
        assertEquals(267, d3.getChecks());
        assertEquals(209, d3.getCaptures());
        assertEquals(2, d3.getEnPassant());
        assertEquals(0, d3.getCastles());
        assertEquals(0, d3.getPromotions());
        assertEquals(0, d3.getCheckmates());
        assertEquals(2812, d3.getNodes());

        // Depth 4
        PerftNode d4 = perft(4, engine);
        assertEquals(17, d4.getCheckmates());
        assertEquals(43238, d4.getNodes());
        assertEquals(3348, d4.getCaptures());
        assertEquals(123, d4.getEnPassant());
        assertEquals(0, d4.getCastles());
        assertEquals(0, d4.getPromotions());
        assertEquals(1680, d4.getChecks());
    }

    @Test
    public void PERFTPos_4() {

        Engine engine = new Engine();
        engine.importBoardFromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1");// The chess engine

        // Depth 1
        PerftNode d1 = perft(1, engine);
        assertEquals(6, d1.getNodes());
        assertEquals(0, d1.getCaptures());
        assertEquals(0, d1.getEnPassant());
        assertEquals(0, d1.getCastles());
        assertEquals(0, d1.getPromotions());
        assertEquals(0, d1.getChecks());
        assertEquals(0, d1.getCheckmates());

        // Depth 2
        PerftNode d2 = perft(2, engine);
        assertEquals(6, d2.getCastles());
        assertEquals(264, d2.getNodes());
        assertEquals(87, d2.getCaptures());
        assertEquals(0, d2.getEnPassant());
        assertEquals(48, d2.getPromotions());
        assertEquals(10, d2.getChecks());
        assertEquals(0, d2.getCheckmates());

        // Depth 3
        PerftNode d3 = perft(3, engine);
        assertEquals(1021, d3.getCaptures());
        assertEquals(4, d3.getEnPassant());
        assertEquals(0, d3.getCastles());
        assertEquals(120, d3.getPromotions());
        assertEquals(38, d3.getChecks());
        assertEquals(22, d3.getCheckmates());
        assertEquals(9467, d3.getNodes());

        // Depth 4
        PerftNode d4 = perft(4, engine);
        assertEquals(422333, d4.getNodes());
        assertEquals(131393, d4.getCaptures());
        assertEquals(0, d4.getEnPassant());
        assertEquals(7795, d4.getCastles());
        assertEquals(60032, d4.getPromotions());
        assertEquals(15492, d4.getChecks());
        assertEquals(5, d4.getCheckmates());

    }

    //https://www.chessprogramming.net/perfect-perft/
    @Test
    public void PERFT_castling_rights() {

        Engine engine = new Engine();
        engine.importBoardFromFen("r3k2r/1b4bq/8/8/8/8/7B/R3K2R w KQkq - 0 1");// The chess engine

        // Depth 4
        PerftNode d4 = perft(4, engine);
        assertEquals(1274206, d4.getNodes());

    }

    private PerftNode perft(int depth, Engine engine) {
        return perft(depth, engine, -1);
    }


    private PerftNode perft(int depth, Engine engine, int lastMove) {
        int specialProperty = (lastMove >> 16) & 0x03; // Extract the next 2 bits
        boolean isCapture = (specialProperty & 0x01) != 0;
        boolean isEnPassantMove = specialProperty == 3;
        boolean isCastlingMove = specialProperty == 2;

        int promotionPieceTypeBits = (lastMove >> 18) & 0x07; // Extract the next 3 bits
        PerftNode node = new PerftNode(depth);

        if (depth == 0) {
            node.addNode(); // Increment the node count at the leaf
            if (lastMove != -1) {
                if (isCapture) {
                    node.addCaptures(1);
                }
                if (isEnPassantMove) {
                    node.addEnPassant(1);
                }
                if (isCastlingMove) {
                    node.addCastle(1);
                }
                if (promotionPieceTypeBits != 0) {
                    node.addPromotion(1);
                }
                if (engine.getGameState().isInStateCheck() || engine.getGameState().isInStateCheckMate()) {
                    node.addCheck(1);
                }
                if (engine.getGameState().isInStateCheckMate()) {
                    node.addCheckmate(1);
                }
            }
            return node;
        }

        MoveList moves = engine.getAllLegalMoves();
        for (int i = 0; i < moves.size(); i++) {
            int move = moves.getMove(i);
            engine.performMove(move);

            PerftNode childNode = perft(depth - 1, engine, move); // Pass the current move as lastMove
            node.addNodes(childNode.getNodes()); // Aggregate the count of nodes from child nodes
            node.addCaptures(childNode.getCaptures());
            node.addCastle(childNode.getCastles());
            node.addCheck(childNode.getChecks());
            node.addCheckmate(childNode.getCheckmates());
            node.addEnPassant(childNode.getEnPassant());
            node.addPromotion(childNode.getPromotions());

            engine.undoLastMove();
        }

        return node;
    }

    @Test
    public void checkForEngine() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("e2"), convertStringToIndex("e4"));

        Engine simulation = engine.createSimulation();

        simulation.moveFigure(convertStringToIndex("e7"), convertStringToIndex("e5"));
        simulation.moveFigure(convertStringToIndex("g1"), convertStringToIndex("f3"));
        simulation.moveFigure(convertStringToIndex("g8"), convertStringToIndex("f6"));
        simulation.moveFigure(convertStringToIndex("b1"), convertStringToIndex("c3"));
        simulation.moveFigure(convertStringToIndex("b8"), convertStringToIndex("c6"));
        simulation.moveFigure(convertStringToIndex("f1"), convertStringToIndex("c4"));
        simulation.moveFigure(convertStringToIndex("f6"), convertStringToIndex("d5"));

        MoveList moves = simulation.getAllLegalMoves();

        assertEquals(moves.size(), 35);

        simulation.undoLastMove();
        simulation.undoLastMove();
        simulation.undoLastMove();
        simulation.undoLastMove();
        simulation.undoLastMove();
        simulation.undoLastMove();
        simulation.undoLastMove();

        engine.logBoard();
        simulation.logBoard();

        assertEquals(engine.getBoardStateHash(), simulation.getBoardStateHash());


    }

/*    @Test
    public void checkRookFirstMove() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("g1"), convertStringToIndex("f3"));
        engine.moveFigure(convertStringToIndex("g8"), convertStringToIndex("f6"));
        engine.moveFigure(convertStringToIndex("h1"), convertStringToIndex("g1"));
        engine.undoLastMove();

        MoveList moves = engine.getAllLegalMoves();

        assertEquals(moves.stream().filter(Move::isRookFirstMove).toList().size(), 1);
    }*/

    @Test
    public void checkForEnPassantWhiteLeft() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("e2"), convertStringToIndex("e4"));
        engine.moveFigure(convertStringToIndex("h7"), convertStringToIndex("h6"));
        engine.moveFigure(convertStringToIndex("e4"), convertStringToIndex("e5"));
        engine.moveFigure(convertStringToIndex("d7"), convertStringToIndex("d5"));

        MoveList moves = engine.getAllLegalMoves();

        engine.logBoard();
        assertEquals(31, moves.size());
    }

    @Test
    public void checkForEnPassantWhiteRight() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("e2"), convertStringToIndex("e4"));
        engine.moveFigure(convertStringToIndex("h7"), convertStringToIndex("h6"));
        engine.moveFigure(convertStringToIndex("e4"), convertStringToIndex("e5"));
        engine.moveFigure(convertStringToIndex("f7"), convertStringToIndex("f5"));


        engine.logBoard();
        MoveList mightNotLegalMoves = engine.getAllLegalMoves();

        assertEquals(31, mightNotLegalMoves.size());
    }

    @Test
    public void checkForEnPassantBlackLeft() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("h2"), convertStringToIndex("h3"));
        engine.moveFigure(convertStringToIndex("e7"), convertStringToIndex("e5"));
        engine.moveFigure(convertStringToIndex("h3"), convertStringToIndex("h4"));
        engine.moveFigure(convertStringToIndex("e5"), convertStringToIndex("e4"));
        engine.moveFigure(convertStringToIndex("f2"), convertStringToIndex("f4"));

        MoveList moves = engine.getAllLegalMoves();

        engine.logBoard();
        assertEquals(31, moves.size());
    }

    @Test
    public void compareHashes() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("h2"), convertStringToIndex("h3"));
        engine.moveFigure(convertStringToIndex("h7"), convertStringToIndex("h5"));
        engine.moveFigure(convertStringToIndex("h3"), convertStringToIndex("h4"));


        Engine engine2 = new Engine();

        engine2.moveFigure(convertStringToIndex("h2"), convertStringToIndex("h4"));
        engine2.moveFigure(convertStringToIndex("h7"), convertStringToIndex("h5"));


        assertNotEquals(engine.getBoardStateHash(), engine2.getBoardStateHash());
    }

    @Test
    public void checkForEnPassantBlackRight() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("h2"), convertStringToIndex("h3"));
        engine.moveFigure(convertStringToIndex("e7"), convertStringToIndex("e5"));
        engine.moveFigure(convertStringToIndex("h3"), convertStringToIndex("h4"));
        engine.moveFigure(convertStringToIndex("e5"), convertStringToIndex("e4"));
        engine.moveFigure(convertStringToIndex("d2"), convertStringToIndex("d4"));

        MoveList moves = engine.getAllLegalMoves();

        engine.logBoard();
        assertEquals(31, moves.size());
    }


    @Test
    public void checkForCapturesBlack() {
        Engine engine = new Engine(); // The chess engine

        engine.moveFigure(convertStringToIndex("e2"), convertStringToIndex("e4"));
        engine.moveFigure(convertStringToIndex("f7"), convertStringToIndex("f5"));
        engine.moveFigure(convertStringToIndex("g2"), convertStringToIndex("g4"));

        MoveList moves = engine.getAllLegalMoves();

        engine.logBoard();
        assertEquals(22, moves.size());
    }

    @Test
    public void testMoveGeneration() {
        BitBoard b = new BitBoard();
        b.performMove(38668); //e4
        b.performMove(6387);  //d5
        assertEquals("[-1946119160]:a3 [-1946119095]:b3 [-1946119030]:c3 [-1946118965]:d3 [-1946118835]:f3 [-1946118770]:g3 [-1946118705]:h3 [-1946117860]:e5 [-1946118648]:a4 [-1946118583]:b4 [-1946118518]:c4 [-1946118453]:d4 [-1946118323]:f4 [-1946118258]:g4 [-1946118193]:h4 [-1943955236]:exd5 [-1946115071]:Na3 [-1946114943]:Nc3 [-1946115322]:Ne2 [-1946114746]:Nf3 [-1946114618]:Nh3 [-1946111227]:Be2 [-1946110779]:Bd3 [-1946110331]:Bc4 [-1946109883]:Bb5 [-1946109435]:Ba6 [-1946103037]:Qe2 [-1946102461]:Qf3 [-1946101885]:Qg4 [-1946101309]:Qh5 [-1929321724]:Ke2 ", b.getAllCurrentPossibleMoves().toString());
    }

    @Test
    public void testPawnMovesWithBitshifting() {
        long whitePawns;
        long blackPawns;

        // Setting white pawns on the second rank
        whitePawns = 0x000000000000FF00L;
        // Setting black pawns on the seventh rank
        blackPawns = 0x00FF000000000000L;

        // Setting white pawns on the second rank
        // Move white pawns up the board by 1 rank using bitshifting
        whitePawns = whitePawns << 2;
        // Move black pawns down the board by 1 rank using bitshifting
        blackPawns = blackPawns >> 2;

        // Print out the bitboards in a human-readable form
        printBitboard(whitePawns);
        printBitboard(blackPawns);
    }

    @Test
    public void testSinglePawnDoubleStepWithBitshifting() {
        // All white pawns are on the second rank
        long whitePawns = 0x000000000000FF00L;
        log.info(whitePawns);

        printBitboard(whitePawns);

        // Mask for the e2 square, which we want to clear
        long eFileMask = 0x1010101010101010L;
        // Clear the e2 square by using bitwise AND with the inverse of the e2 mask
        whitePawns &= ~eFileMask;

        log.info(whitePawns);

        printBitboard(whitePawns);

        // Mask for the e4 square, where we want to move our pawn
        long e4Mask = 1L << 28;
        // Set the e4 square by using bitwise OR with the e4 mask
        whitePawns |= e4Mask;

        log.info(whitePawns);

        // Print out the bitboard in a human-readable form
        printBitboard(whitePawns);

        final long Rank4 = 0x00000000FF000000L;

        long doubleMove = whitePawns & Rank4;

        printBitboard(doubleMove);
    }

    @Test
    public void testEnPassant() {
        // All white pawns are on the second rank
        long whitePawns = 0x000000000000FF00L;
        long blackPawns = 0x00FF000000000000L;


        printBitboard(whitePawns);

        whitePawns = movePawn(2, Color.WHITE, whitePawns, 'e', false, false);
        whitePawns = movePawn(1, Color.WHITE, whitePawns, 'e', false, false);
        blackPawns = movePawn(2, Color.BLACK, blackPawns, 'd', false, false);

        printBitboard(whitePawns | blackPawns);

        blackPawns = removePawnFromPosition(new Position('d', 5), blackPawns);
        whitePawns = movePawn(1, Color.WHITE, whitePawns, 'e', true, false);

        printBitboard(blackPawns | whitePawns);

    }

    @Test
    public void testEnPassant2() {
        // All white pawns are on the second rank
        long whitePawns = 0x000000000000FF00L;
        long blackPawns = 0x00FF000000000000L;


        printBitboard(whitePawns);

        whitePawns = movePawn(2, Color.WHITE, whitePawns, 'e', false, false);
        whitePawns = movePawn(1, Color.WHITE, whitePawns, 'e', false, false);
        blackPawns = movePawn(2, Color.BLACK, blackPawns, 'd', false, false);

        testing(whitePawns | blackPawns);

    }

    private void testing(long pawns) {
        Position lastMoveDoubleStepPawnPosition = new Position('d', 5);


        int enPassantRank = 5; // For white, the en passant rank is 6 (5 in 0-based index); for black, it's 3 (2 in 0-based index).
        int fileIndexOfDoubleSteppedPawn = lastMoveDoubleStepPawnPosition.getX() - 'a';
        int enPassantIndex = (enPassantRank * 8) + fileIndexOfDoubleSteppedPawn;
        long enPassantTargetSquare = 1L << enPassantIndex;

        // Calculate potential en passant capture moves to the left and right
        long attacksLeftEnPassant = (pawns & ~FileMasks[7]) >>> 1;
        long attacksRightEnPassant = ~FileMasks[0] << 1;

        //long attacksLeftEnPassant = 0x800003780L;
        //long attacksRightEnPassant = 0x200001dc00L;

        log.info("EnPassantTargetSquare before masking: {}", "0x" + Long.toHexString(enPassantTargetSquare));

        log.info("Attacks left before masking: {}", "0x" + Long.toHexString(attacksLeftEnPassant));
        log.info("Attacks right before masking: {}", "0x" + Long.toHexString(attacksRightEnPassant));

        // Filter out captures that don't have an opponent pawn in the en passant target square
        long validEnPassantCapturesLeft = attacksLeftEnPassant & enPassantTargetSquare;
        long validEnPassantCapturesRight = attacksRightEnPassant & enPassantTargetSquare;

        if (validEnPassantCapturesLeft != 0) {
            log.info("LEFT");
            printBitboard(validEnPassantCapturesLeft);
        }

        if (validEnPassantCapturesRight != 0) {
            log.info("RIGHT");
            printBitboard(validEnPassantCapturesRight);
        }
    }

    private long movePawn(int amountOfFields, Color color, long pawns, char file, boolean isCaptureToTheLeft, boolean isCaptureToTheRight) {
        int capture = 0;

        if (isCaptureToTheLeft && isCaptureToTheRight) {
            throw new IllegalStateException("Pawns cannot capture left and right!");
        }

        if (isCaptureToTheLeft) {
            capture = Color.WHITE == color ? -1 : 1;
        }

        if (isCaptureToTheRight) {
            capture = Color.WHITE == color ? 1 : -1;
        }

        return Color.WHITE == color ?
                (pawns & ~FileMasks[file - 'a']) | (pawns & FileMasks[file - 'a']) << 8 * amountOfFields + capture :
                (pawns & ~FileMasks[file - 'a']) | (pawns & FileMasks[file - 'a']) >> 8 * amountOfFields + capture;
    }

    private long removePawnFromPosition(Position position, long pawns) {
        // Create a mask that represents the position to remove
        long mask = FileMasks[position.getX() - 'a'] & RankMasks[position.getY() - 1];
        // Flip the bits of the mask to have 0 at the position to remove and 1s elsewhere
        mask = ~mask;
        // Use bitwise AND to clear the bit at the specified position
        return pawns & mask;
    }


    public void printBitboard(long bitboard) {
        for (int rank = 8; rank >= 1; rank--) {
            for (int file = 1; file <= 8; file++) {
                long mask = 1L << ((rank - 1) * 8 + (file - 1));
                if ((bitboard & mask) != 0) {
                    System.out.print("P ");
                } else {
                    System.out.print(". ");
                }
            }
        }
    }


}

