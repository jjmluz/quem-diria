// The phone's side of Quem Diria?: join the TV's room, take a selfie, and follow the room live.
// The TV sends the whole room on /api/events whenever anything changes; this page only draws it.
'use strict';

const STRINGS = {
  en: {
    yourName: 'Your name', join: 'Join', takeSelfie: 'Take a selfie', newSelfie: 'New selfie',
    sending: 'Sending…', change: 'Change', save: 'Save', leave: 'Leave the game',
    leaveConfirm: 'Leave the game?',
    modeFamily: 'Family questions', modeAdults: 'Adult questions',
    reconnecting: 'Connecting to the TV…',
    waitingFew: 'Waiting for more players. {n} more needed.',
    waitingOne: 'Waiting for more players. 1 more needed.',
    ready: 'Everyone in? Start the game on the TV with OK.',
    errName: 'Type a name of up to 14 letters.', errTaken: 'Someone already has that name.',
    errFull: 'The game is full.', errStarted: 'The game has already started. Join the next one!',
    errNetwork: 'Can’t reach the TV. Are you on the same Wi-Fi?',
    errSelfie: 'That photo didn’t work. Try again.',
    errRestarted: 'The TV started a new game. Join again.',
    paused: 'Paused', pausedHint: 'The game carries on when the TV does.',
  },
  pt: {
    yourName: 'O teu nome', join: 'Entrar', takeSelfie: 'Tirar uma selfie', newSelfie: 'Nova selfie',
    sending: 'A enviar…', change: 'Mudar', save: 'Guardar', leave: 'Sair do jogo',
    leaveConfirm: 'Sair do jogo?',
    modeFamily: 'Perguntas para a família', modeAdults: 'Perguntas para adultos',
    reconnecting: 'A ligar à TV…',
    waitingFew: 'À espera de mais jogadores. Faltam {n}.',
    waitingOne: 'À espera de mais jogadores. Falta 1.',
    ready: 'Estão todos? Comecem o jogo na TV com OK.',
    errName: 'Escreve um nome até 14 letras.', errTaken: 'Já há alguém com esse nome.',
    errFull: 'O jogo está cheio.', errStarted: 'O jogo já começou. Entra no próximo!',
    errNetwork: 'Não consigo chegar à TV. Estás na mesma rede Wi-Fi?',
    errSelfie: 'Essa foto não deu. Tenta outra vez.',
    errRestarted: 'A TV começou um jogo novo. Entra outra vez.',
    paused: 'Em pausa', pausedHint: 'O jogo continua quando a TV continuar.',
  },
};
// The TV sets <html lang> to its own language, so every phone in the room reads the same one.
const lang = (document.documentElement.lang || navigator.language || 'en').slice(0, 2).toLowerCase();
const T = STRINGS[lang] || STRINGS.en;

// The same player colours as the TV's (Theme.kt), by index.
const COLOURS = ['#FF5C8A', '#FFC83D', '#2EC4B6', '#4D8DFF', '#A974FF', '#FF8A3D', '#7BD389', '#FF9BD2'];

const $ = (id) => document.getElementById(id);
// An old link may still carry a room code (?c=): there's none any more, so tidy the address.
if (location.search) history.replaceState(null, '', '/');

// From joining. A TV that restarted doesn't know it, and the page then asks to join again.
let token = localStorage.getItem('qd.token') || '';
let events = null;
let room = null;
let uploading = false;

document.querySelectorAll('[data-t]').forEach((el) => { el.textContent = T[el.dataset.t]; });

function show(id) {
  for (const s of ['join', 'room', 'game']) $(s).hidden = s !== id;
}

function saveToken(value) {
  token = value;
  if (value) localStorage.setItem('qd.token', value);
  else localStorage.removeItem('qd.token');
}

function errorText(json) {
  return ({ name: T.errName, taken: T.errTaken, full: T.errFull, started: T.errStarted })[json && json.error] || T.errNetwork;
}

async function post(path, body, type) {
  const response = await fetch(path + '?t=' + encodeURIComponent(token), {
    method: 'POST',
    headers: { 'Content-Type': type || 'application/x-www-form-urlencoded' },
    body,
  });
  let json = null;
  try { json = await response.json(); } catch (e) { /* 204s have no body */ }
  return { status: response.status, json };
}

function showJoin(message) {
  show('join');
  $('join-error').hidden = !message;
  $('join-error').textContent = message || '';
  $('name').value = localStorage.getItem('qd.name') || '';
}

$('join-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const name = $('name').value.trim();
  const button = e.submitter || $('join-form').querySelector('button');
  button.disabled = true;
  try {
    const { status, json } = await post('/api/join', new URLSearchParams({ name }));
    if (status === 200) {
      saveToken(json.token);
      localStorage.setItem('qd.name', name);
      connect();
    } else {
      showJoin(errorText(json));
    }
  } catch (err) {
    showJoin(T.errNetwork);
  } finally {
    button.disabled = false;
  }
});

