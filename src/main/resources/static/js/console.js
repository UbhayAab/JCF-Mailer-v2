/* =========================================================================
   JCF Campaign Studio console
   ========================================================================= */

const $ = id => document.getElementById(id);

function esc(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function attr(s) { return esc(s).replace(/'/g, '&#39;'); }
function num(n) { return Number(n || 0).toLocaleString('en-IN'); }
function can(permission) { return PERMS.indexOf(permission) >= 0; }

function toast(message, kind) {
  const el = document.createElement('div');
  el.className = 'toast ' + (kind || '');
  el.textContent = message;
  $('toasts').appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, 4400);
  setTimeout(() => el.remove(), 4800);
}

/* ---------- transport ---------- */

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : '';
}

async function api(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

async function post(url, params) {
  const body = new URLSearchParams();
  Object.keys(params || {}).forEach(k => {
    if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]);
  });
  const res = await fetch(url, { method: 'POST', body, headers: { 'X-XSRF-TOKEN': csrfToken() } });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');

  let payload = {};
  try { payload = await res.json(); } catch (e) { /* some endpoints return plain text */ }
  if (!res.ok) throw new Error(payload.error || 'Request failed');
  return payload;
}

async function postJson(url, payload, method) {
  const res = await fetch(url, {
    method: method || 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
    body: payload === undefined ? undefined : JSON.stringify(payload)
  });
  if (res.status === 401) { location.href = '/login'; throw new Error('signed out'); }
  if (res.status === 403) throw new Error('Your role does not allow that.');
  if (res.status === 204) return {};
  let body = {};
  try { body = await res.json(); } catch (e) { /* no body on 204 and friends */ }
  if (!res.ok) throw new Error(body.error || 'Request failed');
  return body;
}

const debounceTimers = {};
function debounce(fn, ms) {
  return function () {
    clearTimeout(debounceTimers[fn.name]);
    debounceTimers[fn.name] = setTimeout(fn, ms);
  };
}

function statusPill(status) {
  const map = {
    DRAFT: 'pill-draft', SCHEDULED: 'pill-scheduled', SENDING: 'pill-sending',
    SENT: 'pill-sent', FAILED: 'pill-failed', PAUSED: 'pill-draft', CANCELLED: 'pill-draft',
    PENDING: 'pill-pending', SKIPPED: 'pill-skipped', SUPPRESSED: 'pill-skipped',
    SUBSCRIBED: 'pill-sent', UNSUBSCRIBED: 'pill-skipped', BOUNCED: 'pill-failed',
    COMPLAINED: 'pill-failed', BOUNCE: 'pill-failed', COMPLAINT: 'pill-failed',
    MANUAL: 'pill-draft', MARKETING: 'pill-info', TRANSACTIONAL: 'pill-scheduled'
  };
  return '<span class="pill ' + (map[status] || 'pill-draft') + '">' + esc(status) + '</span>';
}

function emptyRow(cols, message) {
  return '<tr><td colspan="' + cols + '"><div class="empty"><span class="big">&#9675;</span>'
       + esc(message) + '</div></td></tr>';
}

function pagerText(data) {
  return num(data.totalElements) + ' total, page ' + (data.page + 1)
       + ' of ' + Math.max(1, data.totalPages);
}

/* ---------- modals ---------- */
function openModal(id) { $(id).classList.add('open'); }
function closeModal(id) { $(id).classList.remove('open'); }
document.addEventListener('click', e => {
  if (e.target.classList && e.target.classList.contains('modal-backdrop')) e.target.classList.remove('open');
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') document.querySelectorAll('.modal-backdrop.open').forEach(m => m.classList.remove('open'));
});

/* ---------- permission gating ---------- */
function applyPermissions() {
  document.querySelectorAll('[data-perm]').forEach(el => {
    if (!can(el.dataset.perm)) el.style.display = 'none';
  });
  // Hide a section heading when every item under it is hidden
  document.querySelectorAll('.nav-group').forEach(group => {
    let sibling = group.nextElementSibling, anyVisible = false;
    while (sibling && sibling.classList.contains('nav-item')) {
      if (sibling.style.display !== 'none') anyVisible = true;
      sibling = sibling.nextElementSibling;
    }
    if (!anyVisible) group.style.display = 'none';
  });
  $('avatar').textContent = (ME.name || ME.email || '?').trim().charAt(0).toUpperCase();
}

/* ---------- routing ---------- */
document.querySelectorAll('.nav-item').forEach(item => {
  // Webmail lives on its own page, so those entries are anchors. Calling
  // go(undefined) on them would blank every view for the instant before the
  // browser navigates away.
  if (item.tagName === 'A') return;
  item.addEventListener('click', () => go(item.dataset.view));
});

const LOADERS = {
  overview: loadOverview,
  campaigns: loadCampaigns,
  composer: () => loadLists().then(fillListPickers),
  lists: loadLists,
  subscribers: () => loadLists().then(fillListPickers).then(loadSubscribers),
  suppression: loadSuppressions,
  verify: loadVerify,
  analytics: loadAnalytics,
  messagelog: loadMessageLog,
  templates: loadTemplates,
  transactional: () => { loadTransactional(); loadTxTemplates(); },
  team: () => { loadTeam(); loadRoles(); },
  apikeys: () => { loadKeys(); renderApiExample(); },
  audit: loadAudit,
  mailboxes: loadMailboxes,
  domains: loadDomains
};

