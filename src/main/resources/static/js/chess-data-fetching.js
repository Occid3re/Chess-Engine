(function (global, $) {
    'use strict';

    const WS_PROTOCOL = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const WS_URL = `${WS_PROTOCOL}://${window.location.host}/ws/uci`;

    const squareColorClass = (square) => ($(`#board .square-${square}`).hasClass('black-3c85d') ? 'lightskyblue' : 'blue');

    const createHighlightMap = () => ({ squares: {}, active: false });

    const formatScore = (score) => {
        if (!score) {
            return '--';
        }
        if (score.type === 'cp' && typeof score.value === 'number' && !Number.isNaN(score.value)) {
            return (score.value / 100).toFixed(2);
        }
        if (score.type === 'mate' && typeof score.value === 'number') {
            return `Mate in ${score.value}`;
        }
        return score.value || '--';
    };

    const parseMoveToObject = (uci) => {
        if (!uci || uci.length < 4) {
            return null;
        }
        const move = {
            from: uci.substring(0, 2),
            to: uci.substring(2, 4),
        };
        if (uci.length > 4) {
            move.promotion = uci.substring(4);
        }
        return move;
    };

    const convertPvToSan = (fen, pvMoves, ChessConstructor) => {
        if (!pvMoves || pvMoves.length === 0) {
            return '';
        }
        const sandbox = new ChessConstructor(fen);
        const sanMoves = [];
        pvMoves.forEach((uci) => {
            const move = parseMoveToObject(uci);
            if (!move) {
                return;
            }
            const result = sandbox.move(move);
            if (result) {
                sanMoves.push(result.san);
            }
        });
        return sanMoves.join(' ');
    };

    const chessApp = {
        uciClient: null,
        board: null,
        game: new global.Chess(),
        highlightState: createHighlightMap(),
        desiredMoveTime: 50,
        autoplay: false,
        waitingForEngine: false,
        playerColor: 'white',
        computerColor: 'black',
        basePosition: { type: 'startpos' },
        moveHistory: [],
        redoStack: [],
        engineInfo: {},
        engineOptions: {},
        lastScore: null,
        autoplayButton: null,

        initialize() {
            this.desiredMoveTime = parseInt($('#autoplaySlider').val(), 10) || 50;
            $('#sliderValue').text(this.desiredMoveTime);
            this.autoplayButton = document.getElementById('autoPlay');
            if (this.autoplayButton) {
                this.autoplayButton.textContent = 'Autoplay (Off)';
            }

            this.setupBoard();
            this.setupSlider();
            this.setupModal();
            this.setupUciClient();
            this.updateInfoBar();
            this.updateEvaluationDisplay();
            this.updateGameStatus();
        },

        setupBoard() {
            const config = {
                draggable: true,
                position: 'start',
                orientation: this.playerColor,
                onDragStart: (source, piece) => this.onDragStart(source, piece),
                onDrop: (source, target) => this.onDrop(source, target),
                onMouseoverSquare: (square) => this.onMouseoverSquare(square),
                onMouseoutSquare: () => this.onMouseoutSquare(),
                onSnapEnd: () => this.syncBoardPosition(),
            };
            this.board = global.Chessboard('board', config);
        },

        setupSlider() {
            $('#autoplaySlider').on('input change', (event) => {
                const value = parseInt(event.target.value, 10) || 0;
                this.desiredMoveTime = value;
                $('#sliderValue').text(value);
            });
        },

        setupModal() {
            const viewDetails = document.getElementById('viewDetails');
            const modal = document.getElementById('detailsModal');
            const closeModal = document.querySelector('.close');

            if (!viewDetails || !modal || !closeModal) {
                return;
            }

            viewDetails.onclick = () => {
                modal.style.display = 'block';
                this.updateGameDetails();
            };

            closeModal.onclick = () => {
                modal.style.display = 'none';
            };

            window.onclick = (event) => {
                if (event.target === modal) {
                    modal.style.display = 'none';
                }
            };
        },

        setupUciClient() {
            this.uciClient = new global.UciClient(WS_URL);

            this.uciClient.on('open', () => {
                this.updateInfoBar('Connecting to engine...');
            });

            this.uciClient.on('uciok', () => {
                this.updateInfoBar();
                this.uciClient.send('ucinewgame', { awaitReady: true });
                this.syncEnginePosition({ awaitReady: false });
            });

            this.uciClient.on('readyok', () => {
                this.updateInfoBar();
                if (this.autoplay || this.isComputerTurn()) {
                    this.requestEngineMove();
                }
            });

            this.uciClient.on('id', (data) => {
                if (!data) {
                    return;
                }
                this.engineInfo[data.type] = data.value;
                this.updateInfoBar();
            });

            this.uciClient.on('option', (option) => {
                if (!option || !option.name) {
                    return;
                }
                this.engineOptions[option.name] = option;
                this.updateInfoBar();
            });

            this.uciClient.on('info', (info) => {
                if (!info) {
                    return;
                }
                if (info.score) {
                    this.lastScore = info.score;
                }
                this.updateEvaluationDisplay(info);
            });

            this.uciClient.on('bestmove', (payload) => {
                this.waitingForEngine = false;
                if (!payload || !payload.move || payload.move === '(none)') {
                    this.updateGameStatus();
                    return;
                }
                this.applyMoveFromUci(payload.move, { clearRedo: true });
                this.updateGameStatus();
                if (this.autoplay || this.isComputerTurn()) {
                    this.requestEngineMove();
                }
            });
        },

        updateInfoBar(message) {
            const infoBar = document.getElementById('infoBar');
            if (!infoBar) {
                return;
            }

            if (message) {
                infoBar.textContent = message;
                return;
            }

            const name = this.engineInfo.name || this.engineInfo.id || 'Unknown Engine';
            const author = this.engineInfo.author ? ` by ${this.engineInfo.author}` : '';
            const threads = this.engineOptions.Threads && this.engineOptions.Threads.default
                ? ` | Threads: ${this.engineOptions.Threads.default}`
                : '';
            const ready = this.uciClient && this.uciClient.isReady ? ' | Ready' : ' | Initializing';
            infoBar.textContent = `Engine: ${name}${author}${threads}${ready}`;
        },

        updateEvaluationDisplay(info) {
            const scoreElement = document.getElementById('score');
            const lineElement = document.querySelector('#calculatedLine span');

            if (scoreElement) {
                scoreElement.textContent = `Score: ${formatScore(this.lastScore)}`;
            }

            if (!lineElement) {
                return;
            }

            if (!info || !info.pv) {
                lineElement.textContent = '--';
                return;
            }

            const fen = this.game.fen();
            const pvSan = convertPvToSan(fen, info.pv, global.Chess);
            lineElement.textContent = pvSan || '--';
        },

        updateGameStatus() {
            const header = document.getElementById('header');
            if (!header) {
                return;
            }

            if (this.game.in_checkmate()) {
                const winner = this.game.turn() === 'w' ? 'Black' : 'White';
                header.textContent = `Checkmate - ${winner} wins`;
            } else if (this.game.in_draw()) {
                header.textContent = 'Draw';
            } else if (this.game.in_stalemate()) {
                header.textContent = 'Stalemate';
            } else if (this.game.in_threefold_repetition()) {
                header.textContent = 'Threefold repetition';
            } else {
                header.textContent = 'ALIEKNEK';
            }

            this.updateTurnIndicator();
            this.updateKingGlow();
            this.updateGameDetails();
        },

        updateTurnIndicator() {
            const indicator = document.getElementById('turnIndicator');
            if (!indicator) {
                return;
            }
            const turn = this.game.turn() === 'w' ? 'White' : 'Black';
            indicator.textContent = `Turn: ${turn}`;
        },

        updateKingGlow() {
            const whiteKing = document.querySelector('[data-piece="wK"]');
            const blackKing = document.querySelector('[data-piece="bK"]');
            if (!whiteKing || !blackKing) {
                return;
            }
            whiteKing.classList.remove('glow-red', 'glow-blue');
            blackKing.classList.remove('glow-red', 'glow-blue');

            if (this.game.in_check()) {
                if (this.game.turn() === 'w') {
                    whiteKing.classList.add('glow-blue');
                } else {
                    blackKing.classList.add('glow-red');
                }
            }
        },

        updateGameDetails() {
            const container = document.getElementById('gameDetails');
            if (!container) {
                return;
            }

            const historySan = this.game.history();
            const details = [`<div class="game-details-container">`,
                '<table class="details-table">',
                `<tr><td>FEN</td><td>${this.game.fen()}</td></tr>`,
                `<tr><td>Move count</td><td>${historySan.length}</td></tr>`,
                `<tr><td>Autoplay</td><td>${this.autoplay ? 'Enabled' : 'Disabled'}</td></tr>`,
                `<tr><td>Score</td><td>${formatScore(this.lastScore)}</td></tr>`,
                `<tr><td>History</td><td>${historySan.join(' ') || '--'}</td></tr>`,
                '</table>',
                '</div>'];
            container.innerHTML = details.join('');
        },

        onDragStart(source, piece) {
            if (this.game.game_over()) {
                return false;
            }
            if (this.waitingForEngine && !this.autoplay) {
                return false;
            }
            if ((this.game.turn() === 'w' && piece[0] !== 'w') || (this.game.turn() === 'b' && piece[0] !== 'b')) {
                return false;
            }
            if (this.computerColor && piece[0] === this.computerColor.charAt(0) && !this.autoplay) {
                return false;
            }
            return true;
        },

        onDrop(source, target) {
            const move = this.tryMove(source, target, { clearRedo: true });
            if (!move) {
                return 'snapback';
            }
            this.syncBoardPosition();
            this.syncEnginePosition();
            this.updateGameStatus();
            if (this.autoplay || this.isComputerTurn()) {
                this.requestEngineMove();
            }
            return undefined;
        },

        onMouseoverSquare(square) {
            const moves = this.game.moves({ square, verbose: true });
            if (!moves.length) {
                return;
            }
            this.highlightState.active = true;
            this.highlightSquare(square, true);
            moves.forEach((move) => this.highlightSquare(move.to, true));
        },

        onMouseoutSquare() {
            if (!this.highlightState.active) {
                return;
            }
            Object.keys(this.highlightState.squares).forEach((sq) => {
                const $square = $(`#board .square-${sq}`);
                $square.css('background', '');
            });
            this.highlightState = createHighlightMap();
        },

        highlightSquare(square, highlight) {
            const $square = $(`#board .square-${square}`);
            if (!$square.length) {
                return;
            }
            if (highlight) {
                const background = squareColorClass(square);
                $square.css('background', background);
                this.highlightState.squares[square] = background;
            } else {
                $square.css('background', '');
                delete this.highlightState.squares[square];
            }
        },

        syncBoardPosition() {
            if (this.board) {
                this.board.position(this.game.fen());
            }
        },

        syncEnginePosition(options = { awaitReady: true }) {
            if (!this.uciClient) {
                return;
            }
            const command = this.buildPositionCommand();
            this.uciClient.send(command, options);
        },

        buildPositionCommand() {
            let command = this.basePosition.type === 'fen'
                ? `position fen ${this.basePosition.value}`
                : 'position startpos';
            if (this.moveHistory.length) {
                command += ` moves ${this.moveHistory.join(' ')}`;
            }
            return command;
        },

        tryMove(from, to, { clearRedo }) {
            const piece = this.game.get(from);
            if (!piece) {
                return null;
            }
            const isPromotion = piece.type === 'p' && (to[1] === '8' || to[1] === '1');
            const move = this.game.move({
                from,
                to,
                promotion: isPromotion ? 'q' : undefined,
            });
            if (!move) {
                return null;
            }
            const uci = `${from}${to}${move.promotion ? move.promotion : ''}`;
            this.moveHistory.push(uci);
            if (clearRedo) {
                this.redoStack = [];
            }
            this.syncBoardPosition();
            return move;
        },

        applyMoveFromUci(uci, { clearRedo }) {
            const moveObj = parseMoveToObject(uci);
            if (!moveObj) {
                return null;
            }
            const result = this.game.move(moveObj);
            if (!result) {
                console.warn('Failed to apply engine move', uci);
                return null;
            }
            this.moveHistory.push(uci);
            if (clearRedo) {
                this.redoStack = [];
            }
            this.syncBoardPosition();
            return result;
        },

        isComputerTurn() {
            if (this.autoplay) {
                return true;
            }
            if (!this.computerColor) {
                return false;
            }
            return this.game.turn() === this.computerColor.charAt(0);
        },

        requestEngineMove() {
            if (!this.uciClient || this.waitingForEngine || this.game.game_over()) {
                return;
            }
            this.waitingForEngine = true;
            this.syncEnginePosition({ awaitReady: true });
            const goCommand = `go movetime ${this.desiredMoveTime}`;
            this.uciClient.send(goCommand, { awaitBestmove: true });
        },

        setAutoplay(enabled) {
            this.autoplay = enabled;
            if (this.autoplayButton) {
                this.autoplayButton.textContent = enabled ? 'Autoplay (On)' : 'Autoplay (Off)';
            }
            if (enabled) {
                this.requestEngineMove();
            }
        },

        toggleAutoplay() {
            this.setAutoplay(!this.autoplay);
        },

        setPlayerColor(color) {
            this.playerColor = color;
            this.computerColor = color === 'white' ? 'black' : 'white';
            if (this.board) {
                this.board.orientation(color);
            }
            if (this.autoplay || this.isComputerTurn()) {
                this.requestEngineMove();
            }
        },

        resetGame() {
            this.game.reset();
            this.basePosition = { type: 'startpos' };
            this.moveHistory = [];
            this.redoStack = [];
            this.waitingForEngine = false;
            this.lastScore = null;
            this.syncBoardPosition();
            this.updateEvaluationDisplay();
            this.updateGameStatus();
            if (this.uciClient) {
                this.uciClient.send('ucinewgame', { awaitReady: true });
                this.syncEnginePosition({ awaitReady: true });
            }
            if (this.autoplay || this.isComputerTurn()) {
                this.requestEngineMove();
            }
        },

        importFen(fen) {
            if (!fen) {
                return;
            }
            const validator = new global.Chess();
            const validation = validator.validate_fen(fen);
            if (!validation.valid) {
                alert(`Invalid FEN: ${validation.error}`);
                return;
            }
            this.game.load(fen);
            this.basePosition = { type: 'fen', value: fen };
            this.moveHistory = [];
            this.redoStack = [];
            this.waitingForEngine = false;
            this.syncBoardPosition();
            this.updateGameStatus();
            if (this.uciClient) {
                this.uciClient.send('ucinewgame', { awaitReady: true });
                this.syncEnginePosition({ awaitReady: true });
            }
            if (this.autoplay || this.isComputerTurn()) {
                this.requestEngineMove();
            }
        },

        undoMove() {
            if (!this.moveHistory.length) {
                return;
            }
            const last = this.moveHistory.pop();
            this.redoStack.push(last);
            this.game.undo();
            this.waitingForEngine = false;
            this.syncBoardPosition();
            this.updateGameStatus();
            this.syncEnginePosition({ awaitReady: true });
            if (this.autoplay || this.isComputerTurn()) {
                this.requestEngineMove();
            }
        },

        redoMove() {
            if (!this.redoStack.length) {
                return;
            }
            const move = this.redoStack.pop();
            const result = this.applyMoveFromUci(move, { clearRedo: false });
            if (result) {
                this.syncEnginePosition({ awaitReady: true });
                this.updateGameStatus();
                if (this.autoplay || this.isComputerTurn()) {
                    this.requestEngineMove();
                }
            }
        },

        computerMove() {
            this.requestEngineMove();
        },
    };

    global.chessApp = chessApp;

    $(document).ready(() => {
        chessApp.initialize();
    });
}(window, window.jQuery));
