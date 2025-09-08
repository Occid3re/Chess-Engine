$(document).ready(function () {
    let computerColor = 'black'; // Default computer color

    // Function to set board orientation based on color choice
    const setBoardOrientation = (color) => {
        board.orientation(color); // Set board orientation to chosen color
    };

    const chooseColorAndAutoPlay = (color) => {
        setBoardOrientation(color); // Set orientation based on user choice
        computerColor = (color === 'white') ? 'black' : 'white'; // Computer plays opposite color
        autoPlayColor(computerColor); // Call autoPlayColor with updated color
    };

    // Event listeners for UI interactions
    const initEventListeners = () => {
        $('#playWhite').on('click', () => chooseColorAndAutoPlay('white'));
        $('#playBlack').on('click', () => chooseColorAndAutoPlay('black'));
        $('#resetBoard').on('click', () => {
            makeRequest('PUT', 'http://localhost:8080/chess/reset', reloadBoard); // Assuming makeRequest and reloadBoard are defined in chess-data-fetching.js
        });
        $('#undoMove').on('click', () => {
            makeRequest('GET', 'http://localhost:8080/chess/undo', reloadBoard); // Assuming makeRequest and reloadBoard are defined in chess-data-fetching.js
        });
        $('#redoMove').on('click', () => {
            makeRequest('GET', 'http://localhost:8080/chess/redo', reloadBoard); 
        });
        $('#autoPlay').on('click', () => {
            makeRequest('GET', 'http://localhost:8080/chess/autoplay', reloadBoard); // Assuming makeRequest and reloadBoard are defined in chess-data-fetching.js
        });
        $('#importFEN').on('click', function () {
            var fenString = prompt("Please enter FEN:");
            if (fenString) {
                importFEN(fenString); // Assuming importFEN is defined in chess-data-fetching.js
            }
        });
    };

    // Initialize event listeners
    initEventListeners();
    // Additional setup if required (e.g., setupModal)
    setupModal(); // Uncomment if setupModal is a function that needs to be called
});
