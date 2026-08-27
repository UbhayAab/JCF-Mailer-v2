/* =========================================================================
   Jarurat Mail - webmail screen
   Talks only to /api/mail/**. Message bodies arrive already sanitised and
   already wrapped in a standalone document; this file never assembles sender
   HTML into markup of its own. See MailHtmlSanitizer for the reasoning.
   ========================================================================= */

const $ = id => document.getElementById(id);

function esc(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function can(permission) { return PERMS.indexOf(permission) >= 0; }

function toast(message, bad) {
  const el = document.createElement('div');
  el.className = 'toast' + (bad ? ' bad' : '');
  el.textContent = message;
  $('toasts').appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, 4200);
  setTimeout(() => el.remove(), 4600);
}

/* ---------- transport ---------- */

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

/**
 * The mailbox is locked, which is not the same as being signed out.
 * 401 means the console session died and the browser belongs at /login.
 * 409 with locked:true means the console session is fine and only the mail
 * server still wants its password, so the answer is a prompt, not a redirect.
 */
class Locked extends Error {}

function readBody(res) {
  return res.json().catch(() => null);   // an error page is not always json
}

function raise(res, payload) {
  if (res.status === 409 && payload && payload.locked) {
    throw new Locked(payload.error || 'Your mailbox is not open on this device.');
  }
  throw new Error((payload && payload.error) || 'Request failed');
}

async function api(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');
  const payload = await readBody(res);
  if (!res.ok) raise(res, payload);
  return payload;
}

async function post(url, params) {
  const body = new URLSearchParams();
  Object.keys(params || {}).forEach(k => {
    if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]);
  });
  const res = await fetch(url, { method: 'POST', body, headers: { 'X-XSRF-TOKEN': csrfToken() } });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');
  const payload = await readBody(res);
  if (!res.ok) raise(res, payload);
  return payload || {};
}

/** True when the failure was handled by putting the unlock prompt on screen. */
function handled(e) {
  if (!(e instanceof Locked)) return false;
  openUnlock(e.message);
  return true;
}

/* ---------- state ---------- */

const S = {
  mailbox: '',
  folders: [],
  folderId: null,
  folderName: 'Inbox',
  folderRole: '',
  messages: [],
  total: 0,
  offset: 0,
  limit: 40,
  selected: null,
  query: '',
  imagesFor: null      // the one message id the reader is currently showing images for
};

const FOLDER_ICON = {
  inbox: '✉', sent: '➤', drafts: '✎',
  junk: '⚑', trash: '✖', archive: '⌸'
};

/* ---------- time ---------- */

function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d)) return '';
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
  const days = Math.floor((now - d) / 86400000);
  if (days < 1) return 'Yesterday';
  if (days < 7) return d.toLocaleDateString('en-IN', { weekday: 'short' });
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

function fullWhen(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? '' : d.toLocaleString('en-IN', {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false
  });
}