function go(view) {
  document.querySelectorAll('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.view === view));
  document.querySelectorAll('.view').forEach(v => v.classList.toggle('active', v.id === 'view-' + view));
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (LOADERS[view]) LOADERS[view]();
}

/* =========================================================================
   Overview
   ========================================================================= */
async function loadOverview() {
  let d;
  try { d = await api('/api/overview'); } catch (e) { toast('Could not load the overview', 'err'); return; }

  // The server omits any metric this role may not see, so only build tiles for
  // fields that actually came back.
  const tiles = [];
  const px = (key, tile) => { if (d[key] !== undefined) tiles.push(tile); };
  px('subscribers', { label: 'Subscribers', value: num(d.subscribers), foot: num(d.subscribedCount) + ' mailable', cls: '' });
  px('lists', { label: 'Lists', value: num(d.lists), foot: num(d.campaigns || 0) + ' campaigns', cls: '' });
  px('totalSent', { label: 'Emails sent', value: num(d.totalSent), foot: num(d.campaignsScheduled) + ' scheduled', cls: 'good' });
  px('openRate', { label: 'Open rate', value: d.openRate + '%', foot: num(d.totalOpened) + ' opened', cls: 'accent' });
  px('clickRate', { label: 'Click rate', value: d.clickRate + '%', foot: num(d.totalClicked) + ' clicked', cls: 'accent' });
  px('transactionalTotal', { label: 'Transactional', value: num(d.transactionalTotal), foot: num(d.transactional24h) + ' in 24h', cls: '' });
  px('unsubscribed', { label: 'Unsubscribed', value: num(d.unsubscribed), foot: 'suppressed permanently', cls: 'warn' });
  px('bounced', { label: 'Bounced', value: num(d.bounced + d.complaints), foot: 'bounces and complaints', cls: 'danger' });

  $('kpis').innerHTML = tiles.map(t =>
    '<div class="kpi ' + t.cls + '"><div class="label">' + t.label + '</div>'
    + '<div class="value">' + t.value + '</div><div class="foot">' + t.foot + '</div></div>').join('');

  $('activityBody').innerHTML = d.activity.length ? d.activity.map(a =>
    '<tr><td><strong>' + esc(a.actor) + '</strong></td><td>' + esc(a.action.replace(/_/g, ' ').toLowerCase())
    + '</td><td class="truncate">' + esc(a.target) + '</td><td class="mono">' + esc(a.at) + '</td></tr>').join('')
    : emptyRow(4, can('AUDIT_READ') ? 'No activity recorded yet.' : 'Your role does not include the audit trail.');

  drawOverviewCharts(d);

  renderSes(d.ses);
  renderIdentity(d.identity);
  if (d.unsubscribed !== undefined) $('badgeSuppression').textContent = num(d.unsubscribed + d.bounced + d.complaints);
  if (d.campaigns !== undefined) $('badgeCampaigns').textContent = num(d.campaigns);
  if (d.lists !== undefined) $('badgeLists').textContent = num(d.lists);
}

function renderSes(s) {
  if (!s || !s.ok) {
    $('sesDot').className = 'status-dot bad';
    $('sesFoot').textContent = 'unreachable';
    $('sesPanel').innerHTML = '<div class="empty">SES did not respond.<br><span class="mono">'
      + esc(s && s.error ? s.error : 'unknown error') + '</span></div>';
    return;
  }
  const pct = s.max24Hour ? Math.min(100, (s.sentLast24Hours / s.max24Hour) * 100) : 0;
  const healthy = s.productionAccess && s.sendingEnabled && s.enforcementStatus === 'HEALTHY';
  $('sesDot').className = 'status-dot' + (healthy ? '' : ' bad');
  $('sesFoot').textContent = healthy ? 'production, healthy' : 'needs attention';

  $('sesPanel').innerHTML =
    row('Access level', s.productionAccess ? 'Production' : 'Sandbox', s.productionAccess ? 'var(--success)' : 'var(--warning)')
    + row('Reputation', s.enforcementStatus, s.enforcementStatus === 'HEALTHY' ? 'var(--success)' : 'var(--danger)')
    + row('Sending', s.sendingEnabled ? 'Enabled' : 'Paused', null)
    + row('Account limit', s.maxSendRate + '/sec', null)
    + row('We send at', s.configuredRate + '/sec', null)
    + row('Daily quota used', num(s.sentLast24Hours) + ' / ' + num(s.max24Hour), null)
    + '<div class="quota-track"><div class="quota-fill" style="width:' + pct.toFixed(1) + '%"></div></div>';
}

function renderIdentity(identity) {
  if (!identity || !identity.ok) {
    $('identityPanel').innerHTML = '<div class="empty">Could not read the domain identity.</div>';
    return;
  }
  const mailFromLive = identity.mailFromStatus === 'SUCCESS';
  let html =
      row('Domain', identity.domain, null)
    + row('Verified', identity.verified ? 'Yes' : 'No', identity.verified ? 'var(--success)' : 'var(--danger)')
    + row('DKIM', identity.dkimStatus, identity.dkimStatus === 'SUCCESS' ? 'var(--success)' : 'var(--warning)')
    + row('Custom MAIL FROM', identity.mailFromDomain || 'not set', null)
    + row('MAIL FROM status', identity.mailFromStatus || 'n/a',
          mailFromLive ? 'var(--success)' : 'var(--warning)');

  if (!mailFromLive && identity.mailFromDomain) {
    html += '<div class="alert warn" style="margin:14px 0 0">'
      + 'SPF is not aligned yet. Publish these two DNS records for <b>' + esc(identity.mailFromDomain) + '</b>:'
      + '<div class="mono" style="margin-top:8px;line-height:1.7">'
      + 'MX&nbsp; 10 feedback-smtp.ap-south-1.amazonses.com<br>'
      + 'TXT v=spf1 include:amazonses.com ~all</div></div>';
  }
  $('identityPanel').innerHTML = html;
}

function row(label, value, color) {
  return '<div class="health-row"><span class="k">' + esc(label) + '</span><span class="v"'
    + (color ? ' style="color:' + color + '"' : '') + '>' + esc(value) + '</span></div>';
}

/* =========================================================================
   Campaigns
   ========================================================================= */
let campaignCache = [];

async function loadCampaigns() {
  try { campaignCache = await api('/api/campaigns'); }
  catch (e) { toast('Could not load campaigns', 'err'); return; }

  $('badgeCampaigns').textContent = campaignCache.length;
  $('campaignBody').innerHTML = campaignCache.length ? campaignCache.map(c =>
    '<tr class="clickable" onclick="openCampaign(' + c.id + ')">'
    + '<td><strong>' + esc(c.name) + '</strong><br><span style="font-size:12px;color:var(--text-mute)">'
      + esc(c.subject || 'no subject') + '</span></td>'
    + '<td>' + esc(c.listName || '-') + '</td>'
    + '<td>' + statusPill(c.status) + '</td>'
    + '<td class="num">' + num(c.total) + '</td>'
    + '<td class="num">' + num(c.sent) + '</td>'
    + '<td class="num">' + num(c.opened) + ' <span style="color:var(--text-mute)">(' + c.openRate + '%)</span></td>'
    + '<td class="num">' + num(c.clicked) + ' <span style="color:var(--text-mute)">(' + c.clickRate + '%)</span></td>'
    + '<td class="num" style="color:' + (c.failed > 0 ? 'var(--danger)' : 'inherit') + '">' + num(c.failed) + '</td>'
    + '<td><button class="btn btn-sm">Open</button></td></tr>').join('')
    : emptyRow(9, 'No campaigns yet.');
}

/* =========================================================================
   Composer
   ========================================================================= */
let currentCampaignId = null;
let progressTimer = null;
let previewTimer = null;
// A sent or sending campaign is read only server side. The composer has to know,
// because Test and Send both auto-save first and would otherwise fail with an
// "already sent" error that has nothing to do with what the user just clicked.
let composerEditable = true;
let listCache = [];

function newCampaign() {
  currentCampaignId = null;
  ['cName', 'cSubject', 'cHtml', 'cFromName', 'cReplyTo', 'cPreheader', 'cTestTo'].forEach(id => $(id).value = '');
  $('cList').value = '';
  $('cTrackOpens').checked = true;
  $('cTrackClicks').checked = true;
  $('composerTitle').textContent = 'Composer';
  composerEditable = true;
  showComposerLock(null);
  $('composerStatus').textContent = 'DRAFT';
  $('composerStatus').className = 'pill pill-draft plain';
  $('audienceCount').textContent = 'no list';
  $('audienceNote').textContent = '';
  $('linkBody').innerHTML = emptyRow(3, 'No clicks recorded yet.');
  $('recipientBody').innerHTML = emptyRow(6, 'Nobody queued yet.');
  $('progressCard').style.display = 'none';
  $('wireOutput').textContent = 'Save the campaign to see this.';
  updatePreview();
  refreshTestFields();
  go('composer');
}

async function openCampaign(id) {
  let d;
  try { d = await api('/api/campaigns/' + id); }
  catch (e) { toast('Could not open that campaign', 'err'); return; }

  currentCampaignId = id;
  $('cName').value = d.name || '';
  $('cSubject').value = d.subject || '';
  $('cHtml').value = d.htmlBody || '';
  $('cFromName').value = d.fromName || '';
  $('cReplyTo').value = d.replyTo || '';
  $('cPreheader').value = d.preheader || '';
  $('cTrackOpens').checked = !!d.trackOpens;
  $('cTrackClicks').checked = !!d.trackClicks;
  $('composerTitle').textContent = d.name;
  $('composerStatus').textContent = d.status;
  $('composerStatus').className = 'pill plain ' + (statusPill(d.status).match(/pill-[a-z]+/) || ['pill-draft'])[0];

  await loadLists();
  fillListPickers();
  $('cList').value = d.listId || '';
  onListChange();
  if (d.mailable !== undefined) {
    $('audienceCount').textContent = num(d.mailable) + ' mailable';
  }

  $('linkBody').innerHTML = (d.links && d.links.length) ? d.links.map(l =>
    '<tr><td><span class="truncate mono" title="' + attr(l.url) + '">' + esc(l.url) + '</span></td>'
    + '<td class="num">' + num(l.unique) + '</td><td class="num">' + num(l.clicks) + '</td></tr>').join('')
    : emptyRow(3, 'No clicks recorded yet.');

  const editable = d.editable;
  composerEditable = editable;
  ['cName', 'cSubject', 'cHtml', 'cFromName', 'cReplyTo', 'cPreheader', 'cList'].forEach(f => $(f).disabled = !editable);
  showComposerLock(editable ? null : d.status);

  updatePreview();
  refreshTestFields();
  recipientPage = 0;
  loadRecipients();
  pollProgress();
  go('composer');
}

function switchComposerTab(tab) {
  document.querySelectorAll('#view-composer .tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));
  $('tab-code').style.display = tab === 'code' ? '' : 'none';
  $('tab-preview').style.display = tab === 'preview' ? '' : 'none';
  $('tab-wire').style.display = tab === 'wire' ? '' : 'none';
  if (tab === 'preview') updatePreview();
  if (tab === 'wire') loadWire();
}

async function loadWire() {
  if (!currentCampaignId) { $('wireOutput').textContent = 'Save the campaign first.'; return; }
  // Carries the same test values, so this tab shows the message that was actually
  // tested rather than a second one built from different samples.
  const params = new URLSearchParams();
  const values = testMergeValues();
  Object.keys(values).forEach(tag => params.set('merge.' + tag, values[tag]));
  const query = params.toString();
  try {
    const res = await fetch('/api/campaigns/' + currentCampaignId + '/rendered' + (query ? '?' + query : ''));
    $('wireOutput').textContent = await res.text();
  } catch (e) { $('wireOutput').textContent = 'Could not render: ' + e.message; }
}

function schedulePreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(() => { updatePreview(); refreshTestFields(); }, 400);
}

/* =========================================================================
   Merge tags
   -------------------------------------------------------------------------
   MERGE_TAG_RE must stay identical to MergeTags.PATTERN in MergeTags.java.
   The composer has to find tags in text the server has not been shown yet -
   the whole point is that the fields appear while you type - so the pattern
   genuinely lives in both places. Change one, change the other.
   ========================================================================= */

const MERGE_TAG_RE = /\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g;

// The sender owns these two. The unsubscribe URL is generated per recipient at
// send time, so offering to fill it in would be a lie, and accepting a value for
// it would break the one-click unsubscribe Gmail requires of bulk senders.
const RESERVED_TAGS = ['UNSUBSCRIBE_LINK', 'TRACK'];

function mergeTagsIn(sources) {
  const found = [];
  sources.forEach(source => {
    if (!source) return;
    MERGE_TAG_RE.lastIndex = 0;
    let m;
    while ((m = MERGE_TAG_RE.exec(source)) !== null) {
      const tag = m[1].toUpperCase();
      if (RESERVED_TAGS.indexOf(tag) < 0 && found.indexOf(tag) < 0) found.push(tag);
    }
  });
  return found;
}

function composerMergeTags() {
  return mergeTagsIn([$('cSubject').value, $('cPreheader').value, $('cHtml').value]);
}

/** Whatever is currently typed into the test panel, tag -> value. */
function testMergeValues() {
  const out = {};
  document.querySelectorAll('#cTestFields .merge-input').forEach(input => {
    if (input.value !== '') out[input.dataset.tag] = input.value;
  });
  return out;
}

/* Test values are a convenience, not data. localStorage keeps them on the one
   machine that typed them and never has to be cleaned up server side. */
function testValueStoreKey() { return 'jcf.testvalues.' + (currentCampaignId || 'draft'); }

function rememberTestValues() {
  try { localStorage.setItem(testValueStoreKey(), JSON.stringify(testMergeValues())); }
  catch (e) { /* private mode, or the quota is full. Losing them is survivable. */ }
}

function storedTestValues() {
  try { return JSON.parse(localStorage.getItem(testValueStoreKey()) || '{}'); }
  catch (e) { return {}; }
}

/**
 * Rebuilds one labelled input per merge tag the creative actually uses.
 * Values already typed win over remembered ones, so a rebuild triggered by an
 * unrelated keystroke in the body never wipes what you were about to test with.
 */
function refreshTestFields() {
  const tags = composerMergeTags();
  const wrap = $('cTestMerge');
  if (!wrap) return;

  if (!tags.length) {
    wrap.style.display = 'none';
    $('cTestFields').innerHTML = '';
    return;
  }
  wrap.style.display = '';

  const typed = testMergeValues();
  const remembered = storedTestValues();
  $('cTestFields').innerHTML = tags.map(tag => {
    const value = typed[tag] !== undefined ? typed[tag] : (remembered[tag] || '');
    return '<label class="field merge-field"><span>{{' + esc(tag) + '}}</span>'
      + '<input class="input merge-input" data-tag="' + attr(tag) + '"'
      + ' value="' + attr(value) + '" placeholder="' + esc(tag.toLowerCase().replace(/_/g, ' ')) + '"'
      + ' oninput="rememberTestValues()"></label>';
  }).join('');

  $('cTestNote').textContent = tags.length + (tags.length === 1 ? ' merge tag' : ' merge tags')
    + ' in this creative. Anything left blank is filled with sample data, because a blank in a '
    + 'test looks exactly like a tag that failed to substitute.';
}

/** Asks the server for its sample value per tag, so console and sender agree. */
async function fillSampleMergeValues() {
  try {
    const res = await post('/api/campaigns/merge-tags', {
      subject: $('cSubject').value, preheader: $('cPreheader').value,
      htmlBody: $('cHtml').value, testAddress: $('cTestTo').value.trim()
    });
    const samples = {};
    (res.tags || []).forEach(row => { samples[row.tag] = row.sample; });
    document.querySelectorAll('#cTestFields .merge-input').forEach(input => {
      if (samples[input.dataset.tag] !== undefined) input.value = samples[input.dataset.tag];
    });
    rememberTestValues();
    updatePreview();
  } catch (e) { toast(e.message, 'err'); }
}

/** Shows the preview and the on-the-wire HTML built from the typed test values. */
async function previewWithTestValues() {
  switchComposerTab('preview');
  updatePreview();
  refreshTestFields();
}

// The server uppercases a merge key before looking it up, so {{first_name}} and
// {{FIRST_NAME}} both resolve in a real send. Matching that here matters: a preview
// that shows a raw {{first_name}} while the sent mail is fine sends people hunting
// for a bug that does not exist.
const PREVIEW_SAMPLE = {
  NAME: 'Dr. Sharma',
  FIRST_NAME: 'Dr. Sharma',
  LAST_NAME: 'Sharma',
  EMAIL: 'doctor@example.com',
  UNSUBSCRIBE_LINK: '#'
};

function updatePreview() {
  // Anything typed into the test panel wins, so the preview and the test message
  // are the same message. Without this the preview quietly showed "Dr. Sharma"
  // while the test that just landed in your inbox said something else.
  const typed = testMergeValues();
  const body = $('cHtml').value
    .replace(/\{\{TRACK:(.*?)\}\}/g, '$1')
    .replace(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g, (whole, key) => {
      const k = key.toUpperCase();
      if (Object.prototype.hasOwnProperty.call(typed, k) && typed[k] !== '') return typed[k];
      // Unknown tags stay visible on purpose: that is a real mistake worth seeing
      // before you send, and the server would render it as an empty string.
      return Object.prototype.hasOwnProperty.call(PREVIEW_SAMPLE, k) ? PREVIEW_SAMPLE[k] : whole;
    });
  const pre = $('cPreheader').value;
  const banner = '<div style="font:13px/1.4 system-ui;padding:10px 14px;background:#f3f4f6;'
    + 'border-bottom:1px solid #e5e7eb;color:#374151"><b>' + esc($('cSubject').value || 'No subject')
    + '</b>' + (pre ? ' <span style="color:#9ca3af">- ' + esc(pre) + '</span>' : '') + '</div>';
  $('previewFrame').srcdoc = banner + body;
}

/**
 * Explains a greyed out composer. Without this the fields are simply dead and
 * nothing on screen says why, which reads as the page being broken.
 */
function showComposerLock(status) {
  let bar = $('composerLock');
  if (!status) { if (bar) bar.remove(); return; }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'composerLock';
    bar.style.cssText = 'margin:0 0 14px;padding:11px 15px;border-radius:6px;font-size:13.5px;'
      + 'background:rgba(234,179,8,.12);border:1px solid rgba(234,179,8,.45);color:#eab308;'
      + 'display:flex;gap:10px;align-items:center;flex-wrap:wrap';
    const host = $('view-composer');
    host.insertBefore(bar, host.firstChild.nextSibling || host.firstChild);
  }
  bar.innerHTML = '<b>This campaign is ' + esc(String(status).toLowerCase()) + ', so it can no longer be edited.</b>'
    + '<span style="opacity:.85">You can still preview it, send yourself a test, and read its results.</span>'
    + '<button class="btn" id="composerDuplicate" style="margin-left:auto">Duplicate to edit</button>';
  $('composerDuplicate').onclick = duplicateCampaign;
}

/** Copies a locked campaign into a fresh draft so the wording can be reused. */
async function duplicateCampaign() {
  const name = ($('cName').value.trim() || 'Campaign') + ' (copy)';
  const html = $('cHtml').value, subject = $('cSubject').value,
        pre = $('cPreheader').value, from = $('cFromName').value, reply = $('cReplyTo').value;
  currentCampaignId = null;
  composerEditable = true;
  showComposerLock(null);
  ['cName','cSubject','cHtml','cFromName','cReplyTo','cPreheader','cList'].forEach(f => $(f).disabled = false);
  $('cName').value = name; $('cSubject').value = subject; $('cHtml').value = html;
  $('cPreheader').value = pre; $('cFromName').value = from; $('cReplyTo').value = reply;
  if (await saveCampaign(false)) toast('Copied into a new draft', 'ok');
}

function setPreviewWidth(mode) { $('previewFrame').classList.toggle('mobile', mode === 'mobile'); }

/* =========================================================================
   Composer audience: upload or paste
   -------------------------------------------------------------------------
   Everything ends up as a CSV file part, including pasted text, so there is
   exactly one ingestion path on the server. A second parser for pasted data
   would drift from the file one and the drift would only show up on somebody
   else's list.
   ========================================================================= */

let composerFile = null;        // the File chosen, or a Blob built from a paste
let reviewState = null;         // last /import/review response
let reviewMapping = {};         // column index -> field, what the dropdowns say

function switchAudienceTab(tab) {
  document.querySelectorAll('#view-composer .tab[data-atab]')
    .forEach(t => t.classList.toggle('active', t.dataset.atab === tab));
  $('atab-list').style.display = tab === 'list' ? '' : 'none';
  $('atab-upload').style.display = tab === 'upload' ? '' : 'none';
}

(function wireComposerDropzone() {
  document.addEventListener('DOMContentLoaded', () => {
    const zone = $('composerDrop'), input = $('composerCsv');
    if (!zone || !input) return;
    zone.addEventListener('click', () => input.click());
    zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('over'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('over'));
    zone.addEventListener('drop', e => {
      e.preventDefault();
      zone.classList.remove('over');
      if (e.dataTransfer.files.length) acceptComposerFile(e.dataTransfer.files[0]);
    });
    input.addEventListener('change', () => { if (input.files.length) acceptComposerFile(input.files[0]); });
  });
})();

function acceptComposerFile(file) {
  composerFile = file;
  $('composerPaste').value = '';
  $('pasteVerdict').innerHTML = '<b>' + esc(file.name) + '</b> selected, '
    + num(Math.round(file.size / 1024)) + ' KB.';
  $('composerImportBtn').disabled = false;
}

/**
 * Works out what was pasted and turns it into proper CSV.
 *
 * The order of these tests matters. A single line of comma separated addresses
 * and a one row spreadsheet selection look identical to a delimiter counter, so
 * the address test has to run first and has to require that every token really
 * is an address before it claims the line is a list.
 */
