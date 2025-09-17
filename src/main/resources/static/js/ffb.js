$(document).ready(function () {
    let computerColor = 'black'; // Default computer color

    const apiBaseUrl = window.location.origin;
    const makeRequest = async (method, url, callback) => {
        try {
            const response = await fetch(`${apiBaseUrl}${url}`, { method });
            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
            const data = await response.json();
            if (callback) callback(data);
        } catch (error) {
            console.error('Request failed:', error);
        }
    };

    let latestGameData = null; 

    const updateCalculatedLine = () => {
        makeRequest('GET', '/chess/state', (data) => {
            latestGameData = data;    
            const lineText = data.move || "No moves yet";
            const gameState = data.gameState.state;
            const score = data.score;

            document.getElementById('calculatedLine').innerText = `Calculated Line: ${lineText}`;
            document.getElementById('score').textContent = `SCORE: ${score}`;

            checkState(gameState);
            updateKingGlow(gameState); // Update the king glow based on game state
            updateGameDetails(data);
        });
    };

    const updateGameDetails = (data) => {
        let details = '<p>Game State: ' + data.gameState.state + '</p>';
        details += '<p>Score: ' + data.score + '</p>';
        details += '<p>Midgame Score (cp): ' + data.gameState.score.midgameScore + '</p>';
        details += '<p>Endgame Score (cp): ' + data.gameState.score.endgameScore + '</p>';
        details += '<p>Blended Score (cp): ' + data.gameState.score.blendedScore + '</p>';
        details += '<p>Score Difference (pawns): ' + data.gameState.score.scoreDifference + '</p>';
        details += '<p>Game Over: ' + data.gameState.gameOver + '</p>';
        details += '<p>In State Check: ' + data.gameState.inStateCheck + '</p>';
        details += '<p>In State CheckMate: ' + data.gameState.inStateCheckMate + '</p>';
        details += '<p>In State Draw: ' + data.gameState.inStateDraw + '</p>';

        // Parsing the repetition counter
        details += '<p>Repetition Counter:</p><ul>';
        for (const hash in data.gameState.repetitionCounter) {
            details += `<li>${hash}: ${data.gameState.repetitionCounter[hash]}</li>`;
        }
        details += '</ul>';

        document.getElementById('gameDetails').innerHTML = details;
    };

    // Modal handling code
    const viewDetails = document.getElementById("viewDetails");
    const modal = document.getElementById("detailsModal");
    const closeModal = document.querySelector('.close');

    if (viewDetails && modal && closeModal) {
        viewDetails.onclick = function () {
            modal.style.display = "block";
            updateGameDetails(latestGameData); // Update the modal with the latest game data
        }

        closeModal.onclick = function () {
            modal.style.display = "none";
        }

        window.onclick = function (event) {
            if (event.target == modal) {
                modal.style.display = "none";
            }
        }
    } else {
        console.error('Modal or associated elements not found in the document.');
    }

    function importFEN(fenString) {
        var encodedFenString = encodeURIComponent(fenString);
        makeRequest('PATCH', `/chess/fen?fen=${encodedFenString}`, () => {
            reloadBoard();
        });
    }

    function updateKingGlow(gameState) {
        const whiteKingElement = document.querySelector('[data-piece="wK"]');
        const blackKingElement = document.querySelector('[data-piece="bK"]');

        // Remove existing glow classes
        whiteKingElement.classList.remove('glow-red', 'glow-blue');
        blackKingElement.classList.remove('glow-red', 'glow-blue');

        // Apply glow based on the game state
        switch (gameState) {
            case 'WHITE_IN_CHECK':
                whiteKingElement.classList.add('glow-blue');
                break;
            case 'BLACK_IN_CHECK':
                blackKingElement.classList.add('glow-red');
                break;
            case 'WHITE_WON':
                blackKingElement.classList.add('glow-blue'); // Blue glow to indicate loss
                break;
            case 'BLACK_WON':
                whiteKingElement.classList.add('glow-red'); // Blue glow to indicate loss
                break;
            // No glow applied for PLAY and DRAW states
        }
    }

    document.getElementById('viewDetails').onclick = function () {
        document.getElementById('detailsModal').style.display = "block";
    }

    document.getElementsByClassName('close')[0].onclick = function () {
        document.getElementById('detailsModal').style.display = "none";
    }

    window.onclick = function (event) {
        if (event.target == document.getElementById('detailsModal')) {
            document.getElementById('detailsModal').style.display = "none";
        }
    }


    document.getElementById('importFEN').addEventListener('click', function () {
        var fenString = prompt("Please enter FEN:");
        if (fenString) {
            importFEN(fenString);
        }
    });



    const checkState = (state) => {
        if (state !== "PLAY") {
            document.getElementById("header").textContent = state;
        }
    };

    const makeMove = (type, color) => {
        makeRequest('PATCH', `/chess/figure/move/${type}/${color}`, (data) => {
            checkState(data.state);
            reloadBoard();
        });
    };

    const onDrop = (source, target) => {
        makeRequest('PATCH', `/chess/figure/move/${source}/${target}`, reloadBoard);
    };

    const reloadBoard = () => {
        makeRequest('GET', '/chess/figure/frontend', (data) => {
            board.position(data.renderBoard);
            updateCalculatedLine(); // Update the calculated line
        });
    };

    const highlightSquare = (square, highlight) => {
        const $square = $(`#board .square-${square}`);
        const background = $square.hasClass('black-3c85d') ? 'lightskyblue' : 'blue';
        $square.css('background', highlight ? background : '');
    };

    const onMouseoverSquare = (square) => {
        makeRequest('GET', `/chess/figure/move/possible/${square}`, (moves) => {
            if (moves.length === 0) return;
            highlightSquare(square, true);
            moves.forEach(move => highlightSquare(move.x + move.y, true));
        });
    };

    const onMouseoutSquare = () => {
        $('#board .square-55d63').css('background', '');
    };

    const initEventListeners = () => {
        $('#computerMove').on('click', () => {
            makeMove('intelligent', computerColor);
        });
        $('#resetBoard').on('click', () => {
            makeRequest('PUT', '/chess/reset', () => {
                reloadBoard();
            });
        });
        $('#undoMove').on('click', () => {
            makeRequest('GET', '/chess/undo', () => {
                reloadBoard();
            });
        });
        $('#autoPlay').on('click', () => {
            makeRequest('GET', '/chess/autoplay', () => {
                reloadBoard();
            });
        });
    };


    const setBoardOrientation = (color) => {
        board.orientation(color); // Set board orientation to chosen color
    };

    const chooseColor = (color) => {
        setBoardOrientation(color); // Set orientation based on user choice
        computerColor = (color === 'white') ? 'black' : 'white'; // Computer plays opposite color
    };

    const initColorChoiceEventListeners = () => {
        $('#playWhite').on('click', () => chooseColor('white'));
        $('#playBlack').on('click', () => chooseColor('black'));
    };

    const boardConfig = {
        draggable: true,
        position: 'start',
        orientation: 'white', // Default orientation
        onDrop: onDrop,
        onMouseoverSquare: onMouseoverSquare,
        onMouseoutSquare: onMouseoutSquare
    };

    // Function to start the auto-refresh interval
    const startAutoRefresh = (intervalMs) => {
        setInterval(() => {
            reloadBoard();
        }, intervalMs);
    };

    const board = Chessboard('board', boardConfig);

    // Keep board responsive to screen size
    $(window).resize(board.resize);

    // Prevent the page from scrolling when interacting with the board on touch devices
    const boardElement = document.getElementById('board');
    const preventScroll = e => { if (e.cancelable) e.preventDefault(); };

    boardElement.addEventListener('touchmove', preventScroll, { passive: false });
    boardElement.addEventListener('touchstart', () => {
        document.body.addEventListener('touchmove', preventScroll, { passive: false });
    });
    ['touchend', 'touchcancel'].forEach(evt => {
        boardElement.addEventListener(evt, () => {
            document.body.removeEventListener('touchmove', preventScroll);
        });
    });

    initEventListeners();
    initColorChoiceEventListeners();
    reloadBoard();
    startAutoRefresh(300);
});