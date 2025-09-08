package julius.game.chessengine.board;

public class PerftNode {
    private int depth;
    private long nodes;
    private int captures;
    private int enPassant;
    private int castles;
    private int promotions;
    private int checks;
    private int checkmates;

    // Constructor, getters and setters
    public PerftNode(int depth) {
        this.depth = depth;
        this.nodes = 0;
        this.captures = 0;
        this.enPassant = 0;
        this.castles = 0;
        this.promotions = 0;
        this.checks = 0;
        this.checkmates = 0;
    }

    // Other methods to update the properties as needed
    public void addNode() {
        this.nodes++;
    }

    public void addCapture() {
        this.captures++;
    }

    public void addEnPassant(int i) {
        this.enPassant = this.enPassant + i;
    }

    public void addCastle(int i) {
        this.castles = this.castles + i;
    }

    public void addPromotion(int i) {
        this.promotions = this.promotions + i;
    }

    public void addCheck(int i) {
        this.checks = this.checks + i;
    }

    public void addCheckmate(int i) {
        this.checkmates = this.checkmates + i;
    }

    public int getCaptures() {
        return this.captures;
    }

    public void addCaptures(int i) {
        this.captures = this.captures + i;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public long getNodes() {
        return nodes;
    }

    public void setNodes(long nodes) {
        this.nodes = nodes;
    }

    public void setCaptures(int captures) {
        this.captures = captures;
    }

    public int getEnPassant() {
        return enPassant;
    }

    public void setEnPassant(int enPassant) {
        this.enPassant = enPassant;
    }

    public int getCastles() {
        return castles;
    }

    public void setCastles(int castles) {
        this.castles = castles;
    }

    public int getPromotions() {
        return promotions;
    }

    public void setPromotions(int promotions) {
        this.promotions = promotions;
    }

    public int getChecks() {
        return checks;
    }

    public void setChecks(int checks) {
        this.checks = checks;
    }

    public int getCheckmates() {
        return checkmates;
    }

    public void setCheckmates(int checkmates) {
        this.checkmates = checkmates;
    }

    public void addNodes(long nodes) {
        this.nodes = this.nodes + nodes;
    }

}