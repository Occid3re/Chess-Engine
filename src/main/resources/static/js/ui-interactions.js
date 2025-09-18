(function ($, app) {
    'use strict';

    const promptFen = () => {
        const fen = prompt('Please enter FEN:');
        if (fen) {
            app.importFen(fen);
        }
    };

    $(document).ready(() => {
        if (!app) {
            console.error('Chess app not available');
            return;
        }

        $('#playWhite').on('click', (event) => {
            event.preventDefault();
            app.setPlayerColor('white');
        });

        $('#playBlack').on('click', (event) => {
            event.preventDefault();
            app.setPlayerColor('black');
        });

        $('#resetBoard').on('click', (event) => {
            event.preventDefault();
            app.resetGame();
        });

        $('#computerMove').on('click', (event) => {
            event.preventDefault();
            app.computerMove();
        });

        $('#importFEN').on('click', (event) => {
            event.preventDefault();
            promptFen();
        });

        $('#autoPlay').on('click', (event) => {
            event.preventDefault();
            app.toggleAutoplay();
        });

        $('#undoMove').on('click', (event) => {
            event.preventDefault();
            app.undoMove();
        });

        $('#redoMove').on('click', (event) => {
            event.preventDefault();
            app.redoMove();
        });
    });
}(window.jQuery, window.chessApp));
