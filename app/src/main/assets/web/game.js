// The phone's screens during a game. app.js calls renderGame(room) with every state the TV sends.
// Questions and the phone's own words are both in the TV's language (see `lang` in app.js).
'use strict';

Object.assign(STRINGS.en, {
  question: 'Question {n} of {of}', doubleRound: 'Final: double points!',
  pickPlayer: 'Tap a player. You can change until time runs out.',
  lookAtTv: 'Look at the TV!', points: '+{n} points', onePoint: '+1 point', noPoints: 'No points this time',
  total: '{n} points · {rank}', total1: '1 point · {rank}', rank1: '1st', rank2: '2nd', rank3: '3rd', rankN: '{n}th',
  youInSpotlight: 'You’re in the spotlight! Answer for yourself:',
  guessFor: 'Guess what {name} answered:',
  beingDrawn: 'The others are drawing you… 😬', drawn: 'Sent! Waiting for the others.',
  draw: 'Draw:', send: 'Send', undo: 'Undo', sendFailed: 'That didn’t send. Try again.',
  pickDrawing: 'Pick the best drawing', yours: 'Yours',
  scores: 'Scores', finalIntro: 'Final round coming: points count double!',
  place: 'You came {rank}!', winner: 'You won! 🎉', draw2: 'A draw for first place! 🎉',
  awardTitle: 'The room thinks it’s you:', votes: '{n} votes', oneVote: '1 vote',
  noAward: 'Nobody picked you all game. A mystery…',
  nextGame: 'Want another? Press OK on the TV.',
});
Object.assign(STRINGS.pt, {
  question: 'Pergunta {n} de {of}', doubleRound: 'Final: pontos a dobrar!',
  pickPlayer: 'Toca num jogador. Podes mudar até o tempo acabar.',
  lookAtTv: 'Olha para a TV!', points: '+{n} pontos', onePoint: '+1 ponto', noPoints: 'Sem pontos desta vez',
  total: '{n} pontos · {rank}', total1: '1 ponto · {rank}', rank1: '1.º', rank2: '2.º', rank3: '3.º', rankN: '{n}.º',
  youInSpotlight: 'Estás em destaque! Responde por ti:',
  guessFor: 'Adivinha o que {name} respondeu:',
  beingDrawn: 'Os outros estão a desenhar-te… 😬', drawn: 'Enviado! À espera dos outros.',
  draw: 'Desenha:', send: 'Enviar', undo: 'Desfazer', sendFailed: 'Não foi enviado. Tenta outra vez.',
  pickDrawing: 'Escolhe o melhor desenho', yours: 'O teu',
  scores: 'Pontuação', finalIntro: 'Vem aí a ronda final: pontos a dobrar!',
  place: 'Ficaste em {rank} lugar!', winner: 'Ganhaste! 🎉', draw2: 'Empate no primeiro lugar! 🎉',
  awardTitle: 'A sala acha que és tu:', votes: '{n} votos', oneVote: '1 voto',
  noAward: 'Ninguém votou em ti o jogo todo. Um mistério…',
  nextGame: 'Outra? Carreguem em OK na TV.',
});

const PEN_COLOURS = ['#111111', '#FFFFFF', '#FF5C8A', '#FFC83D', '#2EC4B6', '#4D8DFF', '#A974FF', '#7B4B2A'];
const PEN_SIZES = [6, 16];

let gameKey = '';
let deadline = 0;
let timerFrame = 0;
// The drawing in progress, kept across updates of the same stage.
let pad = null;

function fill(text, values) {
  return text.replace(/\{(\w+)\}/g, (_, k) => values[k]);
}

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const c of children) if (c != null) node.append(c);
  return node;
}

function rankText(rank) {
  return fill(T['rank' + rank] || T.rankN, { n: rank });
}

function playerById(room, id) {
  return room.players.find((p) => p.id === id);
}

/** Leaves the game screen (back in the lobby): forgets the stage so the next game starts fresh. */
function leaveGame() {
  gameKey = '';
  pad = null;
  cancelAnimationFrame(timerFrame);
}

/** Draws the stage. Rebuilt only when something this phone shows changes, so a drawing isn't lost. */
function renderGame(room) {
  const g = room.game;
  const key = [g.serial, g.voted, g.answered, g.drew, g.picked, g.score].join('|');
  deadline = g.remaining ? Date.now() + g.remaining : 0;
  if (key === gameKey) return;
  const newStage = !gameKey.startsWith(g.serial + '|');
  gameKey = key;
  if (newStage && ['VOTE', 'ANSWER', 'DRAW', 'PICK'].includes(g.stage) && navigator.vibrate) navigator.vibrate(60);
  if (newStage) pad = null;

  const me = playerById(room, room.you);
  const screen = $('game');
  const header = el('div', { class: 'game-head' },
    face(me),
    el('span', { class: 'game-name' }, me ? me.name : ''),
    el('span', { class: 'game-score' }, fill(g.score === 1 ? T.total1 : T.total, { n: g.score, rank: rankText(g.rank) })));
  const timer = el('div', { class: 'timer' }, el('div', { class: 'timer-bar', id: 'timer-bar' }));
  const body = el('div', { class: 'game-body' });
  screen.replaceChildren(header, deadline ? timer : null, body);
  startTimer(g.remaining);

  const spotlight = playerById(room, g.spotlight);
  switch (g.stage) {
    case 'VOTE': renderVote(room, g, body); break;
    case 'ANSWER': renderAnswer(room, g, body, spotlight); break;
    case 'DRAW': renderDraw(room, g, body, spotlight); break;
    case 'PICK': renderPick(room, g, body); break;
    case 'END': renderEnd(room, g, body); break;
    case 'FINAL_INTRO': body.append(el('p', { class: 'big' }, T.finalIntro)); break;
    case 'SCORES': body.append(el('p', { class: 'big' }, T.lookAtTv)); break;
    default: renderReveal(g, body);
  }
}

