const SIZE = 5;
const API_BASE = "http://localhost:8080/api";

let board = [];
let startingBoard = [];

let gameStarted = false;
let gameLocked = false;

let moves = 0;
let hints = 0;

let hintRow = -1;
let hintCol = -1;

function copyBoard(src) {
  return src.map(row => row.slice());
}

// -------------------- CREATE GRID CELLS --------------------

const cells = [];
const boardEl = document.getElementById("board");

for (let i = 0; i < SIZE * SIZE; i++) {
  const button = document.createElement("button");
  button.className = "cell";
  button.dataset.row = Math.floor(i / SIZE);
  button.dataset.col = i % SIZE;
  boardEl.appendChild(button);
  cells.push(button);
}

// -------------------- RENDER --------------------

function draw() {
  for (let i = 0; i < cells.length; i++) {
    const row = Number(cells[i].dataset.row);
    const col = Number(cells[i].dataset.col);

    if (gameStarted && board[row] && board[row][col] === 1) {
      cells[i].classList.add("lit");
    } else {
      cells[i].classList.remove("lit");
    }

    if (row === hintRow && col === hintCol) {
      cells[i].classList.add("hint");
    } else {
      cells[i].classList.remove("hint");
    }
  }

  document.getElementById("moves").textContent = moves;
  document.getElementById("hints").textContent = hints;

  if (gameStarted) {
    boardEl.classList.remove("off");
    document.getElementById("startText").style.display = "none";
  } else {
    boardEl.classList.add("off");
    document.getElementById("startText").style.display = "block";
  }

  // Keep Hint enabled while game is started so clicking Hint when solved shows the solved message
  document.getElementById("hintBtn").disabled = !gameStarted;
  document.getElementById("resetBtn").disabled = !gameStarted;
  document.getElementById("newBtn").disabled = !gameStarted;
}

function setMessage(text) {
  document.getElementById("message").textContent = text;
}

function startRound(newBoard) {
  board = copyBoard(newBoard);
  startingBoard = copyBoard(newBoard);
  moves = 0;
  gameLocked = false;
  hintRow = -1;
  hintCol = -1;
  document.getElementById("win").classList.remove("show");
  setMessage("");
  draw();
}

// -------------------- JAVA API CALLS --------------------

async function fetchNewBoard() {
  const res = await fetch(`${API_BASE}/new-board`);
  if (!res.ok) throw new Error("Failed to generate board from Java server.");
  const data = await res.json();
  return data.board;
}

async function fetchToggle(currentBoard, row, col) {
  const res = await fetch(`${API_BASE}/toggle`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ board: currentBoard, row, col })
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Toggle failed.");
  return data;
}

async function fetchHint(currentBoard) {
  const res = await fetch(`${API_BASE}/hint`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ board: currentBoard })
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Hint failed.");
  return data;
}

// -------------------- EVENT HANDLERS --------------------

// Launch instruction screen button
document.getElementById("startGameBtn").addEventListener("click", () => {
  document.getElementById("introOverlay").classList.add("hidden");
});

// Grid cell click (locked when not started or after winning)
boardEl.addEventListener("click", async (event) => {
  if (!gameStarted || gameLocked) return;

  const cell = event.target.closest(".cell");
  if (!cell) return;

  const row = Number(cell.dataset.row);
  const col = Number(cell.dataset.col);

  try {
    const result = await fetchToggle(board, row, col);
    board = result.board;
    moves++;

    // Clear hint highlight after player clicks a cell
    hintRow = -1;
    hintCol = -1;
    setMessage("");

    if (result.solved) {
      gameLocked = true;
      document.getElementById("winText").textContent =
        `Solved in ${moves} move(s) using ${hints} hint(s)!`;
      document.getElementById("win").classList.add("show");
    }

    draw();
  } catch (err) {
    setMessage("Error: Make sure LightsOutGame.java is running.");
  }
});

// Hint button (operates on current board, never mutates board or move counter)
document.getElementById("hintBtn").addEventListener("click", async () => {
  if (!gameStarted) return;

  try {
    const result = await fetchHint(board);
    if (result.solved || !result.hint) {
      hintRow = -1;
      hintCol = -1;
      setMessage("The board is already solved!");
      draw();
      return;
    }

    hints++;
    hintRow = result.hint[0];
    hintCol = result.hint[1];
    setMessage(`Hint: click row ${hintRow + 1}, column ${hintCol + 1}`);
    draw();
  } catch (err) {
    setMessage("Error: Make sure LightsOutGame.java is running.");
  }
});

// Reset button (restores starting board and resets move counter)
document.getElementById("resetBtn").addEventListener("click", () => {
  if (!gameStarted) return;
  startRound(startingBoard);
  setMessage("Board reset to starting state.");
});

// New game button
document.getElementById("newBtn").addEventListener("click", async () => {
  try {
    hints = 0;
    const newBoard = await fetchNewBoard();
    startRound(newBoard);
  } catch (err) {
    setMessage("Error: Make sure LightsOutGame.java is running.");
  }
});

// Play again button on win screen
document.getElementById("playAgain").addEventListener("click", async () => {
  try {
    hints = 0;
    const newBoard = await fetchNewBoard();
    startRound(newBoard);
  } catch (err) {
    setMessage("Error: Make sure LightsOutGame.java is running.");
  }
});

// Pull bulb to start first game
document.getElementById("bulb").addEventListener("click", () => {
  if (gameStarted) return;

  document.getElementById("lamp").classList.add("pull");

  setTimeout(async () => {
    try {
      const newBoard = await fetchNewBoard();
      gameStarted = true;
      document.getElementById("bulb").classList.add("on");
      document.getElementById("pullText").style.display = "none";
      startRound(newBoard);
    } catch (err) {
      setMessage("Start LightsOutGame.java first, then pull the bulb!");
    }
  }, 400);

  setTimeout(() => {
    document.getElementById("lamp").classList.remove("pull");
  }, 1300);
});

draw();