function bytes(n) {
  if (!n) return '';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return Math.round(n / 1024) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

/* ---------- folders ---------- */

/**
 * A failure and an empty folder used to render as the same grey circle, so
 * "the mail server is down" was indistinguishable from "there is no mail".
 * These two builders keep the difference visible: a failure says so in words
 * and in colour and offers the one action that helps.
 */
function errState(message, retry) {
  return '<div class="errstate" role="alert">'
    + '<span class="big" aria-hidden="true">&#9888;</span>'
    + '<span class="head">Could not load this</span>'
    + '<span class="why">' + esc(message || 'The mail server did not answer.') + '</span>'
    + (retry ? '<button class="btn sm" type="button" data-retry="' + esc(retry) + '">Try again</button>' : '')
    + '</div>';
}

function loadState(label) {
  return '<div class="loadstate"><span class="spin" aria-hidden="true"></span>'
    + '<span>' + esc(label || 'Loading') + '</span></div>';
}

/* One delegated handler, because the blocks above are written into three
   different panes and each is replaced whenever the pane reloads. */
document.addEventListener('click', function (e) {
  const b = e.target.closest && e.target.closest('[data-retry]');
  if (!b) return;
  const what = b.getAttribute('data-retry');
  if (what === 'folders') loadFolders();
  else if (what === 'messages') loadMessages(true);
});

async function loadFolders() {
  let data;
  try {
    data = await api('/api/mail/folders');
  } catch (e) {
    if (handled(e)) return;
    $('folders').innerHTML = errState(e.message, 'folders');
    $('list').innerHTML = '';
    $('reader').innerHTML = errState('The mailbox could not be opened.', 'folders');
    return;
  }

  S.folders = data.folders || [];
  if (data.mailbox) {
    S.mailbox = data.mailbox;
    $('railEmail').textContent = data.mailbox;
    $('railAvatar').textContent = (data.mailbox[0] || '?').toUpperCase();
    $('composeFrom').textContent = data.mailbox;
  }
  renderFolders();

  const inbox = S.folders.find(f => f.role === 'inbox') || S.folders[0];
  if (inbox && !S.folderId) selectFolder(inbox.id, inbox.name, inbox.role);
  else if (S.folderId) loadMessages(true);
}

function renderFolders() {
  const unread = S.folders.reduce((sum, f) => sum + (f.role === 'inbox' ? f.unread : 0), 0);
  $('railUnread').textContent = unread;

  $('folders').innerHTML = S.folders.map(f => {
    const icon = FOLDER_ICON[f.role] || '●';
    const count = f.unread > 0 ? f.unread : (f.total || '');
    return '<button type="button" class="fold' + (f.id === S.folderId ? ' on' : '') + '"'
      + ' data-id="' + esc(f.id) + '" data-name="' + esc(f.name) + '" data-role="' + esc(f.role) + '">'
      + '<span aria-hidden="true">' + icon + '</span>'
      + '<span class="fname">' + esc(f.name) + '</span>'
      + '<span class="ct">' + esc(count) + '</span></button>';
  }).join('');
}

function selectFolder(id, name, role) {
  S.folderId = id;
  S.folderName = name;
  S.folderRole = role || '';
  S.query = '';
  $('q').value = '';
  S.offset = 0;
  S.selected = null;
  $('paneTitle').textContent = name;
  renderFolders();
  loadMessages(true);
  $('reader').innerHTML = '<div class="empty"><span class="big">✉</span>Select a message to read it</div>';
  showPane(false);
}

/* ---------- message list ---------- */

async function loadMessages(reset) {
  if (reset) { S.offset = 0; S.messages = []; $('list').innerHTML = loadingRow(); }
  let data;
  try {
    data = S.query
      ? await api('/api/mail/search?q=' + encodeURIComponent(S.query)
          + '&offset=' + S.offset + '&limit=' + S.limit)
      : await api('/api/mail/messages?folder=' + encodeURIComponent(S.folderId)
          + '&role=' + encodeURIComponent(S.folderRole)
          + '&offset=' + S.offset + '&limit=' + S.limit);
  } catch (e) {
    if (handled(e)) return;
    $('list').innerHTML = errState(e.message, 'messages');
    return;
  }
  S.messages = S.messages.concat(data.messages || []);
  S.total = data.total || 0;
  renderList();
}

function loadingRow() {
  return loadState('Loading messages');
}

function renderList() {
  if (!S.messages.length) {
    $('list').innerHTML = '<div class="empty"><span class="big">○</span>'
      + (S.query ? 'Nothing matched that search' : 'This folder is empty') + '</div>';
    return;
  }
  const rows = S.messages.map(m => {
    const marks = (m.hasAttachment ? '\u{1F4CE} ' : '') + (m.flagged ? '★' : '');
    return '<button type="button" class="msg' + (m.seen ? '' : ' unread')
      + (m.id === S.selected ? ' on' : '') + '" data-id="' + esc(m.id) + '">'
      + '<div class="r1"><div class="from">' + esc(m.from.display || m.from.email) + '</div>'
      + '<div class="when">' + esc(when(m.receivedAt)) + '</div></div>'
      + '<div class="subj">' + (marks ? '<span class="marks">' + marks + '</span>' : '')
      + esc(m.subject || '(no subject)') + '</div>'
      + '<div class="prev">' + esc(m.preview) + '</div></button>';
  }).join('');

  const more = S.messages.length < S.total
    ? '<div class="listfoot"><button class="btn sm" type="button" id="btnMore">Load more</button>'
      + '<span>' + S.messages.length + ' of ' + S.total + '</span></div>'
    : '<div class="listfoot"><span>' + S.messages.length + ' message'
      + (S.messages.length === 1 ? '' : 's') + '</span></div>';

  $('list').innerHTML = rows + more;
  const btn = $('btnMore');
  if (btn) btn.addEventListener('click', () => { S.offset += S.limit; loadMessages(false); });
}

/* ---------- reader ---------- */

async function openMessage(id, withImages) {
  S.selected = id;
  renderList();
  $('reader').innerHTML = loadState('Opening');

  let m;
  try {
    m = await api('/api/mail/message?id=' + encodeURIComponent(id)
      + '&images=' + (withImages ? 'true' : 'false') + '&theme=' + encodeURIComponent(theme()));
  } catch (e) {
    if (handled(e)) return;
    $('reader').innerHTML = errState(e.message, null);
    return;
  }
  S.imagesFor = withImages ? id : null;
  renderReader(m);

  // Marking read is a separate call so that opening a message stays a plain GET.
  const row = S.messages.find(x => x.id === id);
  if (row && !row.seen) {
    try {
      await post('/api/mail/read', { id: id, value: true });
      row.seen = true;
      renderList();
      loadFolderCounts();
    } catch (e) { /* the body is on screen; a failed receipt is not worth a toast */ }
  }
}

function renderReader(m) {
  const head = document.createElement('div');
  head.className = 'rhead';

  // The href carries the message id as well as the blob id, because the server
  // re-reads the message to prove this blob really belongs to it.
  const attachments = (m.attachments || []).map(a =>
    '<a class="att" href="/api/mail/attachment?id=' + encodeURIComponent(m.id)
    + '&blobId=' + encodeURIComponent(a.blobId || '') + '" download="' + esc(a.name || 'attachment') + '">'
    + esc(a.name || 'attachment')
    + (a.size ? ' <span class="muted">' + esc(bytes(a.size)) + '</span>' : '') + '</a>').join('');

  const recipients = (m.to || []).map(a => esc(a.email)).join(', ');
  const initial = ((m.from.display || m.from.email || '?')[0] || '?').toUpperCase();

  head.innerHTML =
    '<h2>' + esc(m.subject || '(no subject)') + '</h2>'
    + '<div class="rmeta">'
    + '<div class="avatar">' + esc(initial) + '</div>'
    + '<div class="who"><b>' + esc(m.from.display || m.from.email) + '</b>'
    + '<div class="muted" style="font-size:12.5px">' + esc(m.from.email)
    + (recipients ? ' to ' + recipients : '') + ' · ' + esc(fullWhen(m.receivedAt)) + '</div></div>'
    + '<span class="spacer"></span>'
    + '<button class="btn sm btnback" type="button" data-act="back">Back</button>'
    + '<button class="btn sm" type="button" data-act="reply">Reply</button>'
    + '<button class="btn sm" type="button" data-act="flag">' + (m.flagged ? 'Unstar' : 'Star') + '</button>'
    + (can('MAIL_SEND') ? movePicker() : '')
    + (can('MAIL_SEND') ? '<button class="btn sm" type="button" data-act="delete">Delete</button>' : '')
    + '</div>'
    + (m.blockedImages > 0
        ? '<div class="banner">⚠ ' + m.blockedImages + ' remote image'
          + (m.blockedImages === 1 ? '' : 's') + ' blocked, because loading them tells the '
          + 'sender you opened this. <button class="btn sm" type="button" data-act="images">Show images</button></div>'
        : '')
    + (attachments ? '<div class="atts">' + attachments + '</div>' : '');

  const reader = $('reader');
  reader.innerHTML = '';
  reader.appendChild(head);
  mountBody(reader, m.bodyHtml);

  head.addEventListener('change', e => {
    if (e.target.getAttribute('data-act') !== 'move') return;
    const target = e.target.value;
    if (target) moveMessage(m.id, target);
  });

  head.addEventListener('click', e => {
    const act = e.target.getAttribute && e.target.getAttribute('data-act');
    if (!act) return;
    if (act === 'images') openMessage(m.id, true);
    if (act === 'flag') toggleFlag(m.id, !m.flagged);
    if (act === 'delete') removeMessage(m.id);
    if (act === 'reply') replyTo(m);
    if (act === 'back') showPane(false);
  });
  showPane(true);
}

/**
 * Layer 2 of the four described in MailHtmlSanitizer.
 *
 * The sandbox attribute carries NO allow-scripts, so nothing in a message can
 * execute, and NO allow-same-origin, so the frame runs on an opaque origin and
 * cannot reach our cookies, our DOM or our API even if it somehow ran code. Forms
 * are absent from the list too, so a phishing login box has nowhere to post.
 * allow-popups and allow-popups-to-escape-sandbox are present for one reason only:
 * a genuine link in the message should open in an ordinary tab.
 *
 * srcdoc is assigned as a DOM PROPERTY. It is never concatenated into an HTML
 * string, because srcdoc is itself an attribute and building it as text would need
 * a second, separate round of escaping that is easy to get wrong and impossible to
 * notice when it is wrong.
 */
function mountBody(container, doc) {
  const frame = document.createElement('iframe');
  frame.className = 'rframe';
  frame.setAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox');
  frame.setAttribute('referrerpolicy', 'no-referrer');
  frame.setAttribute('title', 'Message body');
  container.appendChild(frame);
  frame.srcdoc = doc;
}

/** Below 1100px there is only room for one pane, so the two take turns. */
function showPane(reading) {
  $('mailGrid').classList.toggle('reading', !!reading);
}

function theme() {
  const explicit = document.documentElement.getAttribute('data-theme');
  if (explicit) return explicit;
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/* ---------- actions ---------- */

async function toggleFlag(id, value) {
  try {
    await post('/api/mail/flag', { id: id, value: value });
    const row = S.messages.find(x => x.id === id);
    if (row) row.flagged = value;
    renderList();
    openMessage(id, S.imagesFor === id);
  } catch (e) { if (!handled(e)) toast(e.message, true); }
}

function movePicker() {
  const options = S.folders
    .filter(f => f.id !== S.folderId)
    .map(f => '<option value="' + esc(f.id) + '">' + esc(f.name) + '</option>').join('');
  if (!options) return '';
  return '<select class="btn sm" data-act="move" aria-label="Move to folder">'
    + '<option value="">Move to...</option>' + options + '</select>';
}

async function moveMessage(id, folderId) {
  try {
    await post('/api/mail/move', { id: id, folder: folderId });
    S.messages = S.messages.filter(x => x.id !== id);
    S.total = Math.max(0, S.total - 1);
    S.selected = null;
    renderList();
    $('reader').innerHTML = '<div class="empty"><span class="big">✉</span>Message moved</div>';
    showPane(false);
    loadFolderCounts();
    toast('Moved.');
  } catch (e) { if (!handled(e)) toast(e.message, true); }
}

async function removeMessage(id) {
  if (!window.confirm('Delete this message?')) return;
  try {
    await post('/api/mail/delete', { id: id });
    S.messages = S.messages.filter(x => x.id !== id);
    S.total = Math.max(0, S.total - 1);
    S.selected = null;
    renderList();
    $('reader').innerHTML = '<div class="empty"><span class="big">✉</span>Message deleted</div>';
    showPane(false);
    loadFolderCounts();
    toast('Deleted.');
  } catch (e) { if (!handled(e)) toast(e.message, true); }
}

async function loadFolderCounts() {
  try {
    const data = await api('/api/mail/folders');
    S.folders = data.folders || [];
    renderFolders();
  } catch (e) { /* the counts are decoration, not the point of the screen */ }
}

/* ---------- opening the mailbox ---------- */

/**
 * The password typed here goes straight into one POST and is wiped from the field
 * as soon as that POST returns. It is never put in S, never in localStorage, and
 * never in a URL, so it cannot end up in a bookmark, a back button or a proxy log.
 */
function openUnlock(reason) {
  if (reason) $('unlockWhy').textContent = reason;
  $('unlockSheet').classList.add('open');
  ($('uAddress').value ? $('uPassword') : $('uAddress')).focus();
}

async function unlockMailbox() {
  const btn = $('btnUnlock');
  const address = $('uAddress').value.trim();
  const password = $('uPassword').value;
  if (!address || !password) { toast('Both fields are needed.', true); return; }

  btn.disabled = true;
  try {
    await post('/api/mail/unlock', { address: address, password: password });
    $('uPassword').value = '';
    $('unlockSheet').classList.remove('open');
    S.folderId = null;
    await loadFolders();
  } catch (e) {
    $('uPassword').value = '';
    // A rejected password comes back as 409 locked, which is exactly the state the
    // prompt is already in, so it is a message rather than a fresh prompt.
    toast(e instanceof Locked ? 'That mailbox or password was not accepted.' : e.message, true);
  } finally {
    btn.disabled = false;
  }
}

async function lockMailbox() {
  try { await post('/api/mail/lock', {}); } catch (e) { /* closing can only fail into closed */ }
  S.folders = [];
  S.messages = [];
  S.folderId = null;
  S.selected = null;
  $('folders').innerHTML = '';
  $('list').innerHTML = '';
  $('reader').innerHTML = '<div class="empty"><span class="big">✉</span>Mailbox closed</div>';
  $('railUnread').textContent = '0';
  openUnlock('Your mailbox is closed on this device.');
}

/* ---------- compose ---------- */

function openCompose(to, subject, body) {
  $('cTo').value = to || '';
  $('cCc').value = '';
  $('cSubject').value = subject || '';
  $('cBody').value = body || '';
  $('composeSheet').classList.add('open');
  $('cTo').focus();
}

function replyTo(m) {
  const subject = /^re:/i.test(m.subject || '') ? m.subject : 'Re: ' + (m.subject || '');
  const quoted = '\n\nOn ' + fullWhen(m.receivedAt) + ', '
    + (m.from.display || m.from.email) + ' wrote:\n> (original message)';
  openCompose(m.from.email, subject, quoted);
}

async function sendMessage() {
  const btn = $('btnSend');
  btn.disabled = true;
  try {
    const result = await post('/api/mail/send', {
      to: $('cTo').value,
      cc: $('cCc').value,
      subject: $('cSubject').value,
      body: $('cBody').value
    });
    $('composeSheet').classList.remove('open');
    toast(result.message || 'Sent.');
  } catch (e) {
    if (!handled(e)) toast(e.message, true);
  } finally {
    btn.disabled = false;
  }
}

/* ---------- wiring ---------- */

$('folders').addEventListener('click', e => {
  const b = e.target.closest('.fold');
  if (b) selectFolder(b.dataset.id, b.dataset.name, b.dataset.role);
});

$('list').addEventListener('click', e => {
  const b = e.target.closest('.msg');
  if (b) openMessage(b.dataset.id, false);
});

let searchTimer = null;
$('q').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    S.query = $('q').value.trim();
    $('paneTitle').textContent = S.query ? 'Search' : S.folderName;
    loadMessages(true);
  }, 320);
});

