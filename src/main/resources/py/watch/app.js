'use strict';
// Watch page for the Alieknek bot: polls /alieknek/status.json and draws the current game.
const GLYPH = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' };
const STATE = {
  playing: 'spielt', idle: 'wartet auf Gegner', sleeping: 'schläft', disabled: 'ausgeschaltet',
  'no-token': 'kein Lichess-Token', starting: 'startet', error: 'Fehler',
};
const RESULT = { win: 'Sieg', loss: 'Niederlage', draw: 'Remis', aborted: 'abgebrochen' };
let snap = null;
let fetchedAt = 0;

const $ = (id) => document.getElementById(id);

function fmtClock(ms) {
  if (ms == null) return '–';
  ms = Math.max(0, ms);
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  const base = `${m}:${String(s % 60).padStart(2, '0')}`;
  return ms < 10000 ? `${base}.${Math.floor((ms % 1000) / 100)}` : base;
}

function drawBoard(fen, flip, lastMove) {
  const squares = [];
  fen.split(' ')[0].split('/').forEach((row, r) => {
    let f = 0;
    for (const ch of row) {
      if (/\d/.test(ch)) {
        for (let i = 0; i < Number(ch); i++) squares.push({ r, f: f++, p: null });
      } else {
        squares.push({ r, f: f++, p: ch });
      }
    }
  });
  if (flip) squares.reverse();
  const highlight = lastMove ? [lastMove.slice(0, 2), lastMove.slice(2, 4)] : [];
  $('board').replaceChildren(...squares.map(({ r, f, p }) => {
    const d = document.createElement('div');
    const name = 'abcdefgh'[f] + (8 - r);
    d.className = ((r + f) % 2 ? 'd' : 'l') + (highlight.includes(name) ? ' hl' : '');
    if (p) {
      d.textContent = GLYPH[p.toLowerCase()];
      d.classList.add(p === p.toUpperCase() ? 'w' : 'b');
    }
    return d;
  }));
}

function drawPlayer(el, p, ms, running) {
  const title = p.title ? `${p.title} ` : '';
  const rating = p.rating ? ` (${p.rating})` : '';
  el.querySelector('.name').textContent = `${title}${p.name}${rating}`;
  const clock = el.querySelector('.clock');
  clock.textContent = fmtClock(ms);
  clock.classList.toggle('run', running);
}

function drawRecent(recent) {
  if (!recent || !recent.length) return;
  $('recent').replaceChildren(...recent.map((r) => {
    const li = document.createElement('li');
    const a = document.createElement('a');
    a.href = `https://lichess.org/${r.id}`;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    const rating = r.opponentRating ? ` (${r.opponentRating})` : '';
    a.textContent = `${r.color === 'white' ? '♔' : '♚'} gegen ${r.opponent}${rating}`;
    const res = document.createElement('span');
    res.className = r.result;
    const diff = r.rated && typeof r.ratingDiff === 'number' ? ` ${signed(r.ratingDiff)}` : '';
    res.textContent = (RESULT[r.result] || r.result) + diff;
    li.append(a, res);
    return li;
  }));
}

function signed(n) {
  return n > 0 ? `+${n}` : n < 0 ? `−${Math.abs(n)}` : '±0';
}

const PERF = { bullet: 'Bullet', blitz: 'Blitz', rapid: 'Schnellschach', classical: 'Klassisch' };

/** „Blitz 1730 (+12 in 24 h) · Bullet 1634“ – Spielarten nach Zahl der Partien. */
function ratingText(s) {
  const change = (s.last24h && s.last24h.ratingChange) || {};
  return Object.entries(s.ratings || {})
    .sort((a, b) => b[1].games - a[1].games)
    .map(([perf, r]) => `${PERF[perf] || perf} ${r.rating}${change[perf] ? ` (${signed(change[perf])} in 24 h)` : ''}`)
    .join(' · ');
}

function render() {
  if (!snap) return;
  const chip = $('state');
  chip.textContent = STATE[snap.state] || snap.state;
  chip.className = `chip ${snap.state}`;
  $('schedule').textContent = snap.enabled
    ? `Spielzeiten: ${snap.hours === 'always' ? 'immer' : snap.hours} (${snap.timezone}), jetzt ${snap.now}`
    : 'Der Bot ist ausgeschaltet.';
  $('rating').textContent = ratingText(snap);

  const g = snap.game;
  $('live').hidden = !(g && g.fen);
  if (g && g.fen) {
    const flip = g.myColor === 'black';
    drawBoard(g.fen, flip, g.lastMove);
    const whiteToMove = g.fen.split(' ')[1] === 'w';
    const running = g.status === 'started' && g.ply >= 2;
    const elapsed = running ? (snap.serverTime - g.updatedAt) + (Date.now() - fetchedAt) : 0;
    const wt = g.wtime != null ? g.wtime - (whiteToMove ? elapsed : 0) : null;
    const bt = g.btime != null ? g.btime - (whiteToMove ? 0 : elapsed) : null;
    const top = flip ? 'white' : 'black';
    const bottom = flip ? 'black' : 'white';
    drawPlayer($('top'), g[top], top === 'white' ? wt : bt, running && (top === 'white') === whiteToMove);
    drawPlayer($('bottom'), g[bottom], bottom === 'white' ? wt : bt, running && (bottom === 'white') === whiteToMove);
    const e = snap.engine || {};
    let evalText = '';
    if (e.depth) {
      const score = e.mate != null ? `Matt in ${e.mate}` : `${e.cp > 0 ? '+' : ''}${(e.cp / 100).toFixed(2)}`;
      evalText = `Bewertung (Weiß) ${score} · Tiefe ${e.depth}`;
    }
    $('engine').textContent = evalText;
    $('lichess').href = `https://lichess.org/${g.id}`;
  }
  drawRecent(snap.recent);
}

async function poll() {
  if (!document.hidden || !snap) {
    try {
      const res = await fetch('/alieknek/status.json', { cache: 'no-store' });
      if (res.ok) {
        snap = await res.json();
        fetchedAt = Date.now();
      }
    } catch (_) {
      // offline: keep showing the last state
    }
  }
  setTimeout(poll, snap && snap.state === 'playing' ? 1500 : 10000);
}

setInterval(render, 250);
poll();
