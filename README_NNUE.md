# NNUE Integration

## 1) Generate dataset by self-play only
```
mkdir -p data
java -cp engine.jar julius.game.chessengine.nnue.selfplay.DumpPositions \
  --games 1000 --moveTimeMs 50 --labelTimeMs 200 --sampleEveryPly 2 \
  --maxPlies 300 --threads 8 --out data/positions.csv --randomizeStart
```

## 2) Train NNUE (Java-only) and export quantized weights
```
mkdir -p build/nnue
java -cp engine.jar julius.game.chessengine.nnue.train.HalfKPTrainer \
  --data data/positions.csv --hidden 192 --epochs 3 --batch 2048 \
  --lr 0.001 --opt adam --valSplit 0.05 --seed 7 \
  --out build/nnue/current.nnuev1
```

## 3) Copy weights into resources and build
```
cp build/nnue/current.nnuev1 src/main/resources/weights/current.nnuev1
./gradlew clean build
```

## 4) Run engine with NNUE enabled (default)
```
# weights are loaded automatically on Engine startup
```