function detectPaste() {
  const box = $('composerPaste');
  const verdict = $('pasteVerdict');
  const raw = box.value.replace(/[﻿​]/g, '').replace(/\r\n?/g, '\n').replace(/\n+$/, '');

  composerFile = null;
  $('composerImportBtn').disabled = true;
  if (!raw.trim()) { verdict.textContent = ''; return; }

  if (raw.length > 4000000) {
    verdict.innerHTML = '<span style="color:var(--warning)">That is too much to paste. '
      + 'Save it as a CSV and upload the file instead.</span>';
    return;
  }
  const lines = raw.split('\n').filter(l => l.trim() !== '');
  if (lines.length > 50000) {
    verdict.innerHTML = '<span style="color:var(--warning)">50,000 addresses is the paste limit. '
      + 'Upload it as a CSV instead.</span>';
    return;
  }

  // 1. One line of separated addresses.
  if (lines.length === 1 && /[,;]/.test(lines[0])) {
    const parts = lines[0].split(/[,;]/).map(p => p.trim()).filter(Boolean);
    const addresses = parts.map(extractAddress).filter(Boolean);
    if (addresses.length >= 2 && addresses.length === parts.length) {
      return usePastedCsv(addresses.map(a => [a.email, a.name]), ['email', 'name'],
        addresses.length + ' addresses detected, comma separated.');
    }
  }

  // 2. Tabular. The delimiter has to appear on most lines, not just somewhere,
  //    or a single address containing a comma turns a name list into a table.
  const sample = lines.slice(0, 20);
  const delimiter = ['\t', ';', ','].find(d =>
    sample.filter(l => splitOutsideQuotes(l, d).length > 1).length >= Math.ceil(sample.length / 2));
  if (delimiter) {
    const label = delimiter === '\t' ? 'Tab' : delimiter === ';' ? 'Semicolon' : 'Comma';
    const columns = splitOutsideQuotes(lines[0], delimiter).length;
    composerFile = new Blob([raw.split('\n').join('\n')], { type: 'text/csv' });
    composerFile.name = 'pasted.csv';
    $('composerImportBtn').disabled = false;
    verdict.textContent = label + ' separated, ' + columns + ' columns, ' + lines.length + ' rows.';
    return;
  }

  // 3. One address per line.
  const parsed = lines.map(extractAddress);
  const good = parsed.filter(Boolean);
  if (good.length === 0) {
    verdict.innerHTML = '<span style="color:var(--danger)">No email addresses found in what you pasted. '
      + 'Expected one address per line, comma separated addresses, or a spreadsheet selection.</span>';
    return;
  }
  if (good.length < lines.length * 0.6) {
    verdict.innerHTML = '<span style="color:var(--warning)">Only ' + good.length + ' of ' + lines.length
      + ' lines look like an email address. Check what you pasted, or upload it as a CSV.</span> '
      + '<a href="#" onclick="forcePasteAnyway();return false" style="color:var(--primary)">Use them anyway</a>';
    window._pasteFallback = good;
    return;
  }
  const skipped = lines.length - good.length;
  usePastedCsv(good.map(a => [a.email, a.name]), ['email', 'name'],
    good.length + ' addresses, one per line.' + (skipped ? ' ' + skipped + ' line(s) had no address and will be skipped.' : ''));
}

function forcePasteAnyway() {
  const good = window._pasteFallback || [];
  if (!good.length) return;
  usePastedCsv(good.map(a => [a.email, a.name]), ['email', 'name'],
    good.length + ' addresses taken, the rest ignored.');
}

