package julius.game.chessengine.nnue.train;

public record TrainingSample(String fen, int evalCp) {
    public static TrainingSample fromCsvLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 2) throw new IllegalArgumentException("Bad line: " + line);
        return new TrainingSample(parts[0], Integer.parseInt(parts[1]));
        
    }

    public static String toCsvLine(String fen, int evalCp) {
        return fen + "," + evalCp;
    }
}