function face(player) {
  const span = el('span', { class: 'avatar' });
  if (player) avatar(span, player, false);
  return span;
}

function startTimer(total) {
  cancelAnimationFrame(timerFrame);
  const bar = $('timer-bar');
  if (!bar || !deadline) return;
  const tick = () => {
    const left = Math.max(0, deadline - Date.now());
    bar.style.width = (100 * left / Math.max(total, 1)) + '%';
    bar.classList.toggle('hurry', left < 5000);
    if (left > 0) timerFrame = requestAnimationFrame(tick);
  };
  tick();
}

async function act(path, body, type) {
  try {
    const { status } = await post(path, body, type);
    return status === 204;
  } catch (e) {
    return false;
  }
}

function renderVote(room, g, body) {
  body.append(
    el('p', { class: 'kicker' }, g.double ? T.doubleRound : fill(T.question, { n: g.number, of: g.of })),
    el('h2', { class: 'question' }, g.question),
    el('p', { class: 'hint' }, T.pickPlayer));
  const grid = el('div', { class: 'choices players-choice' });
  for (const p of room.players) {
    const button = el('button', {
      type: 'button',
      class: 'choice' + (g.voted === p.id ? ' chosen' : ''),
      onclick: () => {
        grid.querySelectorAll('.choice').forEach((b) => b.classList.remove('chosen'));
        button.classList.add('chosen');
        act('/api/vote', new URLSearchParams({ target: p.id }));
      },
    }, face(p), el('span', {}, p.name));
    grid.append(button);
  }
  body.append(grid);
}

function renderAnswer(room, g, body, spotlight) {
  const mine = g.spotlight === room.you;
  body.append(
    el('p', { class: 'kicker' }, mine ? T.youInSpotlight : fill(T.guessFor, { name: spotlight ? spotlight.name : '' })),
    el('h2', { class: 'question' }, g.question));
  const list = el('div', { class: 'choices' });
  g.options.forEach((text, i) => {
    const button = el('button', {
      type: 'button',
      class: 'choice option' + (g.answered === i ? ' chosen' : ''),
      onclick: () => {
        list.querySelectorAll('.choice').forEach((b) => b.classList.remove('chosen'));
        button.classList.add('chosen');
        act('/api/answer', new URLSearchParams({ option: i }));
      },
    }, el('b', {}, 'ABCD'[i]), el('span', {}, text));
    list.append(button);
  });
  body.append(list);
}

function renderReveal(g, body) {
  const text = g.gained === 1 ? T.onePoint : g.gained ? fill(T.points, { n: g.gained }) : T.noPoints;
  body.append(el('p', { class: 'big' }, T.lookAtTv), el('p', { class: 'gained' + (g.gained ? '' : ' none') }, text));
}

function renderDraw(room, g, body, spotlight) {
  if (g.spotlight === room.you) { body.append(el('p', { class: 'big' }, T.beingDrawn)); return; }
  if (g.drew) { body.append(el('p', { class: 'big' }, T.drawn)); return; }
  if (!pad) pad = new DrawingPad(spotlight);
  const error = el('p', { class: 'error', hidden: '' }, T.sendFailed);
  const send = el('button', {
    type: 'button',
    class: 'primary',
    onclick: async () => {
      send.disabled = true;
      error.hidden = true;
      const blob = await pad.jpeg();
      const ok = await act('/api/drawing', blob, 'image/jpeg');
      if (!ok) { send.disabled = false; error.hidden = false; }
    },
  }, T.send);
  body.append(
    el('p', { class: 'draw-label' }, T.draw),
    el('h2', { class: 'question small' }, g.question),
    pad.canvas,
    pad.toolbar(),
    error,
    send);
}