/** Builds RFC 4180 CSV out of rows and hands it to the same path a file takes. */
function usePastedCsv(rows, header, message) {
  const quote = cell => {
    const s = String(cell === null || cell === undefined ? '' : cell);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  const csv = [header.join(',')].concat(rows.map(r => r.map(quote).join(','))).join('\n');
  composerFile = new Blob([csv], { type: 'text/csv' });
  composerFile.name = 'pasted.csv';
  $('composerImportBtn').disabled = false;
  $('pasteVerdict').textContent = message;
}

/** "Anil Sharma <anil@x.org>" -> { name, email }. Null when there is no address. */
function extractAddress(line) {
  const text = String(line || '').replace(/^mailto:/i, '').trim();
  const angled = text.match(/^(.*)<\s*([^<>\s]+@[^<>\s]+)\s*>$/);
  const email = angled ? angled[2].trim() : text;
  if (!/^[^@\s,;]+@[^@\s,;.]+\.[^@\s,;]+$/.test(email)) return null;
  return { email: email.toLowerCase(), name: angled ? angled[1].trim().replace(/^["']|["']$/g, '') : '' };
}

/** Splits on a delimiter while respecting double quotes. */
function splitOutsideQuotes(line, delimiter) {
  const out = [];
  let cur = '', inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') inQuotes = !inQuotes;
    else if (ch === delimiter && !inQuotes) { out.push(cur); cur = ''; }
    else cur += ch;
  }
  out.push(cur);
  return out;
}

/* ---------- the review modal ---------- */

async function openImportReview() {
  if (!composerFile) { toast('Choose a file or paste some addresses first', 'warn'); return; }
  reviewMapping = {};
  $('arLoading').style.display = '';
  $('arContent').style.display = 'none';
  $('arConfirm').disabled = true;
  openModal('modalAudienceReview');
  await loadReview();
}

async function loadReview() {
  const form = new FormData();
  form.append('file', composerFile, composerFile.name || 'audience.csv');
  form.append('subject', $('cSubject').value);
  form.append('preheader', $('cPreheader').value);
  form.append('htmlBody', $('cHtml').value);
  Object.keys(reviewMapping).forEach(index => {
    if (reviewMapping[index]) form.append('map.' + index, reviewMapping[index]);
  });

  try {
    const res = await fetch('/api/campaignsplus/import/review',
      { method: 'POST', body: form, headers: { 'X-XSRF-TOKEN': csrfToken() } });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Could not read that file');
    reviewState = data;
    reviewMapping = {};
    Object.keys(data.mapping || {}).forEach(k => { reviewMapping[k] = data.mapping[k]; });
    renderReview(data);
  } catch (e) {
    $('arLoading').innerHTML = '<span style="color:var(--danger)">' + esc(e.message) + '</span>';
  }
}

function renderReview(d) {
  $('arLoading').style.display = 'none';
  $('arContent').style.display = '';

  const profile = d.profile;
  const fields = d.targetFields || [];

  $('arMapBody').innerHTML = profile.columns.map(col => {
    const name = col.header && col.header.trim()
      ? '<strong>' + esc(col.header) + '</strong>'
      : '<span style="color:var(--text-mute)">Column ' + (col.index + 1) + '</span>';
    const options = ['<option value="">-- ignore --</option>'].concat(fields.map(f =>
      '<option value="' + attr(f) + '"' + (reviewMapping[col.index] === f ? ' selected' : '') + '>'
      + esc(fieldLabel(f)) + '</option>')).join('');
    return '<tr><td>' + name + '</td>'
      + '<td class="truncate" style="max-width:220px;color:var(--text-dim)">'
      + esc((col.examples || []).join(', ')) + '</td>'
      + '<td><select class="input" data-col="' + col.index + '" onchange="onMappingChange(this)">'
      + options + '</select></td>'
      + '<td class="num">' + num(col.blankCount) + '</td></tr>';
  }).join('');

  // Rows rendered the way a recipient would see them. This is where "is the name
  // going properly" gets answered against the real data rather than a made up
  // test value, which is the whole point of showing it before the send.
  const subject = $('cSubject').value || '(no subject)';
  const bodyText = $('cHtml').value.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  $('arRender').innerHTML = (profile.sampleRows || []).slice(0, 5).map(row => {
    const values = valuesForRow(row);
    return '<div style="padding:8px 10px;border:1px solid var(--border);border-radius:var(--radius-sm);margin-bottom:6px">'
      + '<div style="font-weight:600">' + esc(applyMerge(subject, values)) + '</div>'
      + '<div style="color:var(--text-dim);margin-top:3px">'
      + esc(applyMerge(bodyText, values).slice(0, 200)) + '</div></div>';
  }).join('') || '<div class="empty">No data rows to preview.</div>';

  const findings = d.discrepancies || [];
  $('arFindings').innerHTML = findings.length
    ? findings.map(f => {
        const kind = f.severity === 'BLOCK' ? 'danger' : f.severity === 'WARN' ? 'warn' : 'info';
        return '<div class="alert ' + kind + '" style="margin-bottom:7px">' + esc(f.message) + '</div>';
      }).join('')
    : '<div class="alert info" style="margin-bottom:7px">Every merge tag in your email has a column behind it.</div>';

  const r = d.report;
  $('arSummary').innerHTML = num(r.rowsRead) + ' rows read &middot; <b>' + num(r.imported)
    + '</b> will be imported &middot; ' + num(r.skippedDuplicate) + ' duplicate &middot; '
    + num(r.skippedSuppressed) + ' suppressed &middot; ' + num(r.invalid) + ' invalid'
    + (profile.moreRows ? '<br><span style="color:var(--text-mute)">Preview read the first '
        + profile.sampledRows + ' rows; the counts above are from a full pass.</span>' : '');

  if (!$('arNewListName').value) {
    $('arNewListName').value = ($('cName').value.trim() || 'Composer import') + ' - '
      + new Date().toISOString().slice(0, 10);
  }
  $('arExistingList').innerHTML = listCache.map(l =>
    '<option value="' + l.id + '">' + esc(l.name) + '</option>').join('');

  $('arConfirm').disabled = !!d.blocked;
}

function fieldLabel(field) {
  return { email: 'email', firstName: 'first name', lastName: 'last name',
           name: 'name', phone: 'phone', company: 'company' }[field] || field;
}

/** Turns one sampled row into a merge map using the current column mapping. */
function valuesForRow(row) {
  const values = {};
  Object.keys(reviewMapping).forEach(index => {
    const field = reviewMapping[index];
    const cell = row[Number(index)] || '';
    if (field === 'email') values.EMAIL = cell;
    if (field === 'phone') values.PHONE = cell;
    if (field === 'company') values.COMPANY = cell;
    if (field === 'firstName') { values.FIRST_NAME = cell; values.NAME = values.NAME || cell; }
    if (field === 'lastName') values.LAST_NAME = cell;
    if (field === 'name') {
      values.NAME = cell;
      const space = cell.indexOf(' ');
      values.FIRST_NAME = space > 0 ? cell.slice(0, space) : cell;
      if (space > 0) values.LAST_NAME = cell.slice(space + 1);
    }
  });
  return values;
}

function applyMerge(text, values) {
  return String(text || '').replace(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g, (whole, key) => {
    const k = key.toUpperCase();
    return Object.prototype.hasOwnProperty.call(values, k) ? values[k] : '';
  });
}

function onMappingChange(select) {
  reviewMapping[select.dataset.col] = select.value;
  $('arLoading').style.display = '';
  $('arLoading').textContent = 'Rechecking...';
  $('arContent').style.display = 'none';
  loadReview();
}

function onDestChange() {
  const useExisting = document.querySelector('input[name="arDest"]:checked').value === 'existing';
  $('arExistingList').disabled = !useExisting;
  $('arNewListName').disabled = useExisting;
}

async function runComposerImport() {
  const useExisting = document.querySelector('input[name="arDest"]:checked').value === 'existing';
  const form = new FormData();
  form.append('file', composerFile, composerFile.name || 'audience.csv');
  form.append('source', 'composer import');
  if (useExisting) form.append('listId', $('arExistingList').value);
  else form.append('createListName', $('arNewListName').value.trim() || 'Composer import');
  Object.keys(reviewMapping).forEach(index => {
    if (reviewMapping[index]) form.append('map.' + index, reviewMapping[index]);
  });

  $('arConfirm').disabled = true;
  try {
    const res = await fetch('/api/campaignsplus/import',
      { method: 'POST', body: form, headers: { 'X-XSRF-TOKEN': csrfToken() } });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Import failed');

    await loadLists();
    fillListPickers();
    $('cList').value = data.listId || '';
    onListChange();
    switchAudienceTab('list');
    closeModal('modalAudienceReview');
    // Saving here means the campaign is genuinely pointed at the new list, rather
    // than looking like it is until someone reloads the page.
    await saveCampaign(false);
    toast(num(data.report.imported) + ' recipient(s) imported and attached', 'ok');
  } catch (e) {
    toast(e.message, 'err');
    $('arConfirm').disabled = false;
  }
}

function insertTag(tag) {
  const ta = $('cHtml');
  const start = ta.selectionStart || 0;
  ta.value = ta.value.slice(0, start) + tag + ta.value.slice(ta.selectionEnd || start);
  ta.focus();
  ta.selectionStart = ta.selectionEnd = start + tag.length;
  schedulePreview();
}

function onListChange() {
  const id = $('cList').value;
  const list = listCache.find(l => String(l.id) === String(id));
  if (!list) { $('audienceCount').textContent = 'no list'; $('audienceNote').textContent = ''; return; }
  $('audienceCount').textContent = num(list.mailable) + ' mailable';
  $('audienceNote').innerHTML = num(list.members) + ' on the list, <b>' + num(list.mailable)
    + '</b> will actually receive it. The rest are unsubscribed, bounced or suppressed.';
}

async function saveCampaign(notify) {
  const name = $('cName').value.trim();
  if (!name) { toast('Give the campaign a name first', 'warn'); return false; }
  try {
    const res = await post('/api/campaigns/save', {
      id: currentCampaignId, name,
      subject: $('cSubject').value, htmlBody: $('cHtml').value,
      preheader: $('cPreheader').value, fromName: $('cFromName').value,
      replyTo: $('cReplyTo').value, listId: $('cList').value || '',
      trackOpens: $('cTrackOpens').checked, trackClicks: $('cTrackClicks').checked
    });
    currentCampaignId = res.id;
    $('composerTitle').textContent = name;
    if (notify) toast('Campaign saved', 'ok');
    loadCampaigns();
    return true;
  } catch (e) { toast(e.message, 'err'); return false; }
}

async function sendTest() {
  const to = $('cTestTo').value.trim();
  if (!to) { toast('Enter an address to test with', 'warn'); return; }
  // Testing an already sent campaign is legitimate: you may want to see exactly
  // what went out. Only save when there is something the server will accept.
  if (composerEditable && !(await saveCampaign(false))) return;

  // Merge values ride along as merge.NAME=..., because post() sends a form body
  // with the CSRF header and a variable-length map has to survive that encoding.
  const params = { id: currentCampaignId, to };
  const values = testMergeValues();
  Object.keys(values).forEach(tag => { params['merge.' + tag] = values[tag]; });

  const blank = composerMergeTags().filter(tag => !values[tag]);
  try {
    const res = await post('/api/campaigns/test-send', params);
    rememberTestValues();
    toast(blank.length
      ? res.message + ' ' + blank.length + ' tag(s) used sample data: ' + blank.join(', ')
      : res.message, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function sendCampaign() {
  if (composerEditable && !(await saveCampaign(false))) return;
  const list = listCache.find(l => String(l.id) === String($('cList').value));
  if (!list) { toast('Choose an audience list first', 'warn'); return; }
  if (!confirm('Send "' + $('cName').value.trim() + '" to ' + list.mailable
      + ' recipients on "' + list.name + '"?\n\nThis cannot be undone.')) return;

  $('sendBtn').disabled = true;
  try {
    toast((await post('/api/campaigns/send', { id: currentCampaignId })).message, 'ok');
    pollProgress();
  } catch (e) { toast(e.message, 'err'); $('sendBtn').disabled = false; }
}

function openScheduleModal() {
  if (!currentCampaignId) { toast('Save the campaign first', 'warn'); return; }
  const inOneHour = new Date(Date.now() + 3600000 - new Date().getTimezoneOffset() * 60000);
  $('schedWhen').value = inOneHour.toISOString().slice(0, 16);
  openModal('modalSchedule');
}

async function scheduleCampaign() {
  if (!(await saveCampaign(false))) return;
  try {
    const res = await post('/api/campaigns/schedule', { id: currentCampaignId, when: $('schedWhen').value });
    toast(res.message, 'ok');
    closeModal('modalSchedule');
    openCampaign(currentCampaignId);
  } catch (e) { toast(e.message, 'err'); }
}

function pollProgress() {
  clearInterval(progressTimer);
  if (!currentCampaignId) return;

  const tick = async () => {
    let p;
    try { p = await api('/api/campaigns/' + currentCampaignId + '/progress'); }
    catch (e) { clearInterval(progressTimer); return; }

    if (p.active === false && !p.status) { $('progressCard').style.display = 'none'; return; }

    $('progressCard').style.display = '';
    const done = (p.sent || 0) + (p.failed || 0) + (p.skipped || 0);
    const pct = p.total ? Math.min(100, (done / p.total) * 100) : (p.active ? 0 : 100);
    $('progressFill').style.width = pct.toFixed(1) + '%';
    $('progressPct').textContent = pct.toFixed(0) + '%';
    $('progressLabel').textContent = p.status === 'SENDING' ? 'Sending' : p.status;
    $('pgSent').textContent = num(p.sent);
    $('pgFailed').textContent = num(p.failed);
    $('pgSkipped').textContent = num(p.skipped);
    $('pgTotal').textContent = num(p.total);
    $('pgError').textContent = p.lastError || '';

    if (!p.active) {
      clearInterval(progressTimer);
      $('sendBtn').disabled = false;
      if (p.status === 'SENT') toast('Campaign finished: ' + p.sent + ' sent, ' + p.failed + ' failed', 'ok');
      loadCampaigns();
      loadRecipients();
    }
  };
  tick();
  progressTimer = setInterval(tick, 1500);
}

function exportCampaign(status) {
  if (!currentCampaignId) { toast('Open a campaign first', 'warn'); return; }
  location.href = '/api/campaigns/' + currentCampaignId + '/export?status=' + encodeURIComponent(status);
}

async function requeueFailed() {
  if (!currentCampaignId) return;
  try { toast((await post('/api/campaigns/requeue-failed', { id: currentCampaignId })).message, 'ok'); loadRecipients(); }
  catch (e) { toast(e.message, 'err'); }
}

async function deleteCampaign() {
  if (!currentCampaignId) return;
  if (!confirm('Delete "' + $('cName').value.trim() + '"?\n\nSubscribers and the suppression list are untouched.')) return;
  try {
    toast((await post('/api/campaigns/delete', { id: currentCampaignId })).message, 'ok');
    newCampaign();
    loadCampaigns();
  } catch (e) { toast(e.message, 'err'); }
}

let recipientPage = 0;
async function loadRecipients() {
  if (!currentCampaignId) return;
  const params = new URLSearchParams({
    status: $('rStatus').value, q: $('rSearch').value.trim(), page: recipientPage, size: 50
  });
  let d;
  try { d = await api('/api/campaigns/' + currentCampaignId + '/recipients?' + params); } catch (e) { return; }

  $('recipientBody').innerHTML = d.rows.length ? d.rows.map(r =>
    '<tr><td class="mono">' + esc(r.email) + '</td><td>' + esc(r.name) + '</td>'
    + '<td>' + statusPill(r.status)
      + (r.failReason ? '<br><span style="font-size:11px;color:var(--danger)" class="truncate" title="'
        + attr(r.failReason) + '">' + esc(r.failReason) + '</span>' : '') + '</td>'
    + '<td class="num">' + num(r.openCount) + '</td><td class="num">' + num(r.clickCount) + '</td>'
    + '<td class="mono">' + esc(r.sentAt || '-') + '</td></tr>').join('')
    : emptyRow(6, 'Nobody matches those filters.');

  recipientPage = d.page;
  $('rCount').textContent = pagerText(d);
}
function pageRecipients(delta) {
  if (recipientPage + delta < 0) return;
  recipientPage += delta;
  loadRecipients();
}

/* =========================================================================
   Lists
   ========================================================================= */
async function loadLists() {
  try { listCache = await api('/api/lists'); }
  catch (e) { return; }

  $('badgeLists').textContent = listCache.length;
  const body = $('listBody');
  if (body) {
    body.innerHTML = listCache.length ? listCache.map(l =>
      '<tr><td><strong>' + esc(l.name) + '</strong>'
      + (l.description ? '<br><span style="font-size:12px;color:var(--text-mute)">' + esc(l.description) + '</span>' : '')
      + '</td><td>' + statusPill(l.kind) + '</td>'
      + '<td class="num">' + num(l.members) + '</td>'
      + '<td class="num"><b>' + num(l.mailable) + '</b></td>'
      + '<td class="mono">' + esc(l.createdAt) + '</td>'
      + '<td class="actions">'
        + (can('SUBSCRIBERS_WRITE') ? '<button class="btn btn-sm" onclick="openImport(' + l.id + ",'" + attr(l.name) + '\')">Import CSV</button>' : '')
        + (can('SUBSCRIBERS_READ') ? '<button class="btn btn-sm" onclick="viewListMembers(' + l.id + ')">View</button>' : '')
        + (can('LISTS_WRITE') ? '<button class="btn btn-sm btn-danger" onclick="deleteList(' + l.id + ",'" + attr(l.name) + '\')">Delete</button>' : '')
      + '</td></tr>').join('')
      : emptyRow(6, 'No lists yet. Create one, then import a CSV into it.');
  }
  return listCache;
}

function fillListPickers() {
  const options = '<option value="">-- choose a list --</option>'
    + listCache.map(l => '<option value="' + l.id + '">' + esc(l.name) + ' (' + num(l.mailable) + ')</option>').join('');
  const composer = $('cList');
  if (composer) { const v = composer.value; composer.innerHTML = options; composer.value = v; }

  const msList = $('msList');
  if (msList) msList.innerHTML = '<option value="">-- none --</option>'
    + listCache.map(l => '<option value="' + l.id + '">' + esc(l.name) + '</option>').join('');

  const sList = $('sList');
  if (sList) { const v = sList.value; sList.innerHTML = '<option value="">All lists</option>'
    + listCache.map(l => '<option value="' + l.id + '">' + esc(l.name) + '</option>').join(''); sList.value = v; }
}

async function createList() {
  const name = $('mlName').value.trim();
  if (!name) { toast('Name the list', 'warn'); return; }
  try {
    toast((await post('/api/lists', {
      name, description: $('mlDesc').value, kind: $('mlKind').value })).message, 'ok');
    closeModal('modalList');
    $('mlName').value = ''; $('mlDesc').value = '';
    loadLists().then(fillListPickers);
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteList(id, name) {
  if (!confirm('Delete the list "' + name + '"?\n\nThe people on it are kept, only the grouping goes.')) return;
  try { toast((await post('/api/lists/delete', { id })).message, 'ok'); loadLists().then(fillListPickers); }
  catch (e) { toast(e.message, 'err'); }
}

function viewListMembers(id) {
  go('subscribers');
  setTimeout(() => { $('sList').value = id; subscriberPage = 0; loadSubscribers(); }, 120);
}

/* ---------- CSV import ---------- */
let importListId = null;

function openImport(listId, listName) {
  importListId = listId;
  $('importTitle').textContent = 'Import into "' + listName + '"';
  $('importResult').textContent = '';
  openModal('modalImport');
}

$('dropzone').addEventListener('click', () => $('csvFile').click());
$('dropzone').addEventListener('dragover', e => { e.preventDefault(); $('dropzone').classList.add('over'); });
$('dropzone').addEventListener('dragleave', () => $('dropzone').classList.remove('over'));
$('dropzone').addEventListener('drop', e => {
  e.preventDefault(); $('dropzone').classList.remove('over');
  if (e.dataTransfer.files.length) uploadCsv(e.dataTransfer.files[0]);
});
$('csvFile').addEventListener('change', e => { if (e.target.files.length) uploadCsv(e.target.files[0]); });

async function uploadCsv(file) {
  if (!importListId) { toast('Pick a list first', 'warn'); return; }
  const fd = new FormData();
  fd.append('file', file);
  fd.append('listId', importListId);
  $('importResult').textContent = 'Importing ' + file.name + '...';
  try {
    const res = await fetch('/api/lists/import', {
      method: 'POST', body: fd, headers: { 'X-XSRF-TOKEN': csrfToken() } });
    if (res.status === 401) { location.href = '/login'; return; }
    const d = await res.json();
    if (d.error) throw new Error(d.error);
    $('importResult').innerHTML =
      '<b style="color:var(--success)">' + num(d.created) + ' new people</b>, '
      + num(d.updated) + ' enriched, <b>' + num(d.addedToList) + ' added to the list</b><br>'
      + '<span style="color:var(--text-mute)">' + num(d.alreadyOnList) + ' already on it, '
      + num(d.duplicateInFile) + ' duplicated in the file, ' + num(d.suppressed)
      + ' suppressed, ' + num(d.invalid) + ' invalid</span>';
    toast(num(d.addedToList) + ' added to the list', 'ok');
    loadLists().then(fillListPickers);
  } catch (e) {
    $('importResult').textContent = '';
    toast('Import failed: ' + e.message, 'err');
  } finally { $('csvFile').value = ''; }
}

/* =========================================================================
   Subscribers
   ========================================================================= */
let subscriberPage = 0;

async function loadSubscribers() {
  const params = new URLSearchParams({
    q: $('sSearch').value.trim(), status: $('sStatus').value,
    page: subscriberPage, size: 50
  });
  if ($('sList').value) params.append('listId', $('sList').value);

  let d;
  try { d = await api('/api/subscribers?' + params); }
  catch (e) { toast('Could not load subscribers', 'err'); return; }

  $('subscriberBody').innerHTML = d.rows.length ? d.rows.map(s =>
    '<tr><td><strong>' + esc(s.name) + '</strong></td><td class="mono">' + esc(s.email) + '</td>'
    + '<td>' + esc(s.company || '-') + '</td><td>' + statusPill(s.status) + '</td>'
    + '<td class="num">' + num(s.sent) + '</td><td class="num">' + num(s.opened) + '</td>'
    + '<td class="num">' + num(s.clicked) + '</td>'
    + '<td class="actions">' + (can('SUBSCRIBERS_WRITE')
        ? '<button class="btn btn-sm btn-danger" onclick="deleteSubscriber(' + s.id + ",'" + attr(s.email) + '\')">Delete</button>' : '')
    + '</td></tr>').join('')
    : emptyRow(8, 'Nobody matches those filters.');

  subscriberPage = d.page;
  $('sCount').textContent = pagerText(d);
}
function pageSubscribers(delta) {
  if (subscriberPage + delta < 0) return;
  subscriberPage += delta;
  loadSubscribers();
}

async function createSubscriber() {
  const email = $('msEmail').value.trim();
  if (!email) { toast('Email is required', 'warn'); return; }
  try {
    toast((await post('/api/subscribers', {
      email, firstName: $('msFirst').value, lastName: $('msLast').value,
      listId: $('msList').value || '' })).message, 'ok');
    closeModal('modalSubscriber');
    ['msEmail', 'msFirst', 'msLast'].forEach(id => $(id).value = '');
    loadSubscribers(); loadLists();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteSubscriber(id, email) {
  if (!confirm('Delete ' + email + ' entirely?\n\nUse Suppress instead if you only want to stop mailing them.')) return;
  try { toast((await post('/api/subscribers/delete', { id })).message, 'ok'); loadSubscribers(); }
  catch (e) { toast(e.message, 'err'); }
}

function exportSubscribers() {
  const params = new URLSearchParams({ q: $('sSearch').value.trim(), status: $('sStatus').value });
  if ($('sList').value) params.append('listId', $('sList').value);
  location.href = '/api/subscribers/export?' + params;
}

/* =========================================================================
   Suppression
   ========================================================================= */
let suppressionPage = 0;

async function loadSuppressions() {
  const params = new URLSearchParams({
    q: $('supSearch').value.trim(), reason: $('supReason').value, page: suppressionPage, size: 50 });
  let d;
  try { d = await api('/api/suppressions?' + params); }
  catch (e) { toast('Could not load the list', 'err'); return; }

  $('supBody').innerHTML = d.rows.length ? d.rows.map(s =>
    '<tr><td class="mono">' + esc(s.email) + '</td><td>' + statusPill(s.reason) + '</td>'
    + '<td class="mono">' + esc(s.at) + '</td>'
    + '<td>' + (can('SUPPRESSION_WRITE')
        ? '<button class="btn btn-sm btn-danger" onclick="removeSuppression(\'' + attr(s.email) + '\')">Allow again</button>' : '')
    + '</td></tr>').join('')
    : emptyRow(4, 'Nobody is suppressed. That is a good sign.');

  suppressionPage = d.page;
  $('supCount').textContent = pagerText(d);
}
function pageSuppressions(delta) {
  if (suppressionPage + delta < 0) return;
  suppressionPage += delta;
  loadSuppressions();
}

async function addSuppression() {
  const email = $('supAdd').value.trim();
  if (!email) return;
  try { toast((await post('/api/suppressions/add', { email })).message, 'ok'); $('supAdd').value = ''; loadSuppressions(); }
  catch (e) { toast(e.message, 'err'); }
}

async function removeSuppression(email) {
  if (!confirm('Allow ' + email + ' to receive campaigns again?')) return;
  try { toast((await post('/api/suppressions/remove', { email })).message, 'ok'); loadSuppressions(); }
  catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   Templates
   ========================================================================= */
let templateCache = [];

async function loadTemplates() {
  try { templateCache = await api('/api/templates?type=' + encodeURIComponent($('tType').value)); }
  catch (e) { toast('Could not load templates', 'err'); return; }

  $('templateBody').innerHTML = templateCache.length ? templateCache.map(t =>
    '<tr><td><strong>' + esc(t.name) + '</strong>'
    + (t.description ? '<br><span style="font-size:12px;color:var(--text-mute)">' + esc(t.description) + '</span>' : '')
    + '</td><td class="mono">' + esc(t.slug) + '</td><td>' + statusPill(t.type) + '</td>'
    + '<td>' + (t.mergeTags.length
        ? t.mergeTags.map(x => '<span class="chip static">' + esc(x) + '</span>').join(' ')
        : '<span style="color:var(--text-mute)">none</span>') + '</td>'
    + '<td class="mono">' + esc(t.createdAt) + '</td>'
    + '<td class="actions">' + (can('TEMPLATES_WRITE')
        ? '<button class="btn btn-sm" onclick="openTemplateEditor(' + t.id + ')">Edit</button>'
          + '<button class="btn btn-sm btn-danger" onclick="deleteTemplate(' + t.id + ')">Delete</button>' : '')
    + '</td></tr>').join('')
    : emptyRow(6, 'No templates yet.');
  return templateCache;
}

let editingTemplateId = null;
function openTemplateEditor(id) {
  editingTemplateId = id;
  const t = templateCache.find(x => x.id === id);
  $('teTitle').textContent = t ? 'Edit "' + t.name + '"' : 'New template';
  $('teName').value = t ? t.name : '';
  $('teSlug').value = t ? t.slug : '';
  $('teSubject').value = t ? t.subject : '';
  $('teHtml').value = t ? t.htmlBody : '';
  $('teType').value = t ? t.type : 'MARKETING';
  openModal('modalTemplate');
}

async function saveTemplate() {
  const name = $('teName').value.trim();
  if (!name) { toast('Name the template', 'warn'); return; }
  try {
    toast((await post('/api/templates/save', {
      id: editingTemplateId, name, slug: $('teSlug').value,
      subject: $('teSubject').value, htmlBody: $('teHtml').value, type: $('teType').value })).message, 'ok');
    closeModal('modalTemplate');
    loadTemplates();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteTemplate(id) {
  if (!confirm('Delete this template? Anything calling its slug will start failing.')) return;
  try { toast((await post('/api/templates/delete', { id })).message, 'ok'); loadTemplates(); }
  catch (e) { toast(e.message, 'err'); }
}

async function loadTemplateIntoComposer() {
  await loadTemplates();
  const marketing = templateCache.filter(t => t.type === 'MARKETING');
  $('pickTemplateBody').innerHTML = marketing.length ? marketing.map(t =>
    '<div class="health-row" style="cursor:pointer" onclick="applyTemplate(' + t.id + ')">'
    + '<span><b>' + esc(t.name) + '</b><br><span style="font-size:12px;color:var(--text-mute)">'
    + esc(t.subject || 'no subject') + '</span></span>'
    + '<span class="v"><button class="btn btn-sm">Use</button></span></div>').join('')
    : '<div class="empty">No marketing templates saved yet.</div>';
  openModal('modalPickTemplate');
}

function applyTemplate(id) {
  const t = templateCache.find(x => x.id === id);
  if (!t) return;
  $('cSubject').value = t.subject || '';
  $('cHtml').value = t.htmlBody || '';
  updatePreview();
  refreshTestFields();
  closeModal('modalPickTemplate');
  toast('Template loaded into the composer', 'ok');
}

async function saveComposerAsTemplate() {
  const name = prompt('Save this creative to the library as:');
  if (!name) return;
  try {
    toast((await post('/api/templates/save', {
      name, subject: $('cSubject').value, htmlBody: $('cHtml').value, type: 'MARKETING' })).message, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   Transactional
   ========================================================================= */
let transactionalPage = 0;
let txTemplates = [];

async function loadTransactional() {
  const params = new URLSearchParams({
    q: $('txSearch').value.trim(), status: $('txStatus').value, page: transactionalPage, size: 50 });
  let d;
  try { d = await api('/api/transactional/log?' + params); } catch (e) { return; }

  $('txBody').innerHTML = d.rows.length ? d.rows.map(t =>
    '<tr><td class="mono">' + esc(t.to) + '</td><td class="mono">' + esc(t.template) + '</td>'
    + '<td>' + statusPill(t.status)
      + (t.error ? '<br><span style="font-size:11px;color:var(--danger)" class="truncate" title="'
        + attr(t.error) + '">' + esc(t.error) + '</span>' : '') + '</td>'
    + '<td class="truncate" style="max-width:150px">' + esc(t.sentVia) + '</td>'
    + '<td class="mono">' + esc(t.at) + '</td></tr>').join('')
    : emptyRow(5, 'Nothing sent through the transactional API yet.');

  transactionalPage = d.page;
  $('txCount').textContent = pagerText(d);
}
function pageTransactional(delta) {
  if (transactionalPage + delta < 0) return;
  transactionalPage += delta;
  loadTransactional();
}

async function loadTxTemplates() {
  try { txTemplates = await api('/api/templates?type=TRANSACTIONAL'); } catch (e) { return; }
  const sel = $('txTemplate');
  if (!sel) return;
  sel.innerHTML = '<option value="">-- choose --</option>'
    + txTemplates.map(t => '<option value="' + esc(t.slug) + '">' + esc(t.name) + '</option>').join('');
}

function renderTxFields() {
  const slug = $('txTemplate').value;
  const t = txTemplates.find(x => x.slug === slug);
  if (!t || !t.mergeTags.length) { $('txFields').innerHTML = ''; return; }
  $('txFields').innerHTML = t.mergeTags.map(tag =>
    '<label class="field"><span>' + esc(tag) + '</span>'
    + '<input class="input tx-field" data-tag="' + attr(tag) + '" placeholder="value for ' + esc(tag) + '"></label>').join('');
}

async function sendTransactional() {
  const slug = $('txTemplate').value;
  const to = $('txTo').value.trim();
  if (!slug) { toast('Choose a template', 'warn'); return; }
  if (!to) { toast('Enter a recipient', 'warn'); return; }

  const params = { slug, to };
  document.querySelectorAll('.tx-field').forEach(input => { params[input.dataset.tag] = input.value; });
  try {
    toast((await post('/api/transactional/send', params)).message, 'ok');
    $('txTo').value = '';
    transactionalPage = 0;
    loadTransactional();
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   Team
   ========================================================================= */
async function loadTeam() {
  let team;
  try { team = await api('/api/admin/users'); } catch (e) { return; }

  $('teamBody').innerHTML = team.map(u =>
    '<tr><td><strong>' + esc(u.fullName) + '</strong><br><span class="mono" style="font-size:12px;color:var(--text-mute)">'
      + esc(u.email) + '</span></td>'
    + '<td>' + (u.role === 'OWNER' || !can('TEAM_WRITE')
        ? statusPill(u.roleLabel.toUpperCase())
        : '<select class="input" style="max-width:150px" onchange="changeRole(' + u.id + ', this.value)">'
          + ROLES.filter(r => r.name !== 'OWNER').map(r => '<option value="' + r.name + '"'
            + (r.name === u.role ? ' selected' : '') + '>' + esc(r.label) + '</option>').join('')
          + '</select>') + '</td>'
    + '<td>' + (u.active ? (u.locked ? '<span class="pill pill-failed">LOCKED</span>'
                                     : '<span class="pill pill-sent">ACTIVE</span>')
                         : '<span class="pill pill-skipped">DISABLED</span>') + '</td>'
    + '<td class="mono">' + esc(u.lastLoginAt) + '</td>'
    + '<td class="actions">' + (can('TEAM_WRITE') && u.role !== 'OWNER'
        ? '<button class="btn btn-sm" onclick="resetPassword(' + u.id + ",'" + attr(u.email) + '\')">Reset password</button>'
          + '<button class="btn btn-sm ' + (u.active ? 'btn-danger' : '') + '" onclick="setUserActive('
            + u.id + ', ' + (!u.active) + ')">' + (u.active ? 'Disable' : 'Enable') + '</button>'
        : '') + '</td></tr>').join('');
}

let ROLES = [];
async function loadRoles() {
  try { ROLES = await api('/api/admin/roles'); } catch (e) { return; }

  $('roleBody').innerHTML = ROLES.map(r =>
    '<tr><td><strong>' + esc(r.label) + '</strong></td><td>' + esc(r.description) + '</td>'
    + '<td>' + r.permissions.map(p =>
        '<span class="chip static">' + esc(p.replace(/_/g, ' ').toLowerCase()) + '</span>').join(' ')
    + '</td></tr>').join('');

  const sel = $('miRole');
  if (sel) sel.innerHTML = ROLES.filter(r => r.name !== 'OWNER')
    .map(r => '<option value="' + r.name + '">' + esc(r.label) + ' - ' + esc(r.description) + '</option>').join('');
}

async function inviteUser() {
  const email = $('miEmail').value.trim();
  if (!email) { toast('Email is required', 'warn'); return; }
  try {
    toast((await post('/api/admin/users/invite', {
      email, fullName: $('miName').value, role: $('miRole').value,
      password: $('miPassword').value })).message, 'ok');
    closeModal('modalInvite');
    ['miEmail', 'miName', 'miPassword'].forEach(id => $(id).value = '');
    loadTeam();
  } catch (e) { toast(e.message, 'err'); }
}

async function changeRole(id, role) {
  try { toast((await post('/api/admin/users/role', { id, role })).message, 'ok'); loadTeam(); }
  catch (e) { toast(e.message, 'err'); loadTeam(); }
}

async function setUserActive(id, active) {
  try { toast((await post('/api/admin/users/active', { id, active })).message, 'ok'); loadTeam(); }
  catch (e) { toast(e.message, 'err'); }
}

async function resetPassword(id, email) {
  const password = prompt('New password for ' + email + ' (at least 10 characters):');
  if (!password) return;
  try { toast((await post('/api/admin/users/password', { id, password })).message, 'ok'); loadTeam(); }
  catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   API keys
   ========================================================================= */
async function loadKeys() {
  let keys;
  try { keys = await api('/api/admin/api-keys'); } catch (e) { return; }

  $('keyBody').innerHTML = keys.length ? keys.map(k =>
    '<tr><td><strong>' + esc(k.name) + '</strong></td><td class="mono">' + esc(k.prefix) + '</td>'
    + '<td class="mono">' + esc(k.createdAt) + '</td><td class="mono">' + esc(k.lastUsedAt) + '</td>'
    + '<td class="num">' + num(k.useCount) + '</td>'
    + '<td>' + (k.revoked ? '<span class="pill pill-failed">REVOKED</span>'
        : '<button class="btn btn-sm btn-danger" onclick="revokeKey(' + k.id + ')">Revoke</button>') + '</td></tr>').join('')
    : emptyRow(6, 'No API keys yet. Create one for the HR system.');
}

async function createKey() {
  const name = $('mkName').value.trim();
  if (!name) { toast('Name the key', 'warn'); return; }
  try {
    const res = await post('/api/admin/api-keys', { name });
    $('mkResult').innerHTML = '<div class="alert ok" style="margin-top:14px">'
      + '<b>Copy this now. It will never be shown again.</b>'
      + '<pre class="code" style="margin-top:9px;white-space:pre-wrap;word-break:break-all">'
      + esc(res.key) + '</pre></div>';
    $('mkCreate').style.display = 'none';
    $('mkName').value = '';
    loadKeys();
  } catch (e) { toast(e.message, 'err'); }
}

async function revokeKey(id) {
  if (!confirm('Revoke this key? Anything using it stops working immediately.')) return;
  try { toast((await post('/api/admin/api-keys/revoke', { id })).message, 'ok'); loadKeys(); }
  catch (e) { toast(e.message, 'err'); }
}

function renderApiExample() {
  $('apiExample').innerHTML =
    '<span class="c"># Fire an interview invitation from any system</span>\n'
    + 'curl -X POST ' + location.origin + '/api/v1/transactional/send \\\n'
    + '  -H <span class="s">"Authorization: Bearer jcf_live_YOUR_KEY"</span> \\\n'
    + '  -H <span class="s">"Content-Type: application/json"</span> \\\n'
    + '  -d \'{\n'
    + '        <span class="k">"template"</span>: <span class="s">"interview-round-1"</span>,\n'
    + '        <span class="k">"to"</span>: <span class="s">"candidate@example.com"</span>,\n'
    + '        <span class="k">"data"</span>: {\n'
    + '          <span class="k">"CANDIDATE_NAME"</span>: <span class="s">"Priya"</span>,\n'
    + '          <span class="k">"ROLE"</span>: <span class="s">"Program Manager"</span>,\n'
    + '          <span class="k">"INTERVIEW_DATE"</span>: <span class="s">"21 Aug 2026"</span>,\n'
    + '          <span class="k">"INTERVIEW_TIME"</span>: <span class="s">"11:00 IST"</span>,\n'
    + '          <span class="k">"INTERVIEW_MODE"</span>: <span class="s">"Google Meet"</span>,\n'
    + '          <span class="k">"INTERVIEWER"</span>: <span class="s">"Kishan"</span>,\n'
    + '          <span class="k">"SENDER_NAME"</span>: <span class="s">"People Team"</span>\n'
    + '        }\n'
    + '      }\'\n\n'
    + '<span class="c"># Discover which templates and merge tags exist</span>\n'
    + 'curl ' + location.origin + '/api/v1/templates \\\n'
    + '  -H <span class="s">"Authorization: Bearer jcf_live_YOUR_KEY"</span>';
}

/* =========================================================================
   Audit
   ========================================================================= */
let auditPage = 0;

async function loadAudit() {
  const params = new URLSearchParams({ q: $('aSearch').value.trim(), page: auditPage, size: 50 });
  let d;
  try { d = await api('/api/admin/audit?' + params); } catch (e) { return; }

  $('auditBody').innerHTML = d.rows.length ? d.rows.map(a =>
    '<tr><td class="mono nowrap">' + esc(a.at) + '</td><td><strong>' + esc(a.actor) + '</strong></td>'
    + '<td>' + esc(a.action) + '</td><td class="truncate">' + esc(a.target) + '</td>'
    + '<td class="truncate">' + esc(a.detail) + '</td><td class="mono">' + esc(a.ip) + '</td></tr>').join('')
    : emptyRow(6, 'Nothing recorded yet.');

  auditPage = d.page;
  $('aCount').textContent = pagerText(d);
}
function pageAudit(delta) {
  if (auditPage + delta < 0) return;
  auditPage += delta;
  loadAudit();
}

/* =========================================================================
   Verify list
   ========================================================================= */
let verifyPage = 0;
let verifyRunKey = null;
let verifyPollTimer = null;

function loadVerify() {
  // The list picker is filled from the audience cache, which the Lists screen
  // owns. Nothing here refetches it.
  const picker = $('verifyList');
  const chosen = picker.value;
  picker.innerHTML = '<option value="">Whole audience</option>'
    + listCache.map(l => '<option value="' + l.id + '">' + esc(l.name) + ' (' + num(l.mailable) + ')</option>').join('');
  picker.value = chosen;
  loadVerifySummary();
  loadVerifyResults();
}

async function loadVerifySummary() {
  const listId = $('verifyList').value;
  let d;
  try { d = await api('/api/verification/summary' + (listId ? '?listId=' + listId : '')); }
  catch (e) { toast('Could not load verification totals', 'err'); return; }

  const tiles = [
    { label: 'In this audience', value: num(d.audience), foot: num(d.unchecked) + ' never checked', cls: '' },
    { label: 'Checked', value: num(d.checked), foot: 'kept ' + num(d.settings ? d.settings.retentionDays : 0) + ' days', cls: '' },
    { label: 'Deliverable', value: num(d.deliverable), foot: d.deliverablePercent + '% safe to send', cls: 'good' },
    { label: 'Risky', value: num(d.risky), foot: 'role accounts, catch-all', cls: 'warn' },
    { label: 'Undeliverable', value: num(d.undeliverable), foot: 'would hard bounce', cls: 'danger' }
  ];
  $('verifyKpis').innerHTML = tiles.map(kpiTile).join('');
  $('verifyExportBtn').disabled = !$('verifyList').value;
}

async function loadVerifyResults() {
  const params = new URLSearchParams({
    q: $('verifySearch').value.trim(),
    verdict: $('verifyVerdict').value,
    page: verifyPage, size: 50
  });
  let d;
  try { d = await api('/api/verification/results?' + params); }
  catch (e) { toast('Could not load results', 'err'); return; }

  $('verifyBody').innerHTML = d.rows.length ? d.rows.map(r =>
    '<tr><td class="mono">' + esc(r.email) + '</td>'
    + '<td>' + verdictPill(r.verdict, r.label) + '</td>'
    + '<td class="truncate">' + esc(r.reason) + '</td>'
    + '<td>' + mark(r.syntax) + '</td><td>' + mark(r.mx) + '</td><td>' + mark(r.mailbox) + '</td>'
    + '<td class="mono nowrap">' + esc(r.checkedAt) + '</td></tr>').join('')
    : emptyRow(7, 'Nothing verified yet. Pick a list and run a check.');

  verifyPage = d.page;
  $('verifyCount').textContent = num(d.total) + ' total, page ' + (d.page + 1)
    + ' of ' + Math.max(1, d.totalPages);
}

function pageVerify(delta) {
  if (verifyPage + delta < 0) return;
  verifyPage += delta;
  loadVerifyResults();
}

function verdictPill(verdict, label) {
  const map = { DELIVERABLE: 'pill-sent', RISKY: 'pill-scheduled', UNDELIVERABLE: 'pill-failed', UNKNOWN: 'pill-draft' };
  return '<span class="pill ' + (map[verdict] || 'pill-draft') + '">' + esc(label || verdict) + '</span>';
}

/** The server sends "ok", "no" or "" so every check column renders the same three ways. */
function mark(value) {
  if (value === 'ok') return '<span style="color:var(--success)">&#10003;</span>';
  if (value === 'no') return '<span style="color:var(--danger)">&#10007;</span>';
  return '<span style="color:var(--text-faint)">&ndash;</span>';
}

function kpiTile(t) {
  return '<div class="kpi ' + (t.cls || '') + '"><div class="label">' + t.label + '</div>'
    + '<div class="value">' + t.value + '</div><div class="foot">' + t.foot + '</div></div>';
}

async function startListVerification() {
  const listId = $('verifyList').value;
  if (!listId) { toast('Choose a list first', 'err'); return; }
  try {
    const r = await post('/api/verification/list', { listId: listId, force: $('verifyForce').checked });
    verifyRunKey = r.key;
    toast(r.message);
    $('verifyProgress').style.display = 'block';
    pollVerification();
  } catch (e) { toast(e.message, 'err'); }
}

async function pollVerification() {
  clearTimeout(verifyPollTimer);
  if (!verifyRunKey) return;
  let p;
  try { p = await api('/api/verification/progress?key=' + encodeURIComponent(verifyRunKey)); }
  catch (e) { return; }

  $('verifyBar').style.width = (p.percent || 0) + '%';
  $('verifyProgressText').textContent = p.running
    ? 'Checking "' + (p.label || '') + '": ' + num(p.done) + ' of ' + num(p.total)
      + ', ' + num(p.deliverable) + ' deliverable, ' + num(p.risky) + ' risky'
    : 'Finished: ' + num(p.done) + ' of ' + num(p.total) + ' addresses.';

  if (p.running) {
    verifyPollTimer = setTimeout(pollVerification, 1500);
  } else {
    verifyRunKey = null;
    loadVerifySummary();
    loadVerifyResults();
  }
}

async function verifyAdhoc() {
  const emails = $('verifyAdhoc').value.trim();
  if (!emails) { toast('Paste at least one address', 'err'); return; }
  try {
    const r = await post('/api/verification/addresses', { emails: emails, force: false });
    toast(r.checked + ' checked: ' + r.deliverable + ' deliverable, '
      + r.risky + ' risky, ' + r.undeliverable + ' undeliverable');
    $('verifyAdhoc').value = '';
    verifyPage = 0;
    loadVerifyResults();
    loadVerifySummary();
  } catch (e) { toast(e.message, 'err'); }
}

function exportCleanList() {
  const listId = $('verifyList').value;
  if (!listId) { toast('Choose a list first', 'err'); return; }
  location.href = '/api/verification/export?listId=' + listId;
}

/* =========================================================================
   Analytics
   ========================================================================= */
async function loadAnalytics() {
  const picker = $('anCampaign');
  // Refilled every time rather than once. Filling it once meant a campaign created
  // after the page loaded never appeared in the list, so the most recent send was
  // always the one you could not look at.
  const chosen = picker.value;
  picker.innerHTML = '<option value="">All campaigns</option>'
    + campaignCache.map(c => '<option value="' + c.id + '"'
      + (String(c.id) === String(chosen) ? ' selected' : '') + '>' + esc(c.name) + '</option>').join('');

  const params = new URLSearchParams({ days: $('anDays').value });
  if (picker.value) params.set('campaignId', picker.value);

  let d;
  try { d = await api('/api/analytics/overview?' + params); }
  catch (e) { toast('Could not load analytics', 'err'); return; }

  const s = d.summary;
  $('anKpis').innerHTML = [
    { label: 'Delivered', value: num(s.delivered), foot: s.deliveredRate + '% of sent', cls: '' },
    { label: 'Reliable opens', value: num(s.reliableOpens), foot: s.openRate + '% open rate', cls: 'accent' },
    { label: 'Apple MPP opens', value: num(s.mppOpens), foot: 'excluded from the rate', cls: '' },
    { label: 'Bot opens', value: num(s.botOpens), foot: 'excluded from the rate', cls: '' },
    { label: 'Clicks', value: num(s.reliableClicks), foot: s.clickRate + '% clicked, ' + s.clickToOpenRate + '% of openers', cls: 'accent' },
    { label: 'Bounced', value: num(s.bounced), foot: s.bounceRate + '% against a ' + s.bounceDangerLine + '% danger line',
      cls: Number(s.bounceRate) >= Number(s.bounceDangerLine) ? 'danger' : 'good' },
    { label: 'Complaints', value: num(s.complained), foot: s.complaintRate + '% of delivered', cls: Number(s.complaintRate) > 0.1 ? 'warn' : '' },
    { label: 'Unsubscribed', value: num(s.unsubscribed), foot: s.unsubscribeRate + '% of delivered', cls: '' }
  ].map(kpiTile).join('');

  // The honest headline is the point of this screen: say plainly how much the
  // unfiltered number would have overstated it.
  $('anClassifier').innerHTML = '<b>Unfiltered, this would read ' + esc(s.unfilteredOpenRate)
    + '% open rate</b> from ' + num(s.unfilteredOpens) + ' raw pixel loads, an inflation of '
    + esc(s.inflationFactor) + 'x. Apple Mail Privacy Protection pre-loads every image and security '
    + 'gateways sweep links, so both are recorded and neither is counted as a read.';

  // The server sends a full LocalDateTime; the window is whole days, so the
  // time half is noise.
  $('anSeriesRange').textContent = String(s.from).slice(0, 10) + ' to ' + String(s.to).slice(0, 10);
  drawSeries(d.series);

  $('anLinks').innerHTML = d.links.length ? d.links.map(l =>
    '<tr><td class="truncate"><a href="' + attr(l.url) + '" target="_blank" rel="noopener noreferrer">'
    + esc(l.url) + '</a></td><td>' + num(l.clicks) + '</td><td>' + num(l.unique) + '</td>'
    + '<td>' + esc(l.share) + '%</td></tr>').join('')
    : emptyRow(4, 'No clicks recorded in this window.');

  const clients = (d.clients && d.clients.clients) || [];
  $('anClients').innerHTML = clients.length ? clients.map(c =>
    '<tr><td>' + esc(c.name) + '</td><td>' + num(c.count) + '</td><td>' + esc(c.share) + '%</td></tr>').join('')
    : emptyRow(3, 'No reliable opens to break down yet.');

  // The segments are per campaign by definition: "who opened and did nothing" has no
  // meaning across a whole account, because a person can be a non-opener of one
  // message and a clicker of the next.
  loadSegments(picker.value);
}

/* =========================================================================
   Engagement segments
   ========================================================================= */

let segmentCampaignId = null;
let segmentKey = null;
let segmentPage = 0;

async function loadSegments(campaignId) {
  const panel = $('anSegmentPanel'), tto = $('anTtoPanel');
  segmentCampaignId = campaignId || null;

  if (!campaignId) {
    panel.style.display = 'none';
    tto.style.display = 'none';
    return;
  }
  panel.style.display = '';
  tto.style.display = '';

  let d;
  try { d = await api('/api/analytics/segments?campaignId=' + campaignId); }
  catch (e) { panel.style.display = 'none'; tto.style.display = 'none'; return; }

  const tile = s =>
    '<div class="kpi ' + (s.unmailable ? 'danger' : s.segment === 'CLICKED' ? 'accent' : '')
    + '" style="cursor:pointer" role="button" tabindex="0"'
    + ' onclick="openSegment(\'' + attr(s.segment) + '\')"'
    + ' onkeydown="if(event.key===\'Enter\')openSegment(\'' + attr(s.segment) + '\')"'
    + ' title="' + attr(s.description) + '">'
    + '<div class="label">' + esc(s.label) + '</div>'
    + '<div class="value">' + num(s.count) + '</div>'
    + '<div class="foot">' + s.share + '% of sent</div></div>';

  $('anSegmentsExclusive').innerHTML = d.exclusive.map(tile).join('');
  $('anSegmentsOverlap').innerHTML = d.overlapping.map(tile).join('');

  // The open rate is shown as a range whenever enough of the audience sits behind a
  // privacy proxy that a single number would be a guess dressed as a measurement.
  $('anSegmentRange').textContent = d.showAsRange
    ? 'open rate ' + d.openRateLower + '% to ' + d.openRateUpper + '%'
    : 'open rate ' + d.openRateLower + '%';

  $('anSegmentCaveat').innerHTML = esc(d.caveat) + '<br><br>' + esc(d.readingCaveat)
    + (d.showAsRange
        ? '<br><br><b>' + d.unknownShareOfPossibleOpeners + '% of the people who might have opened '
          + 'this cannot be verified either way</b>, so the real open rate is somewhere between '
          + d.openRateLower + '% and ' + d.openRateUpper + '%.'
        : '');

  loadTimeToOpen(campaignId);
}

async function loadTimeToOpen(campaignId) {
  let d;
  try { d = await api('/api/analytics/time-to-open?campaignId=' + campaignId); }
  catch (e) { $('anTtoPanel').style.display = 'none'; return; }

  $('anTtoMedian').textContent = d.medianLabel
    ? 'half had opened within ' + d.medianLabel : 'no opens yet';
  $('anTtoBasis').textContent = d.basis;
  drawTimeToOpen(d.buckets);
}

/** Seven bars, drawn by hand. Same reasoning as the day chart: no library here. */
function drawTimeToOpen(buckets) {
  const svg = $('anTtoChart');
  const max = Math.max(1, ...buckets.map(b => b.count));
  const parts = [];
  buckets.forEach((b, i) => {
    const x = 26 + i * 76;
    const h = Math.round(140 * b.count / max);
    parts.push('<rect x="' + x + '" y="' + (160 - h) + '" width="56" height="' + Math.max(h, 1)
      + '" rx="3" fill="#1f9d55" fill-opacity="0.8"/>');
    parts.push('<text x="' + (x + 28) + '" y="' + (154 - h) + '" text-anchor="middle"'
      + ' class="chart-axis" fill="#eaeaea">' + num(b.count) + '</text>');
    parts.push('<text x="' + (x + 28) + '" y="180" text-anchor="middle" class="chart-axis-sm"'
      + ' fill="#9a9a9a">' + esc(b.label) + '</text>');
  });
  svg.innerHTML = parts.join('');
}

function openSegment(segment) {
  segmentKey = segment;
  segmentPage = 0;
  $('segSearch').value = '';
  loadSegmentPeople();
  openModal('modalSegment');
}

async function loadSegmentPeople() {
  if (!segmentCampaignId || !segmentKey) return;
  const params = new URLSearchParams({
    campaignId: segmentCampaignId, segment: segmentKey,
    q: $('segSearch').value.trim(), page: segmentPage
  });

  let d;
  try { d = await api('/api/analytics/segment/people?' + params); }
  catch (e) { toast(e.message, 'err'); return; }

  $('segTitle').textContent = d.label;
  $('segDesc').textContent = d.description;
  $('segBody').innerHTML = d.rows.length ? d.rows.map(r =>
    '<tr><td>' + esc(r.name) + '</td>'
    + '<td class="truncate" style="max-width:230px">' + esc(r.email) + '</td>'
    + '<td>' + statusPill(r.status) + '</td>'
    + '<td class="num">' + num(r.opens) + '</td>'
    + '<td class="num">' + num(r.clicks) + '</td>'
    + '<td class="nowrap">' + esc(String(r.sentAt).replace('T', ' ').slice(0, 16)) + '</td></tr>').join('')
    : emptyRow(6, 'Nobody is in this group.');
  $('segCount').textContent = pagerText(d);
}

function pageSegment(delta) {
  segmentPage = Math.max(0, segmentPage + delta);
  loadSegmentPeople();
}

function exportSegment() {
  location.href = '/api/analytics/segment/export?campaignId=' + segmentCampaignId
    + '&segment=' + encodeURIComponent(segmentKey);
}

async function saveSegmentAsList() {
  const suggested = ($('anCampaign').selectedOptions[0] || {}).textContent || 'Campaign';
  const name = prompt('Name the new list:', suggested.trim() + ' - ' + $('segTitle').textContent);
  if (!name) return;
  try {
    const res = await post('/api/analytics/segment/save-as-list',
      { campaignId: segmentCampaignId, segment: segmentKey, name });
    toast(res.message, 'ok');
    await loadLists();
    fillListPickers();
  } catch (e) { toast(e.message, 'err'); }
}

/**
 * Two lines over a shared scale, drawn by hand. A charting library would be a
 * second megabyte of JavaScript for one screen.
 */
function drawSeries(series) {
  const svg = $('anChart');
  if (!series || !series.length) { svg.innerHTML = ''; return; }

  // Measure the container and make one SVG unit equal one CSS pixel. With a fixed
  // 860 unit viewBox squeezed into a 390px phone, everything inside is scaled down
  // 2.2x, text included, so no CSS font size can rescue the axis labels.
  const W = Math.max(320, Math.round(svg.parentElement.clientWidth || 860));
  const H = 240, PAD_L = 46, PAD_B = 22, PAD_T = 12;
  svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
  // Four gridlines, so the scale is rounded up to a multiple of 4. Without this a
  // quiet window peaking at 1 prints the axis as 1, 1, 1, 0, 0.
  const observed = Math.max(1, ...series.map(p => Math.max(p.sent || 0, p.reliableOpens || 0)));
  const peak = Math.ceil(observed / 4) * 4;
  const stepX = (W - PAD_L - 8) / Math.max(1, series.length - 1);
  const y = v => PAD_T + (H - PAD_T - PAD_B) * (1 - (v || 0) / peak);
  const x = i => PAD_L + i * stepX;

  const path = key => series.map((p, i) => (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(p[key]).toFixed(1)).join(' ');
  const area = key => path(key) + ' L' + x(series.length - 1).toFixed(1) + ' ' + (H - PAD_B)
    + ' L' + x(0).toFixed(1) + ' ' + (H - PAD_B) + ' Z';

  let out = '';
  for (let g = 0; g <= 4; g++) {
    const gy = PAD_T + (H - PAD_T - PAD_B) * g / 4;
    out += '<line x1="' + PAD_L + '" y1="' + gy + '" x2="' + W + '" y2="' + gy
      + '" stroke="rgba(255,255,255,.07)" stroke-width="1"/>'
      + '<text x="' + (PAD_L - 8) + '" y="' + (gy + 4) + '" class="chart-axis" text-anchor="end">'
      + Math.round(peak * (4 - g) / 4) + '</text>';
  }
  out += '<path d="' + area('sent') + '" fill="rgba(47,111,237,.14)"/>';
  out += '<path d="' + path('sent') + '" fill="none" stroke="#2f6fed" stroke-width="2"/>';
  out += '<path d="' + path('reliableOpens') + '" fill="none" stroke="#19a7a0" stroke-width="2"/>';

  const labelEvery = Math.ceil(series.length / 8);
  series.forEach((p, i) => {
    if (i % labelEvery) return;
    out += '<text x="' + x(i) + '" y="' + (H - 6) + '" class="chart-axis" text-anchor="middle">'
      + esc(String(p.day).slice(5)) + '</text>';
  });
  svg.innerHTML = out;
}

/* =========================================================================
   Message log
   ========================================================================= */
let messageLogPage = 0;

async function loadMessageLog() {
  const params = new URLSearchParams({
    q: $('mlSearch').value.trim(),
    outcome: $('mlOutcome').value,
    direction: $('mlDirection').value,
    page: messageLogPage, size: 50
  });

  let d;
  try { d = await api('/api/messagelog?' + params); }
  catch (e) { toast('Could not load the message log', 'err'); return; }

  const outcomes = $('mlOutcome');
  if (!outcomes.dataset.filled && d.outcomes) {
    outcomes.innerHTML = '<option value="">All outcomes</option>'
      + d.outcomes.map(o => '<option value="' + esc(o) + '">' + esc(o.toLowerCase()) + '</option>').join('');
    outcomes.dataset.filled = '1';
  }

  $('mlBody').innerHTML = d.rows.length ? d.rows.map(r =>
    '<tr><td class="mono nowrap">' + esc(r.at) + '</td>'
    + '<td class="mono truncate">' + esc(r.to) + '</td>'
    + '<td class="truncate">' + esc(r.subject) + '</td>'
    + '<td>' + statusPill(r.outcome) + '</td>'
    + '<td class="truncate">' + esc(r.serverResponse) + '</td>'
    + '<td><button class="btn btn-sm" onclick="openMessageDetail(' + r.id + ')">Detail</button></td></tr>').join('')
    : emptyRow(6, 'Nothing logged yet. Rows appear as soon as a message is sent.');

  messageLogPage = d.page;
  $('mlCount').textContent = pagerText(d) + ', kept ' + d.retentionDays + ' days';
  loadMessageLogSummary();
}

function pageMessageLog(delta) {
  if (messageLogPage + delta < 0) return;
  messageLogPage += delta;
  loadMessageLog();
}

/** The summary endpoint defaults to the last 24 hours, so the tiles say so. */
async function loadMessageLogSummary() {
  let s;
  try { s = await api('/api/messagelog/summary'); } catch (e) { return; }
  const by = s.byOutcome || {};
  $('mlKpis').innerHTML = [
    { label: 'Last 24 hours', value: num(s.total), foot: 'messages logged', cls: '' },
    { label: 'Delivered', value: num(by.DELIVERED || 0), foot: 'confirmed by the receiving server', cls: 'good' },
    { label: 'Accepted by SES', value: num(by.SENT || 0), foot: 'not yet confirmed delivered', cls: '' },
    { label: 'Bounced', value: num(by.BOUNCED || 0), foot: num(by.COMPLAINED || 0) + ' complaints', cls: 'danger' },
    { label: 'Failed', value: num(by.FAILED || 0), foot: num(by.SUPPRESSED || 0) + ' refused before sending', cls: 'warn' }
  ].map(kpiTile).join('');
}

async function openMessageDetail(id) {
  let d;
  try { d = await api('/api/messagelog/' + id); }
  catch (e) { toast('Could not load that message', 'err'); return; }

  const row = (k, v) => v === null || v === undefined || v === ''
    ? '' : '<tr><th style="width:170px;text-align:left;color:var(--text-mute);font-weight:600">'
      + k + '</th><td class="mono" style="word-break:break-all">' + esc(v) + '</td></tr>';

  $('messageDetailBody').innerHTML = '<div class="table-wrap"><table class="data"><tbody>'
    + row('Time', d.at) + row('Direction', d.direction) + row('From', d.from) + row('To', d.to)
    + row('Subject', d.subject) + row('Outcome', d.outcome) + row('Server response', d.serverResponse)
    + row('SES message id', d.sesMessageId) + row('Message id', d.messageId)
    + row('Campaign', d.campaignId) + row('Latency', d.latencyMs === null ? '' : d.latencyMs + ' ms')
    + row('Sent by', d.actor)
    + '</tbody></table></div>';
  openModal('modalMessage');
}

/* =========================================================================
   Boot
   ========================================================================= */
['cSubject', 'cPreheader'].forEach(id => $(id).addEventListener('input', schedulePreview));
applyPermissions();
loadOverview();
if (can('LISTS_READ')) loadLists();
if (can('CAMPAIGNS_READ')) loadCampaigns();
setInterval(() => { if ($('view-overview').classList.contains('active')) loadOverview(); }, 60000);

/* =========================================================================
   Mailboxes

   These rows are accounts on the mail server itself, not app users. The two
   directories are separate: deleting here stops mail for a live address and
   cannot be undone from this screen, so the destructive action confirms
   against the typed address rather than a yes/no box.
   ========================================================================= */

const MB = { domain: 'jarurat.care', accounts: [], editing: null };

function mbFmtBytes(bytes) {
  const b = Number(bytes || 0);
  if (b <= 0) return '0';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
  const v = b / Math.pow(1024, i);
  return (v >= 10 || i === 0 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
}

function mbFmtDate(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d)) return esc(iso);
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}

async function loadMailboxes() {
  const body = $('mbBody');
  const notice = $('mbNotice');
  notice.innerHTML = '';
  body.innerHTML = emptyRow(6, 'Loading...');

  let data;
  try { data = await api('/api/admin/mailboxes'); }
  catch (e) { body.innerHTML = emptyRow(6, e.message); return; }

  MB.domain = data.domain || 'jarurat.care';
  MB.accounts = data.accounts || [];
  const suffix = $('mbDomainSuffix');
  if (suffix) suffix.textContent = '@' + MB.domain;
  const badge = $('badgeMailboxes');
  if (badge) badge.textContent = MB.accounts.length;

  // A missing admin token is a configuration state, not a failure. Say so plainly
  // instead of rendering an empty table that reads as "there are no mailboxes".
  if (!data.configured) {
    notice.innerHTML = '<div class="alert warn" style="margin:0 0 16px">'
      + 'Mailbox administration is not configured. Set <span class="mono">STALWART_REFRESH_TOKEN</span> '
      + 'in the server environment and restart, then this screen can create and edit real mailboxes.</div>';
    body.innerHTML = emptyRow(6, 'Not configured');
    return;
  }

  if (!MB.accounts.length) { body.innerHTML = emptyRow(6, 'No mailboxes yet'); return; }

  body.innerHTML = MB.accounts.map(function (a) {
    const aliases = a.aliases || [];
    const aliasCell = aliases.length
      ? '<span class="pill pill-info">' + aliases.length + '</span> '
        + '<span class="mono" style="color:var(--text-dim);font-size:12px">'
        + esc(aliases.slice(0, 2).join(', ')) + (aliases.length > 2 ? ', ...' : '') + '</span>'
      : '<span style="color:var(--text-faint)">none</span>';
    const quota = a.quotaBytes
      ? mbFmtBytes(a.usedBytes) + ' / ' + mbFmtBytes(a.quotaBytes)
      : mbFmtBytes(a.usedBytes);
    const id = esc(a.id);
    return '<tr>'
      + '<td><span class="mono">' + esc(a.emailAddress) + '</span></td>'
      + '<td>' + esc(a.description || '') + '</td>'
      + '<td>' + aliasCell + '</td>'
      + '<td class="num">' + quota + '</td>'
      + '<td>' + mbFmtDate(a.createdAt) + '</td>'
      + '<td class="row-actions">'
      + '<button class="btn btn-sm" onclick="openMailboxAliases(&quot;' + id + '&quot;)">Aliases</button> '
      + '<button class="btn btn-sm" onclick="openMailboxPwd(&quot;' + id + '&quot;)">Password</button> '
      + '<button class="btn btn-sm btn-danger" onclick="deleteMailbox(&quot;' + id + '&quot;)">Delete</button>'
      + '</td></tr>';
  }).join('');
}

function mbFind(id) { return MB.accounts.find(function (a) { return a.id === id; }); }

/* Alphanumeric only, and no lookalike characters. These get read off a screen and
   typed into a phone mail client, where l/1 and O/0 turn into a support call. */
function genMailboxPassword(fieldId) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
  const values = new Uint32Array(20);
  crypto.getRandomValues(values);
  let out = '';
  for (let i = 0; i < values.length; i++) out += alphabet[values[i] % alphabet.length];
  const field = $(fieldId);
  field.value = out;
  field.type = 'text';
}

function openMailboxCreate() {
  $('mbLocal').value = '';
  $('mbDesc').value = '';
  $('mbPass').value = '';
  $('mbQuota').value = '2';
  $('mbCreateResult').innerHTML = '';
  const suffix = $('mbDomainSuffix');
  if (suffix) suffix.textContent = '@' + MB.domain;
  openModal('modalMailbox');
}

async function createMailbox() {
  const localPart = ($('mbLocal').value || '').trim().toLowerCase();
  const password = $('mbPass').value || '';
  const out = $('mbCreateResult');
  if (!localPart) { out.innerHTML = '<div class="alert err">Enter an address.</div>'; return; }
  if (password.length < 12) {
    out.innerHTML = '<div class="alert err">Password must be at least 12 characters.</div>';
    return;
  }

  const btn = $('mbCreateBtn');
  btn.disabled = true;
  try {
    const r = await postJson('/api/admin/mailboxes', {
      localPart: localPart,
      description: ($('mbDesc').value || '').trim(),
      password: password,
      quotaGb: Number($('mbQuota').value || 2)
    });
    // The password is never retrievable again, so it stays on screen until the
    // operator dismisses the modal rather than disappearing with the success toast.
    out.innerHTML = '<div class="alert ok" style="margin-top:12px">Created <span class="mono">'
      + esc(r.emailAddress) + '</span><br>Password: <span class="mono">' + esc(password)
      + '</span><br><em class="hint">Copy it now. It cannot be shown again.</em></div>';
    $('mbLocal').value = '';
    $('mbDesc').value = '';
    $('mbPass').value = '';
    toast('Mailbox created', 'ok');
    loadMailboxes();
  } catch (e) {
    out.innerHTML = '<div class="alert err" style="margin-top:12px">' + esc(e.message) + '</div>';
  } finally {
    btn.disabled = false;
  }
}

function openMailboxPwd(id) {
  const a = mbFind(id);
  if (!a) return;
  MB.editing = id;
  $('mbPwdTitle').textContent = 'Set password for ' + a.emailAddress;
  $('mbNewPass').value = '';
  $('mbPwdResult').innerHTML = '';
  openModal('modalMailboxPwd');
}

async function saveMailboxPassword() {
  const a = mbFind(MB.editing);
  const password = $('mbNewPass').value || '';
  const out = $('mbPwdResult');
  if (password.length < 12) {
    out.innerHTML = '<div class="alert err">Password must be at least 12 characters.</div>';
    return;
  }
  try {
    await postJson('/api/admin/mailboxes/' + encodeURIComponent(MB.editing) + '/password',
                   { password: password });
    out.innerHTML = '<div class="alert ok" style="margin-top:12px">Password for <span class="mono">'
      + esc(a ? a.emailAddress : '') + '</span> is now <span class="mono">' + esc(password)
      + '</span><br><em class="hint">Copy it now. It cannot be shown again.</em></div>';
    toast('Password updated', 'ok');
  } catch (e) {
    out.innerHTML = '<div class="alert err" style="margin-top:12px">' + esc(e.message) + '</div>';
  }
}

function openMailboxAliases(id) {
  const a = mbFind(id);
  if (!a) return;
  MB.editing = id;
  $('mbAliasTitle').textContent = 'Aliases for ' + a.emailAddress;
  // Held as full addresses, edited as bare names: the domain is fixed, and retyping
  // it on every line is only an opportunity to typo it.
  $('mbAliasText').value = (a.aliases || [])
    .map(function (x) { return String(x).split('@')[0]; }).join('\n');
  $('mbAliasResult').innerHTML = '';
  openModal('modalMailboxAliases');
}

async function saveMailboxAliases() {
  const a = mbFind(MB.editing);
  const out = $('mbAliasResult');
  const aliases = ($('mbAliasText').value || '')
    .split('\n')
    .map(function (x) { return x.trim().toLowerCase().split('@')[0]; })
    .filter(Boolean);
  try {
    await postJson('/api/admin/mailboxes/' + encodeURIComponent(MB.editing) + '/aliases',
                   { aliases: aliases }, 'PUT');
    toast(aliases.length + ' alias(es) saved for ' + (a ? a.emailAddress : ''), 'ok');
    closeModal('modalMailboxAliases');
    loadMailboxes();
  } catch (e) {
    out.innerHTML = '<div class="alert err" style="margin-top:12px">' + esc(e.message) + '</div>';
  }
}

async function deleteMailbox(id) {
  const a = mbFind(id);
  if (!a) return;
  // Typing the address is deliberate. This removes a live mailbox and everything in
  // it, and a yes/no box is far too easy to hit on the wrong row.
  const typed = prompt('This permanently deletes ' + a.emailAddress
    + ' and all mail in it.\n\nType the address to confirm:');
  if (typed === null) return;
  if (typed.trim().toLowerCase() !== a.emailAddress.toLowerCase()) {
    toast('That did not match, nothing was deleted', 'warn');
    return;
  }
  try {
    await postJson('/api/admin/mailboxes/' + encodeURIComponent(id), undefined, 'DELETE');
    toast('Deleted ' + a.emailAddress, 'ok');
    loadMailboxes();
  } catch (e) { toast(e.message, 'err'); }
}


/* -------------------------------------------------------------------------
   Overview charts

   The donut is derived from the same totals as the tiles above it, so it can
   never disagree with them. The series is a second request because the daily
   breakdown lives on the analytics endpoint, and it is skipped entirely for a
   role without ANALYTICS_READ rather than firing a request that would 403.
   ------------------------------------------------------------------------- */
function drawOverviewCharts(d) {
  const donut = $('ovEngagement');
  if (donut) {
    if (d.totalSent === undefined) {
      drawDonut(donut, [], { empty: 'Your role does not include campaign figures.' });
    } else {
      const clicked = Number(d.totalClicked || 0);
      const opened = Number(d.totalOpened || 0);
      const sent = Number(d.totalSent || 0);
      drawDonut(donut, [
        { label: 'Clicked', value: clicked, color: '#2f6fed' },
        { label: 'Opened, no click', value: Math.max(0, opened - clicked), color: '#19a7a0' },
        { label: 'Not opened', value: Math.max(0, sent - opened), color: '#3a3a3a' }
      ], {
        centerValue: (d.openRate !== undefined ? d.openRate + '%' : ''),
        centerLabel: 'open rate',
        empty: 'Nothing sent yet.'
      });
    }
  }

  const host = $('ovSeriesChart');
  if (!host) return;
  if (!can('ANALYTICS_READ')) {
    drawArea(host, [], { empty: 'Your role does not include analytics.' });
    return;
  }
  api('/api/analytics/overview?days=14').then(a => {
    const series = (a && a.series) || [];
    const range = $('ovSeriesRange');
    if (range && a && a.summary) {
      range.textContent = String(a.summary.from).slice(0, 10) + ' to ' + String(a.summary.to).slice(0, 10);
    }
    drawArea(host, [
      { label: 'Sent', color: '#2f6fed',
        points: series.map(p => ({ x: String(p.day).slice(5), y: p.sent })) },
      { label: 'Reliable opens', color: '#19a7a0',
        points: series.map(p => ({ x: String(p.day).slice(5), y: p.reliableOpens })) }
    ], { height: 240, empty: 'No sends in the last 14 days.' });
  }).catch(() => drawArea(host, [], { empty: 'Could not load the daily series.' }));
}


/* =========================================================================
   Domains

   A DNS health board. Every record here is one that silently stops mail when it
   is wrong, and the failure is always invisible from inside the product, so the
   point of the screen is to make it visible without anyone running dig.
   ========================================================================= */

const DNS_ROWS = [
  ['mx',    'MX',    'Where other servers deliver mail for this domain'],
  ['spf',   'SPF',   'Which servers are allowed to send as this domain'],
  ['dkim',  'DKIM',  'Signing keys that prove a message was not forged'],
  ['dmarc', 'DMARC', 'What receivers should do when SPF and DKIM fail']
];

function dnsPill(status) {
  const map = { OK: 'pill-sent', WARN: 'pill-pending', FAIL: 'pill-failed', UNKNOWN: 'pill-draft' };
  const s = String(status || 'UNKNOWN').toUpperCase();
  return '<span class="pill ' + (map[s] || 'pill-draft') + '">' + esc(s) + '</span>';
}

async function loadDomains() {
  const host = $('domainsBody');
  host.innerHTML = '<div class="panel"><div class="panel-body">'
                 + '<div class="empty">Checking DNS...</div></div></div>';

  let d;
  try { d = await api('/api/admin/domains'); }
  catch (e) {
    host.innerHTML = '<div class="alert err">' + esc(e.message) + '</div>';
    return;
  }

  const domains = (d && d.domains) || [];

  // The DNS checks run with no admin token at all, so an unconfigured directory
  // only costs the mailbox counts. Say that rather than hiding a working page.
  const notice = (d && d.configured === false)
    ? '<div class="alert warn" style="margin:0 0 16px">'
      + esc(d.directoryDetail || 'Mailbox administration is not configured, so mailbox and alias counts are unavailable.')
      + '</div>'
    : '';

  if (!domains.length) {
    host.innerHTML = '<div class="panel"><div class="panel-body">'
                   + '<div class="empty">No sending domains configured.</div></div></div>';
    return;
  }

  host.innerHTML = notice + domains.map(dom => {
    const dns = dom.dns || {};
    const bad = DNS_ROWS.filter(r => {
      const st = String((dns[r[0]] || {}).status || '').toUpperCase();
      return st === 'FAIL' || st === 'WARN';
    }).length;

    const rows = DNS_ROWS.map(([key, label, why]) => {
      const c = dns[key] || {};
      const found = (c.found || []);
      const value = found.length
        ? found.map(f => '<div class="mono dns-value">' + esc(f) + '</div>').join('')
        : '<span style="color:var(--text-faint)">not found</span>';
      return '<tr>'
        + '<td style="white-space:nowrap"><strong>' + esc(label) + '</strong>'
        + '<div class="sub" style="margin:2px 0 0">' + esc(why) + '</div></td>'
        + '<td>' + dnsPill(c.status) + '</td>'
        + '<td style="min-width:0">' + value
        + (c.detail ? '<div class="sub" style="margin-top:4px">' + esc(c.detail) + '</div>' : '')
        + '</td></tr>';
    }).join('');

    return '<div class="panel" style="margin-bottom:18px">'
      + '<div class="panel-head"><h3>' + esc(dom.name) + '</h3>'
      + (dom.isDefault ? '<span class="pill pill-info">sending domain</span>' : '')
      + '<span class="panel-note">'
      + (dom.accountCount === null || dom.accountCount === undefined
          ? 'mailbox count unavailable'
          : num(dom.accountCount) + ' mailbox(es), ' + num(dom.aliasCount || 0) + ' alias(es)')
      + (bad ? ' &middot; ' + bad + (bad === 1 ? ' needs' : ' need') + ' attention' : ' &middot; all checks passing')
      + '</span></div>'
      + '<div class="panel-body tight"><div class="table-wrap"><table class="data">'
      + '<thead><tr><th style="width:210px">Record</th><th style="width:110px">Status</th><th>Found</th></tr></thead>'
      + '<tbody>' + rows + '</tbody></table></div></div></div>';
  }).join('');
}


/* =========================================================================
   Responsive table labels

   Below 760px every data row becomes a card, and a card has no column headings
   above it. Each cell therefore has to carry its own heading, which CSS pulls in
   with content: attr(data-label).

   Stamping happens from a MutationObserver rather than from each of the
   seventeen loaders. Every one of them builds its tbody with innerHTML, so there
   is no single place to hook, and a loader added later would silently miss the
   step and ship a screen of unlabelled values on phones. Watching the DOM cannot
   be forgotten.
   ========================================================================= */

function stampTableLabels(root) {
  const tables = (root && root.querySelectorAll)
    ? root.querySelectorAll('table.data')
    : document.querySelectorAll('table.data');

  tables.forEach(table => {
    const heads = Array.prototype.map.call(
      table.querySelectorAll('thead th'), th => th.textContent.trim());
    if (!heads.length) return;

    table.querySelectorAll('tbody tr').forEach(tr => {
      // The empty-state and loading rows are a single wide cell, not a record.
      if (tr.querySelector('td[colspan]')) return;

      Array.prototype.forEach.call(tr.children, (td, i) => {
        const head = heads[i];
        if (head) {
          td.setAttribute('data-label', head);
          td.removeAttribute('data-actions');
          // A cell with no content under a heading reads as a broken card.
          if (!td.textContent.trim() && !td.querySelector('img,svg,input,button')) {
            td.setAttribute('data-empty', '');
          } else {
            td.removeAttribute('data-empty');
          }
        } else {
          // Header cells are deliberately blank above an actions column.
          td.setAttribute('data-actions', '');
          td.removeAttribute('data-label');
        }
      });
    });
  });
}

/* One observer for the whole app. Tables are re-rendered constantly (filters,
   paging, polling), so subtree watching is the only thing that keeps up. */
(function watchTables() {
  const run = () => {
    stampTableLabels(document);
    const observer = new MutationObserver(records => {
      let touched = false;
      for (const r of records) {
        const target = r.target;
        if (target && target.closest && target.closest('table.data')) { touched = true; break; }
      }
      if (!touched) return;
      // Detach while stamping: setAttribute inside the callback would otherwise
      // re-enter this observer on every cell and burn the main thread.
      observer.disconnect();
      stampTableLabels(document);
      observer.observe(document.body, { childList: true, subtree: true });
    });
    observer.observe(document.body, { childList: true, subtree: true });
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
})();

/* Charts size themselves from their container at draw time, which is what makes
   the axis text legible on a phone. The cost is that they do not follow a resize,
   so rotating a handset leaves a chart drawn for the old width. Re-running the
   current view's loader is the cheapest correct fix: it redraws from data already
   in hand. Debounced, because a rotation fires a burst of resize events. */
(function redrawOnResize() {
  let timer = null;
  let lastWidth = window.innerWidth;
  window.addEventListener('resize', () => {
    // Ignore the vertical-only resize an on-screen keyboard causes.
    if (window.innerWidth === lastWidth) return;
    lastWidth = window.innerWidth;
    clearTimeout(timer);
    timer = setTimeout(() => {
      const active = document.querySelector('.view.active');
      if (!active) return;
      const view = active.id.replace(/^view-/, '');
      if (LOADERS[view]) LOADERS[view]();
    }, 250);
  });
})();