/** Follows the room. The browser reconnects a dropped stream by itself; a refused one means the token is gone. */
function connect() {
  if (events) events.close();
  events = new EventSource('/api/events?t=' + encodeURIComponent(token));
  events.onopen = () => { $('offline').hidden = true; };
  events.onmessage = (e) => { room = JSON.parse(e.data); draw(); };
  events.addEventListener('gone', () => { events.close(); saveToken(''); showJoin(); });
  events.onerror = () => {
    $('offline').hidden = false;
    if (events.readyState === EventSource.CLOSED) checkToken();
  };
}

/** After a refused stream: start over if the TV no longer knows us, or try again shortly. */
async function checkToken() {
  try {
    const response = await fetch('/api/me?t=' + encodeURIComponent(token));
    if (response.status === 401) {
      $('offline').hidden = true;
      saveToken('');
      showJoin(T.errRestarted);
      return;
    }
  } catch (err) { /* still unreachable */ }
  setTimeout(() => { if (token) connect(); }, 3000);
}

function selfieUrl(player) {
  return '/selfie/' + player.id + '?t=' + encodeURIComponent(token) + '&v=' + player.selfie;
}

function avatar(el, player, big) {
  el.style.backgroundColor = COLOURS[player.colour % COLOURS.length];
  if (player.selfie) {
    el.style.backgroundImage = 'url("' + selfieUrl(player) + '")';
    el.textContent = '';
  } else {
    el.style.backgroundImage = '';
    el.textContent = Array.from(player.name)[0].toUpperCase();
  }
  if (big) el.setAttribute('aria-label', player.name);
}

function draw() {
  $('paused').hidden = !(room.phase === 'GAME' && room.game && room.game.paused);
  if (room.phase === 'GAME') { show('game'); renderGame(room); return; }
  leaveGame();
  show('room');
  $('mode').textContent = room.mode === 'ADULTS' ? T.modeAdults : T.modeFamily;
  const me = room.players.find((p) => p.id === room.you);
  if (!me) return;
  avatar($('me-avatar'), me, true);
  $('me-name').textContent = me.name;
  if (!uploading) $('selfie-label').textContent = me.selfie ? T.newSelfie : T.takeSelfie;

  const missing = room.min - room.players.length;
  $('status').textContent = missing > 1 ? T.waitingFew.replace('{n}', missing) : missing === 1 ? T.waitingOne : T.ready;

  const list = $('players');
  list.replaceChildren(...room.players.map((p) => {
    const li = document.createElement('li');
    if (!p.connected) li.classList.add('away');
    if (p.id === room.you) li.classList.add('you');
    const face = document.createElement('span');
    face.className = 'avatar';
    avatar(face, p, false);
    const name = document.createElement('span');
    name.textContent = p.name;
    li.append(face, name);
    return li;
  }));
}

// Selfies: the phone's camera through the file picker (camera access in the page itself needs HTTPS),
// cropped to the middle square and shrunk to 480 pixels before it goes to the TV.
$('selfie-button').addEventListener('click', () => $('selfie-input').click());
$('selfie-input').addEventListener('change', async () => {
  const file = $('selfie-input').files[0];
  $('selfie-input').value = '';
  if (!file) return;
  uploading = true;
  $('selfie-label').textContent = T.sending;
  try {
    const jpeg = await square(file, 480);
    const { status } = await post('/api/selfie', jpeg, 'image/jpeg');
    if (status !== 204) throw new Error(String(status));
  } catch (err) {
    alert(T.errSelfie);
  } finally {
    uploading = false;
    if (room) draw();
  }
});

async function square(file, size) {
  // createImageBitmap and drawImage follow the photo's EXIF rotation in current browsers.
  const image = await createImageBitmap(file);
  const side = Math.min(image.width, image.height);
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = size;
  canvas.getContext('2d').drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, 0, 0, size, size);
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('blob'))), 'image/jpeg', 0.85));
}

$('rename-button').addEventListener('click', () => {
  $('rename-form').hidden = false;
  $('rename-error').hidden = true;
  $('rename').value = $('me-name').textContent;
  $('rename').focus();
  $('rename').select();
});
$('rename-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const name = $('rename').value.trim();
  try {
    const { status, json } = await post('/api/name', new URLSearchParams({ name }));
    if (status === 204) {
      localStorage.setItem('qd.name', name);
      $('rename-form').hidden = true;
    } else {
      $('rename-error').hidden = false;
      $('rename-error').textContent = errorText(json);
    }
  } catch (err) {
    $('rename-error').hidden = false;
    $('rename-error').textContent = T.errNetwork;
  }
});

$('leave-button').addEventListener('click', async () => {
  if (!confirm(T.leaveConfirm)) return;
  try { await post('/api/leave', ''); } catch (err) { /* unreachable: we stay on the TV's list, greyed out */ }
  if (events) events.close();
  saveToken('');
  showJoin();
});

if (token) connect();
else showJoin();