$('btnRefresh').addEventListener('click', () => { loadFolders(); loadMessages(true); });
$('btnCompose').addEventListener('click', () => openCompose());
$('railCompose').addEventListener('click', () => openCompose());
$('railLock').addEventListener('click', lockMailbox);
$('btnCancel').addEventListener('click', () => $('composeSheet').classList.remove('open'));
$('btnSend').addEventListener('click', sendMessage);
$('btnUnlock').addEventListener('click', unlockMailbox);
$('uPassword').addEventListener('keydown', e => { if (e.key === 'Enter') unlockMailbox(); });
$('composeSheet').addEventListener('click', e => {
  if (e.target === $('composeSheet')) $('composeSheet').classList.remove('open');
});
document.addEventListener('keydown', e => {
  // The unlock sheet deliberately has no dismiss: there is nothing behind it.
  if (e.key === 'Escape') $('composeSheet').classList.remove('open');
});

if (ME.email) {
  $('railEmail').textContent = ME.email;
  $('railAvatar').textContent = (ME.email[0] || '?').toUpperCase();
}
if (!can('MAIL_SEND')) {
  $('btnCompose').style.display = 'none';
  $('railCompose').style.display = 'none';
}

/* ---------- boot ---------- */

(async function start() {
  let state;
  try {
    state = await api('/api/mail/status');
  } catch (e) {
    $('reader').innerHTML = errState(e.message, null);
    return;
  }
  if (state.unlocked) {
    loadFolders();
    // The console sidebar links here as /mail#compose, so honour the hash once
    // the mailbox is known to be open. Composing into a locked mailbox would
    // only fail at send time.
    if (location.hash === '#compose' && can('MAIL_SEND')) openCompose();
    return;
  }
  $('uAddress').value = state.suggested || ME.email || '';
  openUnlock();
})();
