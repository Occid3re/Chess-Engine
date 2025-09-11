# Chess Engine

This project is a work-in-progress chess engine.

## Static Exchange Evaluation

The engine uses a **Static Exchange Evaluation (SEE)** helper to analyze the
material balance of capture sequences. SEE detects losing captures and allows
the search to skip or heavily de-prioritize them, preventing unsound sacrifices
like dropping a bishop for an unprotected pawn.
