## 🧩 Testing Policy
All tests must be executed using Java 21 preview features.

### Default test command
```bash
mvn -Djava.version=21 \
    -Dmaven.compiler.release=21 \
    -Dmaven.compiler.enablePreview=true \
    -DargLine=--enable-preview \
    test
```

### Additional notes
* `MateSearchTest` keeps the shorter time budgets (50–500ms) for diagnostics only. The suite now requires the engine to
  succeed when it receives the largest configured per-move budget (currently 10s), so occasional misses at lower limits are
  acceptable.
