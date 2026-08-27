/* =========================================================================
   JCF Campaign Studio - multi email journeys
   -------------------------------------------------------------------------
   The flowchart canvas is plain HTML boxes positioned over one SVG layer of
   wires. No library, because the console has no build step and no npm, and a
   canvas that needed a bundler would be the only thing here that did.

   Loaded after console.js and reuses its helpers: $, esc, attr, num, api,
   post, toast, openModal, closeModal, emptyRow, pagerText, statusPill, can.
   ========================================================================= */

let journeyId = null;
let journeyData = null;          // { journey, nodes, edges, stats, conditions, buckets }
let selectedNodeId = null;
let connectFrom = null;          // node id waiting for a target
let journeyEventPage = 0;
let sheetBucket = null;
let sheetPage = 0;
let journeySaveTimer = null;

const NODE_W = 216;
const NODE_H = 96;

/* =========================================================================
   The list
   ========================================================================= */

async function loadJourneys() {
  let rows;
  try { rows = await api('/api/journeys'); }
  catch (e) { toast('Could not load journeys', 'err'); return; }

  const badge = $('badgeJourneys');
  if (badge) badge.textContent = rows.length;

  $('journeyBody').innerHTML = rows.length ? rows.map(j =>
    '<tr>'
    + '<td><a href="#" onclick="openJourney(' + j.id + ');return false" style="color:var(--text);font-weight:600">'
      + esc(j.name) + '</a>'
      + (j.description ? '<div class="sub" style="font-size:11.5px">' + esc(j.description) + '</div>' : '')
      + (j.pauseReason ? '<div style="font-size:11.5px;color:var(--warning)">' + esc(j.pauseReason) + '</div>' : '')
      + '</td>'
    + '<td>' + statusPill(j.status) + '</td>'
    + '<td class="num">' + num(j.nodes) + '</td>'
    + '<td class="num">' + num(j.participants) + '</td>'
    + '<td class="num">' + num(j.active) + '</td>'
    + '<td class="nowrap">' + esc(j.createdAt) + '</td>'
    + '<td><button class="btn btn-sm" onclick="openJourney(' + j.id + ')">Open</button></td>'
    + '</tr>').join('')
    : emptyRow(7, 'No journeys yet. A journey is a campaign with more than one email in it.');
}

function openCampaignKind() { openModal('modalCampaignKind'); }

function chooseCampaignKind(kind) {
  closeModal('modalCampaignKind');
  if (kind === 'single') { newCampaign(); return; }
  go('journeys');
  openModal('modalJourney');
}