/** A square canvas over the spotlight player's selfie, with pens, undo, and a JPEG of the result. */
class DrawingPad {
  constructor(subject) {
    this.size = 480;
    this.strokes = [];
    this.colour = PEN_COLOURS[0];
    this.width = PEN_SIZES[0];
    this.canvas = el('canvas', { class: 'pad', width: this.size, height: this.size });
    this.ctx = this.canvas.getContext('2d');
    this.subject = subject;
    this.background = null;
    if (subject && subject.selfie) {
      const img = new Image();
      img.onload = () => { this.background = img; this.redraw(); };
      img.src = selfieUrl(subject);
    }
    this.redraw();
    let current = null;
    const point = (e) => {
      const r = this.canvas.getBoundingClientRect();
      return [(e.clientX - r.left) * this.size / r.width, (e.clientY - r.top) * this.size / r.height];
    };
    this.canvas.addEventListener('pointerdown', (e) => {
      this.canvas.setPointerCapture(e.pointerId);
      current = { colour: this.colour, width: this.width, points: [point(e)] };
      this.strokes.push(current);
      this.redraw();
    });
    this.canvas.addEventListener('pointermove', (e) => {
      if (!current) return;
      current.points.push(point(e));
      this.redraw();
    });
    const end = () => { current = null; };
    this.canvas.addEventListener('pointerup', end);
    this.canvas.addEventListener('pointercancel', end);
  }

  redraw() {
    const c = this.ctx;
    const s = this.size;
    if (this.background) {
      c.drawImage(this.background, 0, 0, s, s);
    } else {
      c.fillStyle = this.subject ? COLOURS[this.subject.colour % COLOURS.length] : '#2A1D4A';
      c.fillRect(0, 0, s, s);
      if (this.subject) {
        c.fillStyle = '#170F2B';
        c.font = '900 260px system-ui, sans-serif';
        c.textAlign = 'center';
        c.textBaseline = 'middle';
        c.fillText(Array.from(this.subject.name)[0].toUpperCase(), s / 2, s / 2 + 10);
      }
    }
    c.lineCap = 'round';
    c.lineJoin = 'round';
    for (const stroke of this.strokes) {
      c.strokeStyle = stroke.colour;
      c.fillStyle = stroke.colour;
      c.lineWidth = stroke.width;
      const [first, ...rest] = stroke.points;
      if (rest.length === 0) {
        c.beginPath();
        c.arc(first[0], first[1], stroke.width / 2, 0, Math.PI * 2);
        c.fill();
        continue;
      }
      c.beginPath();
      c.moveTo(first[0], first[1]);
      for (const [x, y] of rest) c.lineTo(x, y);
      c.stroke();
    }
  }

  toolbar() {
    const bar = el('div', { class: 'tools' });
    const swatches = PEN_COLOURS.map((colour) => {
      const b = el('button', {
        type: 'button',
        class: 'swatch' + (colour === this.colour ? ' chosen' : ''),
        style: 'background:' + colour,
        'aria-label': colour,
        onclick: () => {
          this.colour = colour;
          swatches.forEach((s) => s.classList.toggle('chosen', s === b));
        },
      });
      return b;
    });
    const sizes = PEN_SIZES.map((width) => {
      const b = el('button', {
        type: 'button',
        class: 'size' + (width === this.width ? ' chosen' : ''),
        onclick: () => {
          this.width = width;
          sizes.forEach((s) => s.classList.toggle('chosen', s === b));
        },
      }, el('span', { style: 'width:' + (width / 2 + 4) + 'px;height:' + (width / 2 + 4) + 'px' }));
      return b;
    });
    const undo = el('button', {
      type: 'button',
      class: 'undo',
      onclick: () => { this.strokes.pop(); this.redraw(); },
    }, T.undo);
    bar.append(...swatches, ...sizes, undo);
    return bar;
  }

  jpeg() {
    return new Promise((resolve, reject) =>
      this.canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('blob'))), 'image/jpeg', 0.85));
  }
}

function renderPick(room, g, body) {
  body.append(el('h2', { class: 'question small' }, g.question), el('p', { class: 'kicker' }, T.pickDrawing));
  const grid = el('div', { class: 'gallery' });
  g.drawers.forEach((drawer, i) => {
    const own = drawer === room.you;
    const img = el('img', { src: '/drawing/' + drawer + '?c=' + code + '&s=' + g.serial, alt: String(i + 1) });
    const button = el('button', {
      type: 'button',
      class: 'drawing' + (g.picked === drawer ? ' chosen' : '') + (own ? ' own' : ''),
      onclick: () => {
        if (own) return;
        grid.querySelectorAll('.drawing').forEach((b) => b.classList.remove('chosen'));
        button.classList.add('chosen');
        act('/api/pick', new URLSearchParams({ drawer }));
      },
    }, img, el('span', { class: 'tag' }, own ? T.yours : String(i + 1)));
    grid.append(button);
  });
  body.append(grid);
}

function renderEnd(room, g, body) {
  const won = g.winners.includes(room.you);
  body.append(el('p', { class: 'big' },
    won ? (g.winners.length > 1 ? T.draw2 : T.winner) : fill(T.place, { rank: rankText(g.rank) })));
  if (g.award && g.award.question) {
    body.append(el('div', { class: 'award' },
      el('p', { class: 'kicker' }, T.awardTitle),
      el('p', { class: 'award-q' }, g.award.question),
      el('p', { class: 'hint' }, g.award.votes === 1 ? T.oneVote : fill(T.votes, { n: g.award.votes }))));
  } else {
    body.append(el('div', { class: 'award' }, el('p', { class: 'award-q' }, T.noAward)));
  }
  body.append(el('p', { class: 'hint' }, T.nextGame));
}
