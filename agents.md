## 🧩 Testing Policy
All tests must be executed using Java 21 preview features.

### Default test command
```bash
mvn -Djava.version=21 \
    -Dmaven.compiler.release=21 \
    -Dmaven.compiler.enablePreview=true \
    -DargLine=--enable-preview \
    test