async function createJourney() {
  const name = $('mjName').value.trim();
  if (!name) { toast('Give the journey a name', 'warn'); return; }
  try {
    const res = await post('/api/journeys', { name, description: $('mjDesc').value });
    closeModal('modalJourney');
    $('mjName').value = ''; $('mjDesc').value = '';
    await openJourney(res.id);
    toast('Journey created. Start by adding a base sheet.', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   The editor
   ========================================================================= */

async function openJourney(id) {
  journeyId = id;
  selectedNodeId = null;
  connectFrom = null;
  journeyEventPage = 0;
  await refreshJourney();
  go('journey');
}

async function refreshJourney() {
  if (!journeyId) return;
  try { journeyData = await api('/api/journeys/' + journeyId); }
  catch (e) { toast('Could not load that journey', 'err'); return; }

  const j = journeyData.journey;
  $('jrnTitle').textContent = j.name;
  $('jrnStatus').textContent = j.status;
  $('jrnStatus').className = 'pill plain ' + (statusPill(j.status).match(/pill-[a-z]+/) || ['pill-draft'])[0];

  const s = journeyData.stats;
  $('jrnSub').textContent = num(s.participants) + ' people have entered, ' + num(s.active)
    + ' still moving, ' + num(s.exited) + ' finished.'
    + (j.structurallyEditable ? '' : ' The shape is locked while it runs; pause to change it.');

  const pause = $('jrnPauseNote');
  if (j.pauseReason && j.status === 'PAUSED') {
    pause.style.display = '';
    pause.textContent = j.pauseReason;
  } else { pause.style.display = 'none'; }

  const go1 = $('jrnGo');
  if (j.status === 'ACTIVE') { go1.textContent = 'Pause'; go1.onclick = pauseJourney; }
  else if (j.status === 'PAUSED') { go1.textContent = 'Resume'; go1.onclick = resumeJourney; }
  else if (j.status === 'DRAFT') { go1.textContent = 'Activate'; go1.onclick = activateJourney; }
  else { go1.textContent = j.status; go1.onclick = () => toast('This journey has finished.', 'warn'); }

  renderCanvas();
  renderPanel();
  renderSheets();
  fillAbPicker();
}

/* ---------- canvas ---------- */

function renderCanvas() {
  const canvas = $('jrnCanvas');
  const nodes = journeyData.nodes;

  // Rebuild the surface each time rather than diffing. At the scale a human can
  // draw by hand this is a few dozen boxes, and a diff would be more code than
  // it saves.
  canvas.innerHTML = '<div class="jrn-surface" id="jrnSurface">'
    + '<svg class="jrn-wires" id="jrnWires" aria-hidden="true"></svg></div>';
  const surface = $('jrnSurface');

  nodes.forEach(n => {
    const el = document.createElement('div');
    el.className = 'jrn-node' + (n.id === selectedNodeId ? ' selected' : '')
      + (n.id === connectFrom ? ' connect-source' : '');
    el.dataset.kind = n.type;
    el.dataset.nodeId = n.id;
    el.style.left = (n.x || 0) + 'px';
    el.style.top = (n.y || 0) + 'px';
    el.tabIndex = 0;
    el.setAttribute('role', 'button');
    el.setAttribute('aria-label', kindLabel(n.type) + ': ' + n.name + '. ' + nodeSummary(n));

    el.innerHTML =
      '<div class="jrn-kind">' + esc(kindLabel(n.type)) + '</div>'
      + '<div class="jrn-name">' + esc(n.name) + '</div>'
      + '<div class="jrn-meta">' + nodeSummary(n) + '</div>'
      + (n.here ? '<span class="jrn-here">' + num(n.here) + ' here now</span>' : '')
      + '<button class="jrn-port" title="Connect this step to another"'
        + ' aria-label="Connect ' + attr(n.name) + ' to another step">&#8594;</button>';

    surface.appendChild(el);
  });

  wireCanvasEvents(surface);
  drawWires();
}

function kindLabel(type) {
  return { SOURCE: 'Base sheet', EMAIL: 'Email', SPLIT: 'A/B split',
           CONDITION: 'Condition', WAIT: 'Wait', EXIT: 'Exit' }[type] || type;
}

function nodeSummary(n) {
  if (n.type === 'SOURCE') {
    return n.sourceListName ? esc(n.sourceListName)
      : '<span style="color:var(--warning)">no list chosen</span>';
  }
  if (n.type === 'EMAIL') {
    const when = n.absoluteAt ? 'at ' + esc(String(n.absoluteAt).replace('T', ' ').slice(0, 16))
      : (n.delayMinutes ? delayText(n.delayMinutes) + ' after the step before' : 'straight away');
    return (n.subject ? esc(n.subject) : '<span style="color:var(--warning)">no subject</span>')
      + '<br><span style="color:var(--text-mute)">' + when + '</span>';
  }
  if (n.type === 'SPLIT') {
    const arms = outgoingOf(n.id);
    const total = arms.reduce((a, e) => a + (e.weight || 0), 0) || 1;
    return arms.length
      ? arms.map(e => esc(e.armCode || '?') + ' ' + Math.round(e.weight / total * 100) + '%').join(' &middot; ')
      : '<span style="color:var(--warning)">no versions yet</span>';
  }
  if (n.type === 'CONDITION') {
    return 'judged ' + delayText(n.evaluateAfterMinutes) + ' after the message<br>'
      + '<span style="color:var(--text-mute)">' + outgoingOf(n.id).length + ' branch(es)</span>';
  }
  if (n.type === 'WAIT') return delayText(n.delayMinutes);
  if (n.type === 'EXIT') {
    const b = (journeyData.buckets || []).find(x => x.value === n.exitBucket);
    return b ? 'filed as ' + esc(b.label) : 'leaves the journey';
  }
  return '';
}

function delayText(minutes) {
  const m = Number(minutes || 0);
  if (!m) return 'immediately';
  if (m < 60) return m + ' min';
  if (m < 1440) return Math.round(m / 60 * 10) / 10 + ' h';
  return Math.round(m / 1440 * 10) / 10 + ' days';
}

function outgoingOf(nodeId) {
  return (journeyData.edges || []).filter(e => e.from === nodeId);
}

function nodeById(id) { return (journeyData.nodes || []).find(n => n.id === id); }

function drawWires() {
  const svg = $('jrnWires');
  if (!svg) return;

  const defs = '<defs>'
    + '<marker id="jrnArrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7"'
      + ' orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="#2f6fed"/></marker>'
    + '<marker id="jrnArrowLoop" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7"'
      + ' orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="#d99e0b"/></marker>'
    + '</defs>';

  const parts = [defs];
  (journeyData.edges || []).forEach(e => {
    const from = nodeById(e.from), to = nodeById(e.to);
    if (!from || !to) return;

    const x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2;
    const x2 = to.x, y2 = to.y + NODE_H / 2;
    // A loop points backwards, so a plain S curve would run straight through the
    // boxes between. Bowing it under the row keeps it readable.
    const loop = e.loopBack;
    const bend = loop ? Math.max(90, Math.abs(x1 - x2) / 2) : Math.max(50, Math.abs(x2 - x1) / 2);
    const d = loop
      ? 'M' + x1 + ' ' + y1 + ' C' + (x1 + bend) + ' ' + (y1 + 150) + ','
        + (x2 - bend) + ' ' + (y2 + 150) + ',' + x2 + ' ' + y2
      : 'M' + x1 + ' ' + y1 + ' C' + (x1 + bend) + ' ' + y1 + ','
        + (x2 - bend) + ' ' + y2 + ',' + x2 + ' ' + y2;

    parts.push('<path d="' + d + '" fill="none" stroke="' + (loop ? '#d99e0b' : '#2f6fed')
      + '" stroke-width="1.9" stroke-opacity="' + (e.exhausted ? '.45' : '.8') + '"'
      + (loop || e.exhausted ? ' stroke-dasharray="6 5"' : '')
      + ' marker-end="url(#' + (loop ? 'jrnArrowLoop' : 'jrnArrow') + ')"/>');

    const label = e.exhausted ? 'gave up' : (e.conditionLabel || e.armCode || '');
    if (label) {
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2 + (loop ? 78 : -8);
      parts.push('<text x="' + mx + '" y="' + my + '" text-anchor="middle" font-size="10.5"'
        + ' fill="' + (loop ? '#d99e0b' : '#bdbdbd') + '">' + esc(label) + '</text>');
    }
  });
  svg.innerHTML = parts.join('');
}

/* ---------- drag, select, connect ---------- */

function wireCanvasEvents(surface) {
  let dragging = null, startX = 0, startY = 0, originX = 0, originY = 0, moved = false;

  surface.addEventListener('mousedown', e => {
    const port = e.target.closest('.jrn-port');
    const node = e.target.closest('.jrn-node');
    if (!node) return;

    if (port) {
      e.preventDefault();
      beginConnect(Number(node.dataset.nodeId));
      return;
    }
    if (!can('CAMPAIGNS_WRITE')) return;

    dragging = node;
    moved = false;
    startX = e.clientX; startY = e.clientY;
    originX = parseInt(node.style.left, 10) || 0;
    originY = parseInt(node.style.top, 10) || 0;
    node.classList.add('dragging');
  });

  document.addEventListener('mousemove', e => {
    if (!dragging) return;
    const dx = e.clientX - startX, dy = e.clientY - startY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;
    const x = Math.max(0, originX + dx), y = Math.max(0, originY + dy);
    dragging.style.left = x + 'px';
    dragging.style.top = y + 'px';
    const node = nodeById(Number(dragging.dataset.nodeId));
    if (node) { node.x = x; node.y = y; }
    drawWires();
  });

  document.addEventListener('mouseup', async () => {
    if (!dragging) return;
    const el = dragging;
    dragging = null;
    el.classList.remove('dragging');
    const id = Number(el.dataset.nodeId);
    if (!moved) { selectNode(id); return; }
    const node = nodeById(id);
    // Position is cosmetic, so this deliberately does not bump the definition
    // version: tidying the layout is not a structural change.
    try { await post('/api/journeys/' + journeyId + '/nodes/' + id + '/move', { x: node.x, y: node.y }); }
    catch (err) { /* the position will be restored on the next load */ }
  });

  surface.addEventListener('click', e => {
    const node = e.target.closest('.jrn-node');
    if (!node) return;
    const id = Number(node.dataset.nodeId);
    if (connectFrom !== null && connectFrom !== id) { finishConnect(id); return; }
  });

  surface.addEventListener('keydown', e => {
    const node = e.target.closest('.jrn-node');
    if (!node) return;
    const id = Number(node.dataset.nodeId);
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectNode(id); }
    // The port is an 11px target, so the keyboard needs its own way to connect.
    if (e.key === 'c' || e.key === 'C') {
      e.preventDefault();
      connectFrom === null ? beginConnect(id) : finishConnect(id);
    }
    if (e.key === 'Escape' && connectFrom !== null) cancelConnect();
  });
}

function beginConnect(id) {
  connectFrom = id;
  const node = nodeById(id);
  announce('Connecting from "' + (node ? node.name : '') + '". Click the step it should lead to, '
    + 'or press Escape to cancel.');
  renderCanvas();
}

function cancelConnect() {
  connectFrom = null;
  announce('Connection cancelled.');
  renderCanvas();
}

async function finishConnect(toId) {
  const fromId = connectFrom;
  connectFrom = null;
  if (!fromId || fromId === toId) { renderCanvas(); return; }

  const from = nodeById(fromId), to = nodeById(toId);
  const params = { fromNodeId: fromId, toNodeId: toId };

  // A branch off a condition has to say which outcome it is for, and a split arm
  // needs a letter, or the canvas shows an unlabelled wire nobody can read.
  if (from && from.type === 'CONDITION') {
    params.condition = defaultConditionFor(fromId);
  } else if (from && from.type === 'SPLIT') {
    const used = outgoingOf(fromId).map(e => e.armCode);
    params.armCode = 'ABCDEFGH'.split('').find(c => used.indexOf(c) < 0) || 'A';
    params.weight = 1;
  }

  try {
    const res = await post('/api/journeys/' + journeyId + '/edges', params);
    announce('Connected "' + (from ? from.name : '') + '" to "' + (to ? to.name : '') + '"'
      + (res.loopBack ? ', which loops back' : '') + '.');
    if (res.loopBack) toast('That connection loops back. It is capped at '
      + journeyData.journey.maxLoopIterations + ' passes per person.', 'warn');
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); renderCanvas(); }
}

/** The first outcome not already covered, so branches do not silently duplicate. */
function defaultConditionFor(nodeId) {
  const used = outgoingOf(nodeId).map(e => e.condition);
  const order = ['CLICKED', 'OPENED_NOT_CLICKED', 'NOT_OPENED', 'NOT_DELIVERED', 'ELSE'];
  return order.find(c => used.indexOf(c) < 0) || 'ELSE';
}

function announce(message) {
  const el = $('jrnLive');
  if (el) el.textContent = message;
}

function selectNode(id) {
  selectedNodeId = id;
  renderCanvas();
  renderPanel();
}

async function addJourneyNode(type) {
  if (!journeyId) return;
  // Drop it clear of whatever is already there rather than on top of it.
  const nodes = journeyData.nodes || [];
  const x = 60 + (nodes.length % 4) * 280;
  const y = 60 + Math.floor(nodes.length / 4) * 170;
  try {
    const res = await post('/api/journeys/' + journeyId + '/nodes', { type, x, y });
    await refreshJourney();
    selectNode(res.id);
    announce('Added a ' + kindLabel(type).toLowerCase() + '.');
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   The side panel
   ========================================================================= */

function renderPanel() {
  const panel = $('jrnPanel');
  const node = nodeById(selectedNodeId);
  const editable = journeyData && journeyData.journey.structurallyEditable;

  if (!node) {
    $('jrnPanelTitle').textContent = 'Nothing selected';
    panel.innerHTML = '<p class="sub" style="margin:0">Pick a step on the left, or add one from the '
      + 'toolbar. Start with a base sheet: that is the list people enter from.</p>';
    return;
  }

  $('jrnPanelTitle').textContent = kindLabel(node.type);
  let html = field('Name', input('jn-name', node.name, 'text'));

  if (node.type === 'SOURCE') html += sourcePanel(node);
  if (node.type === 'EMAIL') html += emailPanel(node);
  if (node.type === 'SPLIT') html += splitPanel(node);
  if (node.type === 'CONDITION') html += conditionPanel(node);
  if (node.type === 'WAIT') html += field('Wait for (minutes)', input('jn-delay', node.delayMinutes, 'number'));
  if (node.type === 'EXIT') html += exitPanel(node);

  html += connectionsPanel(node);

  html += '<div style="display:flex;gap:8px;margin-top:14px">'
    + '<button class="btn" style="flex:1" onclick="saveNode()">Save step</button>';
  if (node.type !== 'SOURCE' && editable) {
    html += '<button class="btn btn-sm" onclick="openCopyBranch(' + node.id + ')">Copy branch</button>';
  }
  html += '</div>';

  if (editable) {
    html += '<button class="btn btn-danger" style="width:100%;margin-top:8px" onclick="deleteNode()">'
      + 'Remove this step</button>';
  } else {
    html += '<p class="sub" style="margin:12px 0 0">The shape is locked while the journey runs. '
      + 'Pause it to add, remove or rewire steps. Wording and timing can still be edited.</p>';
  }

  panel.innerHTML = html;
  if (node.type === 'EMAIL') refreshNodeTestFields();
}

function field(label, control, hint) {
  return '<label class="field"><span>' + esc(label)
    + (hint ? ' <em class="hint">' + esc(hint) + '</em>' : '') + '</span>' + control + '</label>';
}

function input(id, value, type) {
  return '<input class="input" id="' + id + '" type="' + (type || 'text') + '" value="'
    + attr(value === null || value === undefined ? '' : value) + '">';
}

function sourcePanel(node) {
  const options = ['<option value="">-- choose a list --</option>'].concat(
    (listCache || []).map(l => '<option value="' + l.id + '"'
      + (String(l.id) === String(node.sourceListId) ? ' selected' : '') + '>'
      + esc(l.name) + ' (' + num(l.mailable) + ' mailable)</option>')).join('');
  return field('Base sheet', '<select class="input" id="jn-list">' + options + '</select>',
    'the list people enter from');
}

function emailPanel(node) {
  const tags = mergeTagsIn([node.subject, node.preheader, node.htmlBody]);
  const bytes = new Blob([node.htmlBody || '']).size;

  return '<button class="btn btn-primary" style="width:100%;margin-bottom:12px"'
      + ' onclick="openNodeEmailEditor(' + node.id + ')">Open the email editor</button>'
    + '<p class="sub" style="margin:0 0 14px">Full HTML, merge tags, live preview and a test send, '
      + 'the same editor a single-email campaign gets.'
      + (bytes ? ' Currently ' + Math.round(bytes / 1024 * 10) / 10 + 'KB'
                 + (tags.length ? ', ' + tags.length + ' merge tag(s).' : '.') : '')
      + '</p>'
    + field('Send this long after the step before (minutes)', input('jn-delay', node.delayMinutes, 'number'),
      '0 means as soon as they arrive')
    + field('Subject', input('jn-subject', node.subject))
    + field('Preheader', input('jn-preheader', node.preheader), 'the grey preview line')
    + field('From name', input('jn-fromName', node.fromName), 'blank uses the journey default')
    + field('Reply-to', input('jn-replyTo', node.replyTo))
    + '<label class="field"><span>Body HTML <em class="hint">quick edits; use the editor above for real work</em></span>'
      + '<textarea class="input mono" id="jn-html" rows="7" oninput="debounce(refreshNodeTestFields,400)()">'
      + esc(node.htmlBody) + '</textarea></label>'
    + '<div style="display:flex;gap:18px;margin:2px 0 12px">'
      + '<label class="check"><input type="checkbox" id="jn-trackOpens"'
        + (node.trackOpens ? ' checked' : '') + '> Track opens</label>'
      + '<label class="check"><input type="checkbox" id="jn-trackClicks"'
        + (node.trackClicks ? ' checked' : '') + '> Track clicks</label>'
    + '</div>'
    + '<div class="panel" style="padding:12px;margin-bottom:4px">'
      + '<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">'
        + '<span class="eyebrow">Send a test</span><span class="spacer"></span>'
        + '<button class="btn btn-sm" onclick="testSendNode()">Test</button></div>'
      + '<input class="input" id="jn-testTo" placeholder="you@example.com" style="margin-bottom:8px">'
      + '<div id="jn-testFields" class="merge-grid"></div>'
      + '<p id="jn-testNote" style="font-size:11.5px;color:var(--text-mute);margin:8px 0 0"></p>'
    + '</div>';
}

function splitPanel(node) {
  const arms = outgoingOf(node.id);
  const total = arms.reduce((a, e) => a + (e.weight || 0), 0);
  let html = '<p class="sub" style="margin:0 0 10px">Everything under this step is one stage. '
    + 'Give each version a weight; they are normalised, so 40/40/30 becomes 36/36/27 rather than '
    + 'an error. A version set to 0 receives nobody, which is how you pause one without losing '
    + 'its history.</p>';

  html += arms.map(e => {
    const to = nodeById(e.to);
    return '<div class="jrn-branch">'
      + '<input class="input" style="width:44px;padding:5px 7px;text-align:center" value="'
        + attr(e.armCode || '') + '" onchange="saveEdgeField(' + e.id + ', \'armCode\', this.value)">'
      + '<input class="input" style="width:74px;padding:5px 7px" type="number" min="0" value="'
        + attr(e.weight) + '" onchange="saveEdgeField(' + e.id + ', \'weight\', this.value)">'
      + '<span class="grow truncate">' + (total ? Math.round(e.weight / total * 100) + '% &rarr; ' : '&rarr; ')
        + esc(to ? to.name : 'nowhere') + '</span>'
      + '<button class="btn btn-sm btn-danger" onclick="deleteEdge(' + e.id + ')">&times;</button>'
      + '</div>';
  }).join('');

  if (!arms.length) {
    html += '<div class="alert warn" style="margin:0 0 10px">No versions yet. Use the arrow on the '
      + 'canvas to connect this step to two or more emails.</div>';
  }
  return html;
}

function conditionPanel(node) {
  const branches = outgoingOf(node.id);
  const catalogue = journeyData.conditions || [];
  const hasElse = branches.some(e => e.condition === 'ELSE');

  let html = field('Judge the result this long after the message reached them (minutes)',
      input('jn-evaluateAfter', node.evaluateAfterMinutes, 'number'),
      '2880 is 48 hours');

  html += '<p class="sub" style="margin:0 0 10px">Each branch is one outcome. The list is fixed on '
    + 'purpose: every entry maps to something the platform actually measures.</p>';

  html += branches.map(e => {
    const to = nodeById(e.to);
    const options = catalogue.map(c => '<option value="' + c.value + '"'
      + (c.value === e.condition ? ' selected' : '') + '>' + esc(c.label) + '</option>').join('');
    return '<div class="jrn-branch" style="flex-wrap:wrap">'
      + '<select class="input grow" style="padding:5px 7px" onchange="saveEdgeField('
        + e.id + ', \'condition\', this.value)">' + options + '</select>'
      + '<button class="btn btn-sm btn-danger" onclick="deleteEdge(' + e.id + ')">&times;</button>'
      + '<div style="flex-basis:100%;font-size:11.5px;color:var(--text-mute);margin-top:3px">&rarr; '
        + esc(to ? to.name : 'nowhere')
        + (e.exhausted ? ' <b style="color:var(--warning)">(used when the loop gives up)</b>' : '')
        + '</div>'
      + (e.condition === 'CLICKED_SPECIFIC'
          ? '<input class="input" style="flex-basis:100%;margin-top:6px;padding:5px 7px" placeholder="part of the link URL" value="'
            + attr(e.conditionArg) + '" onchange="saveEdgeField(' + e.id + ', \'conditionArg\', this.value)">'
          : '')
      + '</div>';
  }).join('');

  if (!hasElse && branches.length) {
    html += '<div class="alert danger" style="margin:0 0 10px">This condition has no '
      + '"everyone else" branch. Anybody whose outcome you did not draw would have nowhere to go, '
      + 'so the journey will not start until you add one.</div>';
  }
  return html;
}

function exitPanel(node) {
  const options = ['<option value="">-- leave the sheet as it is --</option>'].concat(
    (journeyData.buckets || []).map(b => '<option value="' + b.value + '"'
      + (b.value === node.exitBucket ? ' selected' : '') + '>' + esc(b.label) + '</option>')).join('');
  return field('File them under', '<select class="input" id="jn-exitBucket">' + options + '</select>',
    'their sheet only ever moves up, never down');
}

function connectionsPanel(node) {
  const out = outgoingOf(node.id);
  if (node.type === 'SPLIT' || node.type === 'CONDITION' || !out.length) return '';
  return '<div style="margin-top:10px">'
    + out.map(e => {
        const to = nodeById(e.to);
        return '<div class="jrn-branch"><span class="grow truncate">&rarr; '
          + esc(to ? to.name : 'nowhere') + (e.loopBack ? ' (loops back)' : '') + '</span>'
          + '<button class="btn btn-sm btn-danger" onclick="deleteEdge(' + e.id + ')">&times;</button></div>';
      }).join('')
    + '</div>';
}

/* ---------- saving ---------- */

async function saveNode() {
  const node = nodeById(selectedNodeId);
  if (!node) return;
  const params = { nodeId: node.id };
  const pick = (id, key) => { const el = $(id); if (el) params[key] = el.value; };

  pick('jn-name', 'name');
  if (node.type === 'SOURCE') pick('jn-list', 'sourceListId');
  if (node.type === 'EMAIL' || node.type === 'WAIT') pick('jn-delay', 'delayMinutes');
  if (node.type === 'EMAIL') {
    pick('jn-subject', 'subject'); pick('jn-preheader', 'preheader');
    pick('jn-fromName', 'fromName'); pick('jn-replyTo', 'replyTo'); pick('jn-html', 'htmlBody');
    params.trackOpens = $('jn-trackOpens').checked;
    params.trackClicks = $('jn-trackClicks').checked;
  }
  if (node.type === 'CONDITION') pick('jn-evaluateAfter', 'evaluateAfterMinutes');
  if (node.type === 'EXIT') pick('jn-exitBucket', 'exitBucket');

  try {
    await post('/api/journeys/' + journeyId + '/nodes', params);
    await refreshJourney();
    toast('Step saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function saveEdgeField(edgeId, key, value) {
  const params = { edgeId };
  params[key] = value;
  try {
    await post('/api/journeys/' + journeyId + '/edges', params);
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteEdge(edgeId) {
  if (!confirm('Remove this connection?')) return;
  try {
    await post('/api/journeys/' + journeyId + '/edges/' + edgeId + '/delete', {});
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteNode() {
  const node = nodeById(selectedNodeId);
  if (!node) return;
  if (!confirm('Remove "' + node.name + '"? Its connections go with it.')) return;
  try {
    await post('/api/journeys/' + journeyId + '/nodes/' + node.id + '/delete', {});
    selectedNodeId = null;
    await refreshJourney();
    toast('Step removed', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/** Copies a whole subtree onto another base sheet, then leaves it editable. */
async function openCopyBranch(rootNodeId) {
  const sources = (journeyData.nodes || []).filter(n => n.type === 'SOURCE' || n.type === 'WAIT');
  if (!sources.length) { toast('Add a second base sheet to copy this onto', 'warn'); return; }
  const names = sources.map((n, i) => (i + 1) + '. ' + n.name).join('\n');
  const answer = prompt('Copy this branch, and everything under it, onto which step?\n\n' + names
    + '\n\nType the number:');
  if (!answer) return;
  const target = sources[Number(answer) - 1];
  if (!target) { toast('No step with that number', 'warn'); return; }
  try {
    const res = await post('/api/journeys/' + journeyId + '/copy-branch',
      { rootNodeId, attachToNodeId: target.id, move: false });
    await refreshJourney();
    toast(res.copiedNodes + ' steps copied under "' + target.name + '". Edit them freely.', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   The email editor
   -------------------------------------------------------------------------
   A journey email is an ordinary campaign email, so editing one gets the same
   tools the single composer has: raw HTML, merge-tag chips, a live preview and
   a test send with real values. The side panel is 360px wide and a preview does
   not fit in it, which is why this is a modal rather than more inline fields.
   ========================================================================= */

let neNodeId = null;

function openNodeEmailEditor(nodeId) {
  const node = nodeById(nodeId);
  if (!node) return;
  neNodeId = nodeId;

  $('neTitle').textContent = node.name;
  $('neSubject').value = node.subject || '';
  $('nePreheader').value = node.preheader || '';
  $('neDelay').value = node.delayMinutes || 0;
  $('neFromName').value = node.fromName || '';
  $('neReplyTo').value = node.replyTo || '';
  $('neHtml').value = node.htmlBody || '';
  $('neTrackOpens').checked = !!node.trackOpens;
  $('neTrackClicks').checked = !!node.trackClicks;
  $('neTestTo').value = ($('jn-testTo') && $('jn-testTo').value) || '';

  neTab('code');
  neRefresh();
  openModal('modalNodeEmail');
}

function neTab(tab) {
  document.querySelectorAll('#modalNodeEmail .tab[data-netab]')
    .forEach(t => t.classList.toggle('active', t.dataset.netab === tab));
  $('ne-code').style.display = tab === 'code' ? '' : 'none';
  $('ne-preview').style.display = tab === 'preview' ? '' : 'none';
  if (tab === 'preview') neRenderPreview();
}

function neWidth(mode) { $('nePreviewFrame').classList.toggle('mobile', mode === 'mobile'); }

/** Inserts a tag where the caret is, rather than at the end of the body. */
function neInsert(tag) {
  const box = $('neHtml');
  const at = box.selectionStart === undefined ? box.value.length : box.selectionStart;
  const end = box.selectionEnd === undefined ? at : box.selectionEnd;
  box.value = box.value.slice(0, at) + tag + box.value.slice(end);
  box.focus();
  box.selectionStart = box.selectionEnd = at + tag.length;
  neRefresh();
}

function neRefresh() {
  neRefreshTestFields();
  neRenderNote();
  if ($('ne-preview').style.display !== 'none') neRenderPreview();
}

/**
 * Rendered client side with the typed test values, so the preview and the test
 * message are the same message. The tracked-link and pixel injection happen at
 * send time on the server and are described rather than faked here.
 */
function neRenderPreview() {
  const values = neTestValues();
  const body = ($('neHtml').value || '')
    .replace(/\{\{TRACK:(.*?)\}\}/g, '$1')
    .replace(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g, (whole, key) => {
      const k = key.toUpperCase();
      if (k === 'UNSUBSCRIBE_LINK') return '#';
      if (Object.prototype.hasOwnProperty.call(values, k) && values[k] !== '') return values[k];
      // An unknown tag stays visible on purpose: that is a real mistake worth
      // seeing before the journey runs, and the server renders it as empty.
      return whole;
    });

  const subject = applyMerge($('neSubject').value, values) || '(no subject)';
  const pre = applyMerge($('nePreheader').value, values);
  const banner = '<div style="font:13px/1.45 system-ui;padding:11px 14px;background:#f3f4f6;'
    + 'border-bottom:1px solid #e5e7eb;color:#111"><b>' + esc(subject) + '</b>'
    + (pre ? '<br><span style="color:#6b7280">' + esc(pre) + '</span>' : '') + '</div>';

  $('nePreviewFrame').srcdoc = banner + body;
}

function neRenderNote() {
  const html = $('neHtml').value || '';
  const notes = [];
  notes.push(html.indexOf('{{UNSUBSCRIBE_LINK}}') >= 0
    ? 'Your own unsubscribe link is used.'
    : 'An unsubscribe footer is added automatically at send time.');
  notes.push($('neTrackClicks').checked
    ? 'Every http link is rewritten for click tracking.'
    : 'Click tracking is off, so links go out untouched.');
  notes.push($('neTrackOpens').checked
    ? 'A 1x1 open pixel is added before &lt;/body&gt;.'
    : 'No open pixel, so opens cannot be measured for this step.');
  const bytes = new Blob([html]).size;
  if (bytes > 102 * 1024) {
    notes.push('<b style="color:var(--warning)">Over 102KB. Gmail clips the message and hides '
      + 'everything after the cut, including the unsubscribe link.</b>');
  }
  $('neRenderNote').innerHTML = notes.map(n => '&middot; ' + n).join('<br>');
}

function neTestValues() {
  const out = {};
  document.querySelectorAll('#neTestFields .merge-input').forEach(i => {
    if (i.value !== '') out[i.dataset.tag] = i.value;
  });
  return out;
}

function neRefreshTestFields() {
  const tags = mergeTagsIn([$('neSubject').value, $('nePreheader').value, $('neHtml').value]);
  const box = $('neTestFields');
  if (!tags.length) {
    box.innerHTML = '';
    $('neTestNote').textContent = 'No merge tags, so the test is exactly what a recipient sees.';
    return;
  }
  const typed = neTestValues();
  box.innerHTML = tags.map(tag =>
    '<label class="field merge-field"><span>{{' + esc(tag) + '}}</span>'
    + '<input class="input merge-input" data-tag="' + attr(tag) + '" value="'
    + attr(typed[tag] || '') + '" placeholder="' + esc(tag.toLowerCase().replace(/_/g, ' ')) + '"'
    + ' oninput="debounce(neRenderPreview,300)()"></label>').join('');
  $('neTestNote').textContent = tags.length + ' merge tag(s). Anything left blank is filled with '
    + 'sample data, because a blank in a test looks exactly like a tag that failed to substitute.';
}

async function neSampleValues() {
  try {
    const res = await post('/api/campaigns/merge-tags', {
      subject: $('neSubject').value, preheader: $('nePreheader').value,
      htmlBody: $('neHtml').value, testAddress: $('neTestTo').value.trim()
    });
    const samples = {};
    (res.tags || []).forEach(r => { samples[r.tag] = r.sample; });
    document.querySelectorAll('#neTestFields .merge-input').forEach(i => {
      if (samples[i.dataset.tag] !== undefined) i.value = samples[i.dataset.tag];
    });
    neRenderPreview();
  } catch (e) { toast(e.message, 'err'); }
}

/** Reuses the marketing template library the single composer pulls from. */
async function neInsertTemplate() {
  let list;
  try { list = await api('/api/templates?type=MARKETING'); }
  catch (e) { toast('Could not load templates', 'err'); return; }
  if (!list.length) { toast('No marketing templates saved yet', 'warn'); return; }

  const pick = prompt('Insert which template?\n\n'
    + list.map((t, i) => (i + 1) + '. ' + t.name).join('\n') + '\n\nType the number:');
  if (!pick) return;
  const t = list[Number(pick) - 1];
  if (!t) { toast('No template with that number', 'warn'); return; }
  if ($('neHtml').value.trim()
      && !confirm('Replace the current body with "' + t.name + '"?')) return;

  if (!$('neSubject').value.trim()) $('neSubject').value = t.subject || '';
  $('neHtml').value = t.htmlBody || '';
  neRefresh();
  toast('Template inserted', 'ok');
}

async function neSave() {
  if (!neNodeId) return;
  try {
    await post('/api/journeys/' + journeyId + '/nodes', {
      nodeId: neNodeId,
      subject: $('neSubject').value,
      preheader: $('nePreheader').value,
      delayMinutes: $('neDelay').value,
      fromName: $('neFromName').value,
      replyTo: $('neReplyTo').value,
      htmlBody: $('neHtml').value,
      trackOpens: $('neTrackOpens').checked,
      trackClicks: $('neTrackClicks').checked
    });
    closeModal('modalNodeEmail');
    await refreshJourney();
    toast('Email saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function neTestSend() {
  const to = $('neTestTo').value.trim();
  if (!to) { toast('Enter an address to test with', 'warn'); return; }

  // Read the values BEFORE saving. neSave closes the modal and reloads the
  // journey, which tears down these inputs, so collecting them afterwards would
  // silently send a test with no merge values at all.
  const nodeId = neNodeId;
  const params = { to };
  Object.entries(neTestValues()).forEach(([tag, value]) => { params['merge.' + tag] = value; });

  // Saved first so the test exercises what is actually stored on the node,
  // rather than what happens to be on screen.
  await neSave();

  try {
    toast((await post('/api/journeys/' + journeyId + '/nodes/' + nodeId + '/test-send',
      params)).message, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- test send for one node ---------- */

function refreshNodeTestFields() {
  const box = $('jn-testFields');
  if (!box) return;
  const html = $('jn-html') ? $('jn-html').value : '';
  const subject = $('jn-subject') ? $('jn-subject').value : '';
  const preheader = $('jn-preheader') ? $('jn-preheader').value : '';
  const tags = mergeTagsIn([subject, preheader, html]);

  if (!tags.length) {
    box.innerHTML = '';
    $('jn-testNote').textContent = 'This message has no merge tags, so the test is exactly what a '
      + 'recipient sees.';
    return;
  }
  const existing = {};
  box.querySelectorAll('.merge-input').forEach(i => { existing[i.dataset.tag] = i.value; });

  box.innerHTML = tags.map(tag =>
    '<label class="field merge-field"><span>{{' + esc(tag) + '}}</span>'
    + '<input class="input merge-input" data-tag="' + attr(tag) + '" value="'
      + attr(existing[tag] || '') + '" placeholder="' + esc(tag.toLowerCase().replace(/_/g, ' ')) + '">'
    + '</label>').join('');
  $('jn-testNote').textContent = tags.length + ' merge tag(s). Anything left blank is filled with '
    + 'sample data, because a blank in a test looks exactly like a tag that failed to substitute.';
}

async function testSendNode() {
  const node = nodeById(selectedNodeId);
  if (!node) return;
  const to = $('jn-testTo').value.trim();
  if (!to) { toast('Enter an address to test with', 'warn'); return; }

  await saveNode();
  const params = { to };
  document.querySelectorAll('#jn-testFields .merge-input').forEach(i => {
    if (i.value !== '') params['merge.' + i.dataset.tag] = i.value;
  });
  try {
    const res = await post('/api/journeys/' + journeyId + '/nodes/' + node.id + '/test-send', params);
    toast(res.message, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   Lifecycle
   ========================================================================= */

async function validateJourney() {
  try {
    const res = await post('/api/journeys/' + journeyId + '/validate', {});
    renderFindings(res.findings, res.blocked);
    toast(res.blocked ? 'This journey cannot start yet' : 'No blocking problems', res.blocked ? 'warn' : 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

function renderFindings(findings, blocked) {
  const box = $('jrnFindings');
  if (!findings || !findings.length) {
    box.innerHTML = '<div class="alert info">Nothing to flag. This journey is ready to run.</div>';
    return;
  }
  box.innerHTML = findings.map(f => {
    const kind = f.severity === 'BLOCK' ? 'danger' : f.severity === 'WARN' ? 'warn' : 'info';
    const node = f.nodeId ? nodeById(f.nodeId) : null;
    return '<div class="alert ' + kind + '" style="margin-bottom:7px">'
      + (node ? '<b>' + esc(node.name) + '</b> &middot; ' : '') + esc(f.message) + '</div>';
  }).join('');
}

async function activateJourney() {
  if (!confirm('Start this journey?\n\nPeople will begin receiving mail on the schedule you have '
    + 'drawn, without anyone pressing send again.')) return;
  try {
    const res = await post('/api/journeys/' + journeyId + '/activate', {});
    renderFindings(res.findings, res.blocked);
    toast(res.message, res.blocked ? 'err' : 'ok');
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

async function pauseJourney() {
  try {
    toast((await post('/api/journeys/' + journeyId + '/pause', {})).message, 'ok');
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

async function resumeJourney() {
  try {
    toast((await post('/api/journeys/' + journeyId + '/resume', {})).message, 'ok');
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

async function abortJourney() {
  if (!confirm('Stop this journey for good?\n\nEveryone still moving is let out, keeping the sheet '
    + 'they are on. Mail already sent is unaffected.')) return;
  try {
    toast((await post('/api/journeys/' + journeyId + '/abort', {})).message, 'ok');
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

async function runJourneyNow() {
  try {
    const res = await post('/api/journeys/' + journeyId + '/run-now', {});
    toast(res.skipped ? 'Nothing to do: ' + res.skipped
      : 'Admitted ' + num(res.admitted || 0) + ', advanced ' + num(res.advanced || 0) + '.', 'ok');
    await refreshJourney();
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- settings ---------- */

function openJourneySettings() {
  const j = journeyData.journey;
  $('jsName').value = j.name;
  $('jsStart').value = j.startAt ? String(j.startAt).slice(0, 16) : '';
  $('jsDeadline').value = j.deadlineAt ? String(j.deadlineAt).slice(0, 16) : '';
  $('jsMaxEmails').value = j.maxEmailsPerParticipant;
  $('jsMaxLoops').value = j.maxLoopIterations;
  $('jsMinGap').value = j.minGapHours;
  $('jsQuietStart').value = j.quietStartHour;
  $('jsQuietEnd').value = j.quietEndHour;
  $('jsFromName').value = j.fromName;
  $('jsReplyTo').value = j.replyTo;
  openModal('modalJourneySettings');
}

async function saveJourneySettings() {
  const params = {
    name: $('jsName').value,
    startAt: $('jsStart').value ? $('jsStart').value + ':00' : '',
    deadlineAt: $('jsDeadline').value ? $('jsDeadline').value + ':00' : '',
    maxEmailsPerParticipant: $('jsMaxEmails').value,
    maxLoopIterations: $('jsMaxLoops').value,
    minGapHours: $('jsMinGap').value,
    quietStartHour: $('jsQuietStart').value,
    quietEndHour: $('jsQuietEnd').value,
    fromName: $('jsFromName').value,
    replyTo: $('jsReplyTo').value
  };
  try {
    await post('/api/journeys/' + journeyId + '/settings', params);
    closeModal('modalJourneySettings');
    await refreshJourney();
    toast('Settings saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteJourney() {
  if (!confirm('Delete this journey?\n\nThe flowchart and its history go. Mail already sent, and the '
    + 'campaigns behind it, are kept.')) return;
  try {
    await post('/api/journeys/' + journeyId + '/delete', {});
    closeModal('modalJourneySettings');
    journeyId = null;
    go('journeys');
    toast('Journey deleted', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* =========================================================================
   Tabs
   ========================================================================= */

function switchJourneyTab(tab) {
  document.querySelectorAll('#view-journey .tab[data-jtab]')
    .forEach(t => t.classList.toggle('active', t.dataset.jtab === tab));
  $('jtab-sheets').style.display = tab === 'sheets' ? '' : 'none';
  $('jtab-log').style.display = tab === 'log' ? '' : 'none';
  $('jtab-ab').style.display = tab === 'ab' ? '' : 'none';
  if (tab === 'log') loadJourneyEvents();
  if (tab === 'ab') loadJourneyVariants();
}

function renderSheets() {
  const sheets = (journeyData.stats && journeyData.stats.sheets) || [];
  $('jrnSheets').innerHTML = sheets.map(s =>
    '<div class="kpi ' + (s.terminal ? 'warn' : s.goal ? 'good' : '') + '" style="cursor:pointer"'
    + ' role="button" tabindex="0" onclick="openSheet(\'' + attr(s.bucket) + '\')"'
    + ' title="' + attr(s.description) + '">'
    + '<div class="label">' + esc(s.label) + '</div>'
    + '<div class="value">' + num(s.count) + '</div>'
    + '<div class="foot">' + s.share + '% of everyone</div></div>').join('')
    || '<div class="empty">Nobody has entered this journey yet.</div>';
}

async function loadJourneyEvents() {
  let d;
  try { d = await api('/api/journeys/' + journeyId + '/events?page=' + journeyEventPage); }
  catch (e) { toast('Could not load the log', 'err'); return; }

  $('jrnEventBody').innerHTML = d.rows.length ? d.rows.map(r => {
    const node = r.nodeId ? nodeById(r.nodeId) : null;
    return '<tr><td class="nowrap">' + esc(r.at) + '</td>'
      + '<td>' + statusPill(r.type) + '</td>'
      + '<td class="truncate" style="max-width:200px">' + esc(r.email) + '</td>'
      + '<td>' + (node ? '<b>' + esc(node.name) + '</b> &middot; ' : '') + esc(r.detail) + '</td></tr>';
  }).join('') : emptyRow(4, 'Nothing has happened yet.');
  $('jrnEventCount').textContent = pagerText(d);
}

function pageJourneyEvents(delta) {
  journeyEventPage = Math.max(0, journeyEventPage + delta);
  loadJourneyEvents();
}

function fillAbPicker() {
  const splits = (journeyData.nodes || []).filter(n => n.type === 'SPLIT');
  $('jrnAbPicker').innerHTML = splits.length
    ? splits.map(n => '<option value="' + n.id + '">' + esc(n.name) + '</option>').join('')
    : '<option value="">No A/B steps in this journey</option>';
}

async function loadJourneyVariants() {
  const splitId = $('jrnAbPicker').value;
  const box = $('jrnAbBody');
  if (!splitId) {
    box.innerHTML = '<div class="empty">Add an A/B split to compare two versions of a message.</div>';
    return;
  }
  let d;
  try { d = await api('/api/journeys/' + journeyId + '/variants/' + splitId); }
  catch (e) { toast('Could not load the comparison', 'err'); return; }

  const pct = v => (v === null || v === undefined) ? '-' : v + '%';
  box.innerHTML =
    '<div class="alert ' + (d.callable ? 'info' : 'warn') + '" style="margin:0 0 12px">'
      + esc(d.verdict)
      + (d.detectableDifferencePoints !== null && d.detectableDifferencePoints !== undefined
          ? ' At this size only a gap of about ' + d.detectableDifferencePoints
            + ' percentage points or more could be told apart from chance.'
          : '')
    + '</div>'
    + '<div class="table-wrap"><table class="data"><thead><tr>'
      + '<th>Version</th><th class="num">Assigned</th><th class="num">Delivered</th>'
      + '<th class="num">Opened</th><th class="num">Open rate</th><th class="num">Clicked</th>'
      + '<th class="num">Click rate</th><th class="num">CTOR</th><th class="num">Unsub</th>'
    + '</tr></thead><tbody>'
    + (d.arms.length ? d.arms.map(a =>
        '<tr><td><b>' + esc(a.arm) + '</b></td>'
        + '<td class="num">' + num(a.assigned) + '</td>'
        + '<td class="num">' + num(a.delivered) + '</td>'
        + '<td class="num">' + num(a.opened) + '</td>'
        + '<td class="num">' + pct(a.openRate) + '</td>'
        + '<td class="num">' + num(a.clicked) + '</td>'
        + '<td class="num">' + pct(a.clickRate) + '</td>'
        + '<td class="num">' + pct(a.clickToOpenRate) + '</td>'
        + '<td class="num">' + num(a.unsubscribed) + '</td></tr>').join('')
       : emptyRow(9, 'Nobody has been assigned a version yet.'))
    + '</tbody></table></div>';
}

/* ---------- sheet drill-down ---------- */

function openSheet(bucket) {
  sheetBucket = bucket;
  sheetPage = 0;
  const sheet = ((journeyData.stats && journeyData.stats.sheets) || [])
    .find(s => s.bucket === bucket);
  $('sheetTitle').textContent = sheet ? sheet.label : bucket;
  $('sheetDesc').textContent = sheet ? sheet.description : '';
  loadSheetPeople();
  openModal('modalSheet');
}

async function loadSheetPeople() {
  let d;
  try { d = await api('/api/journeys/' + journeyId + '/sheets/' + sheetBucket + '/people?page=' + sheetPage); }
  catch (e) { toast('Could not load that sheet', 'err'); return; }

  $('sheetBody').innerHTML = d.rows.length ? d.rows.map(p =>
    '<tr><td>' + esc(p.name) + '</td><td class="truncate" style="max-width:220px">' + esc(p.email) + '</td>'
    + '<td>' + statusPill(p.state) + '</td>'
    + '<td class="num">' + num(p.emailsSent) + '</td>'
    + '<td class="num">' + num(p.loopCount) + '</td>'
    + '<td>' + esc(p.variantArm) + '</td>'
    + '<td class="truncate" style="max-width:180px">' + esc(p.exitReason) + '</td></tr>').join('')
    : emptyRow(7, 'Nobody is on this sheet.');
  $('sheetCount').textContent = pagerText(d);
}

function pageSheet(delta) {
  sheetPage = Math.max(0, sheetPage + delta);
  loadSheetPeople();
}

function exportSheet() {
  location.href = '/api/journeys/' + journeyId + '/sheets/' + sheetBucket + '/export';
}

async function saveSheetAsList() {
  const name = prompt('Name the new list:',
    (journeyData.journey.name + ' - ' + $('sheetTitle').textContent));
  if (!name) return;
  try {
    const res = await post('/api/journeys/' + journeyId + '/sheets/' + sheetBucket + '/save-as-list',
      { name });
    toast(res.message, 'ok');
    await loadLists();
    fillListPickers();
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- wire into the router ---------- */

if (typeof LOADERS !== 'undefined') {
  LOADERS.journeys = loadJourneys;
  LOADERS.journey = () => { if (journeyId) refreshJourney(); };
}
