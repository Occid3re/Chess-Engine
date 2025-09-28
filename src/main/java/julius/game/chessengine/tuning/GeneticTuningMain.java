package julius.game.chessengine.tuning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line entry point that runs the {@link GeneticOptimizer} against a seed YAML file and
 * persists the evolved population back to disk.
 */
public final class GeneticTuningMain {

    public static void main(String[] args) throws Exception {
        CliArguments cli;
        try {
            cli = CliArguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        if (cli.helpRequested) {
            printUsage();
            return;
        }

        EngineTuningSet seed;
        try {
            seed = EngineTuningLoader.load(cli.seedFile);
        } catch (IOException e) {
            System.err.printf("Failed to load seed population from %s: %s%n", cli.seedFile, e.getMessage());
            return;
        }

        if (seed.isEmpty()) {
            System.err.printf("Seed file %s did not contain any population entries.%n", cli.seedFile);
            return;
        }

        GeneticOptions options = cli.toOptions();
        GeneticOptimizer optimizer = new GeneticOptimizer();

        System.out.printf("Running %d generations with population size %d (retain %d, mutation %.2f).%n",
                options.generations(), options.populationSize(), options.retainCount(), options.mutationStrength());
        Instant start = Instant.now();
        GeneticOptimizer.GeneticResult result = optimizer.evolve(seed, options);
        Duration duration = Duration.between(start, Instant.now());

        try {
            EngineTuningWriter.write(result.population(), cli.outputFile);
        } catch (IOException e) {
            System.err.printf("Failed to write evolved population to %s: %s%n", cli.outputFile, e.getMessage());
            return;
        }

        if (cli.matchLogFile != null) {
            try {
                writeMatchLog(result.matches(), cli.matchLogFile);
            } catch (IOException e) {
                System.err.printf("Failed to write match log to %s: %s%n", cli.matchLogFile, e.getMessage());
            }
        }

        System.out.printf("Evolution completed in %d seconds.%n", Math.max(1, duration.getSeconds()));
        System.out.printf("Saved %d configurations to %s%n", result.population().population().size(), cli.outputFile.toAbsolutePath());
        printLeaderboard(result.matches());
    }

    private static void printLeaderboard(List<MatchRunner.MatchResult> matches) {
        if (matches.isEmpty()) {
            System.out.println("No matches were played.");
            return;
        }

        Map<String, Double> scoreboard = new HashMap<>();
        matches.forEach(match -> {
            scoreboard.merge(match.whiteTuning().name(), match.whiteScore(), Double::sum);
            scoreboard.merge(match.blackTuning().name(), match.blackScore(), Double::sum);
        });

        System.out.println("Top performers (higher score is better):");
        scoreboard.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .forEach(entry -> System.out.printf("  %-24s %.2f%n", entry.getKey(), entry.getValue()));
    }

    private static void writeMatchLog(List<MatchRunner.MatchResult> matches, Path destination) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try (var writer = Files.newBufferedWriter(destination)) {
            writer.write("white,black,whiteScore,blackScore,result,plies\n");
            for (MatchRunner.MatchResult match : matches) {
                writer.write(String.format(Locale.ROOT,
                        "%s,%s,%.2f,%.2f,%s,%d%n",
                        match.whiteTuning().name(),
                        match.blackTuning().name(),
                        match.whiteScore(),
                        match.blackScore(),
                        match.finalState(),
                        match.plies()));
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage: GeneticTuningMain --seed <file> [options]\n" +
                "Options:\n" +
                "  --output <file>        Destination YAML file (defaults to <seed>-evolved.yaml).\n" +
                "  --matches <file>       Optional CSV log of every self-play match.\n" +
                "  --generations <n>      Number of generations to simulate (default 5).\n" +
                "  --retain <n>           Top performers carried over to the next generation (default 4).\n" +
                "  --population <n>       Number of configurations in each generation (default 8).\n" +
                "  --matches-per-pair <n> Matches played between every pair per generation (default 2).\n" +
                "  --mutation <value>     Mutation strength in the [0, 1] range (default 0.15).\n" +
                "  --move-time <ms>       Move time in milliseconds for each search (default 50).\n" +
                "  --max-plies <n>        Hard cap on plies per game (default 512).\n" +
                "  -h, --help             Display this help message.\n");
    }

    private static final class CliArguments {
        private final Path seedFile;
        private final Path outputFile;
        private final Path matchLogFile;
        private final boolean helpRequested;
        private final int generations;
        private final int retain;
        private final int population;
        private final int matchesPerPair;
        private final double mutationStrength;
        private final long moveTimeMillis;
        private final int maxPlies;

        private CliArguments(Path seedFile,
                              Path outputFile,
                              Path matchLogFile,
                              boolean helpRequested,
                              int generations,
                              int retain,
                              int population,
                              int matchesPerPair,
                              double mutationStrength,
                              long moveTimeMillis,
                              int maxPlies) {
            this.seedFile = seedFile;
            this.outputFile = outputFile;
            this.matchLogFile = matchLogFile;
            this.helpRequested = helpRequested;
            this.generations = generations;
            this.retain = retain;
            this.population = population;
            this.matchesPerPair = matchesPerPair;
            this.mutationStrength = mutationStrength;
            this.moveTimeMillis = moveTimeMillis;
            this.maxPlies = maxPlies;
        }

        private static CliArguments parse(String[] args) {
            if (args == null) {
                args = new String[0];
            }

            if (args.length == 0) {
                throw new IllegalArgumentException("Missing required --seed argument");
            }

            Path seed = null;
            Path output = null;
            Path matches = null;
            boolean help = false;
            int generations = GeneticOptions.defaults().generations();
            int retain = GeneticOptions.defaults().retainCount();
            int population = GeneticOptions.defaults().populationSize();
            int matchesPerPair = GeneticOptions.defaults().matchesPerPair();
            double mutation = GeneticOptions.defaults().mutationStrength();
            long moveTime = GeneticOptions.defaults().moveTimeMillis();
            int maxPlies = GeneticOptions.defaults().maxPlies();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg) || "--help".equals(arg)) {
                    help = true;
                    continue;
                }
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unrecognised argument: " + arg);
                }
                String name;
                String value;
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    name = arg.substring(2, eq);
                    value = arg.substring(eq + 1);
                } else {
                    name = arg.substring(2);
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --" + name);
                    }
                    value = args[++i];
                }
                switch (name) {
                    case "seed" -> seed = Paths.get(value);
                    case "output" -> output = Paths.get(value);
                    case "matches" -> matches = Paths.get(value);
                    case "generations" -> generations = Integer.parseInt(value);
                    case "retain" -> retain = Integer.parseInt(value);
                    case "population" -> population = Integer.parseInt(value);
                    case "matches-per-pair" -> matchesPerPair = Integer.parseInt(value);
                    case "mutation" -> mutation = Double.parseDouble(value);
                    case "move-time" -> moveTime = Long.parseLong(value);
                    case "max-plies" -> maxPlies = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("Unknown option --" + name);
                }
            }

            if (help) {
                return new CliArguments(null, null, matches, true, generations, retain, population, matchesPerPair, mutation, moveTime, maxPlies);
            }

            if (seed == null) {
                throw new IllegalArgumentException("Missing required --seed argument");
            }
            if (!Files.isRegularFile(seed)) {
                throw new IllegalArgumentException("Seed file does not exist: " + seed);
            }

            Path resolvedOutput = output != null ? output : defaultOutput(seed);
            return new CliArguments(seed, resolvedOutput, matches, false, generations, retain, population, matchesPerPair, mutation, moveTime, maxPlies);
        }

        private GeneticOptions toOptions() {
            return new GeneticOptions(generations, retain, population, matchesPerPair, mutationStrength, moveTimeMillis, maxPlies);
        }

        private static Path defaultOutput(Path seedFile) {
            Path parent = seedFile.toAbsolutePath().getParent();
            String fileName = seedFile.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
            Path directory = parent != null ? parent : Path.of(".");
            return directory.resolve(baseName + "-evolved.yaml");
        }
    }
}

