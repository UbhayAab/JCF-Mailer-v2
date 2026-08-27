/* ==========================================================================
   Charts

   Hand-rolled SVG. The box must render with zero external requests, so a chart
   library is not an option, and these five shapes cover every screen we have.

   Every function takes a container element and replaces its contents. All of
   them draw a labelled empty state when there is no data, because the failure
   this replaces was a chart frame that silently painted nothing and read as a
   broken page rather than as "no sends yet".
   ========================================================================== */

const CHART_COLORS = ['#2f6fed', '#19a7a0', '#d99e0b', '#8b5cf6', '#e0483c', '#1f9d55'];

function chartEmpty(el, message) {
  el.innerHTML = '<div class="chart-empty">' + escapeHtml(message || 'No data yet') + '</div>';
}

/* charts.js loads before console.js, so it cannot borrow esc() from there. */
function escapeHtml(s) {
  return String(s === null || s === undefined ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function svgEl(tag, attrs) {
  const n = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const k in attrs) n.setAttribute(k, attrs[k]);
  return n;
}

function chartNum(n) {
  const v = Number(n || 0);
  if (v >= 1000000) return (v / 1000000).toFixed(v >= 10000000 ? 0 : 1) + 'M';
  if (v >= 1000) return (v / 1000).toFixed(v >= 10000 ? 0 : 1) + 'k';
  return String(Math.round(v));
}

/* --------------------------------------------------------------------------
   Donut. segments: [{label, value, color}]
   -------------------------------------------------------------------------- */
function drawDonut(el, segments, opts) {
  opts = opts || {};
  const data = (segments || []).filter(s => Number(s.value) > 0);
  const total = data.reduce((a, s) => a + Number(s.value), 0);
  if (!total) { chartEmpty(el, opts.empty); return; }

  const size = opts.size || 190;
  const thickness = opts.thickness || 26;
  const r = (size - thickness) / 2;
  const cx = size / 2, cy = size / 2;
  const circumference = 2 * Math.PI * r;

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + size + ' ' + size, width: size, height: size, class: 'chart-donut'
  });

  // Track first, so a single tiny segment still reads as a proportion of a whole.
  svg.appendChild(svgEl('circle', {
    cx: cx, cy: cy, r: r, fill: 'none',
    stroke: 'rgba(255,255,255,0.06)', 'stroke-width': thickness
  }));

  let offset = 0;
  data.forEach((s, i) => {
    const frac = Number(s.value) / total;
    const arc = svgEl('circle', {
      cx: cx, cy: cy, r: r, fill: 'none',
      stroke: s.color || CHART_COLORS[i % CHART_COLORS.length],
      'stroke-width': thickness,
      'stroke-dasharray': (frac * circumference) + ' ' + circumference,
      'stroke-dashoffset': -offset,
      transform: 'rotate(-90 ' + cx + ' ' + cy + ')'
    });
    arc.appendChild(svgEl('title', {})).textContent =
      s.label + ': ' + Number(s.value).toLocaleString('en-IN')
      + ' (' + (frac * 100).toFixed(1) + '%)';
    svg.appendChild(arc);
    offset += frac * circumference;
  });

  const big = svgEl('text', {
    x: cx, y: cy - 2, 'text-anchor': 'middle', class: 'chart-donut-value'
  });
  big.textContent = opts.centerValue !== undefined ? opts.centerValue : chartNum(total);
  svg.appendChild(big);
  if (opts.centerLabel) {
    const small = svgEl('text', {
      x: cx, y: cy + 16, 'text-anchor': 'middle', class: 'chart-donut-label'
    });
    small.textContent = opts.centerLabel;
    svg.appendChild(small);
  }

  const legend = document.createElement('div');
  legend.className = 'chart-legend';
  legend.innerHTML = data.map((s, i) =>
    '<span class="chart-legend-item"><i style="background:'
    + (s.color || CHART_COLORS[i % CHART_COLORS.length]) + '"></i>'
    + escapeHtml(s.label) + ' <b>' + Number(s.value).toLocaleString('en-IN') + '</b></span>'
  ).join('');

  el.innerHTML = '';
  const wrap = document.createElement('div');
  wrap.className = 'chart-donut-wrap';
  wrap.appendChild(svg);
  wrap.appendChild(legend);
  el.appendChild(wrap);
}

/* --------------------------------------------------------------------------
   Area / line chart.
   series: [{label, color, points:[{x, y}]}]   x is a label, y a number.
   -------------------------------------------------------------------------- */
function drawArea(el, series, opts) {
  opts = opts || {};
  const live = (series || []).filter(s => s.points && s.points.length);
  if (!live.length) { chartEmpty(el, opts.empty); return; }

  // One SVG unit per CSS pixel, for the same reason as the analytics chart: a
  // fixed wide viewBox on a narrow screen shrinks the axis text along with
  // everything else, and no stylesheet can reach inside to undo it.
  const W = Math.max(320, Math.round(el.clientWidth || 900));
  const H = opts.height || 260;
  const padL = 46, padR = 14, padT = 14, padB = 30;
  const plotW = W - padL - padR, plotH = H - padT - padB;

  const n = Math.max(...live.map(s => s.points.length));
  let max = Math.max(...live.flatMap(s => s.points.map(p => Number(p.y) || 0)));
  if (max <= 0) max = 1;
  // Round the ceiling up to something a human would pick, so gridline labels are
  // readable numbers rather than 0, 3271, 6542.
  const mag = Math.pow(10, Math.floor(Math.log10(max)));
  max = Math.ceil(max / (mag / 2)) * (mag / 2);

  const X = i => padL + (n === 1 ? plotW / 2 : (i / (n - 1)) * plotW);
  const Y = v => padT + plotH - (Number(v || 0) / max) * plotH;

  const svg = svgEl('svg', {
    viewBox: '0 0 ' + W + ' ' + H,
    class: 'chart-area', width: '100%', height: H
  });

  // Gridlines and y labels.
  for (let g = 0; g <= 4; g++) {
    const v = (max / 4) * g, y = Y(v);
    svg.appendChild(svgEl('line', {
      x1: padL, y1: y, x2: W - padR, y2: y,
      stroke: 'rgba(255,255,255,0.06)', 'stroke-width': 1
    }));
    const t = svgEl('text', { x: padL - 8, y: y + 4, 'text-anchor': 'end', class: 'chart-axis' });
    t.textContent = chartNum(v);
    svg.appendChild(t);
  }

  live.forEach((s, si) => {
    const color = s.color || CHART_COLORS[si % CHART_COLORS.length];
    const pts = s.points.map((p, i) => X(i) + ',' + Y(p.y));

    const areaId = 'grad' + si + '-' + Math.abs(hashString(s.label || String(si)));
    const defs = svgEl('defs', {});
    const lg = svgEl('linearGradient', { id: areaId, x1: '0', y1: '0', x2: '0', y2: '1' });
    lg.appendChild(svgEl('stop', { offset: '0%', 'stop-color': color, 'stop-opacity': '0.28' }));
    lg.appendChild(svgEl('stop', { offset: '100%', 'stop-color': color, 'stop-opacity': '0' }));
    defs.appendChild(lg);
    svg.appendChild(defs);

    svg.appendChild(svgEl('polygon', {
      points: X(0) + ',' + (padT + plotH) + ' ' + pts.join(' ') + ' '
            + X(s.points.length - 1) + ',' + (padT + plotH),
      fill: 'url(#' + areaId + ')'
    }));
    svg.appendChild(svgEl('polyline', {
      points: pts.join(' '), fill: 'none', stroke: color,
      'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'
    }));

    // Dots only when the series is sparse; at 30 points they become noise.
    if (s.points.length <= 14) {
      s.points.forEach((p, i) => {
        const c = svgEl('circle', { cx: X(i), cy: Y(p.y), r: 3, fill: color });
        c.appendChild(svgEl('title', {})).textContent =
          (s.label ? s.label + ' - ' : '') + p.x + ': ' + Number(p.y || 0).toLocaleString('en-IN');
        svg.appendChild(c);
      });
    }
  });

  // X labels, thinned so they never collide.
  const first = live[0].points;
  const step = Math.max(1, Math.ceil(first.length / 8));
  first.forEach((p, i) => {
    if (i % step && i !== first.length - 1) return;
    const t = svgEl('text', {
      x: X(i), y: H - 10, 'text-anchor': 'middle', class: 'chart-axis'
    });
    t.textContent = p.x;
    svg.appendChild(t);
  });

  el.innerHTML = '';
  el.appendChild(svg);
  if (live.length > 1 || opts.legend) {
    const legend = document.createElement('div');
    legend.className = 'chart-legend';
    legend.innerHTML = live.map((s, i) =>
      '<span class="chart-legend-item"><i style="background:'
      + (s.color || CHART_COLORS[i % CHART_COLORS.length]) + '"></i>'
      + escapeHtml(s.label || ('Series ' + (i + 1))) + '</span>').join('');
    el.appendChild(legend);
  }
}

function hashString(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) { h = ((h << 5) - h + s.charCodeAt(i)) | 0; }
  return h;
}

/* --------------------------------------------------------------------------
   Horizontal bars. items: [{label, value, max, note, color}]
   -------------------------------------------------------------------------- */
function drawBars(el, items, opts) {
  opts = opts || {};
  const data = (items || []).filter(Boolean);
  if (!data.length) { chartEmpty(el, opts.empty); return; }

  const ceiling = opts.max || Math.max(...data.map(d => Number(d.max || d.value) || 0)) || 1;
  el.innerHTML = '<div class="chart-bars">' + data.map((d, i) => {
    const v = Number(d.value) || 0;
    const pct = Math.max(0, Math.min(100, (v / ceiling) * 100));
    const color = d.color || CHART_COLORS[i % CHART_COLORS.length];
    return '<div class="chart-bar-row">'
      + '<span class="chart-bar-label" title="' + escapeHtml(d.label) + '">'
      + escapeHtml(d.label) + '</span>'
      + '<span class="chart-bar-track"><i style="width:' + pct.toFixed(1)
      + '%;background:' + color + '"></i></span>'
      + '<span class="chart-bar-value">' + escapeHtml(d.note || chartNum(v)) + '</span>'
      + '</div>';
  }).join('') + '</div>';
}

/* --------------------------------------------------------------------------
   Half gauge, the shape Zoho uses for a compliance score. value is 0..100.
   -------------------------------------------------------------------------- */
function drawGauge(el, value, opts) {
  opts = opts || {};
  const v = Math.max(0, Math.min(100, Number(value) || 0));
  const W = 260, H = 150, cx = W / 2, cy = 128, r = 96, thickness = 18;

  const polar = (deg) => {
    const rad = (deg - 180) * Math.PI / 180;
    return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
  };
  const arc = (from, to, color) => {
    const [x1, y1] = polar(from), [x2, y2] = polar(to);
    return svgEl('path', {
      d: 'M ' + x1 + ' ' + y1 + ' A ' + r + ' ' + r + ' 0 0 1 ' + x2 + ' ' + y2,
      fill: 'none', stroke: color, 'stroke-width': thickness, 'stroke-linecap': 'butt'
    });
  };

  const svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, class: 'chart-gauge' });
  const bands = opts.bands || [
    [0, 25, '#e0483c'], [25, 50, '#d99e0b'], [50, 75, '#e3b341'], [75, 100, '#1f9d55']
  ];
  bands.forEach(b => svg.appendChild(arc((b[0] / 100) * 180, (b[1] / 100) * 180, b[2])));

  // Needle.
  const [nx, ny] = polar((v / 100) * 180);
  svg.appendChild(svgEl('line', {
    x1: cx, y1: cy, x2: nx, y2: ny,
    stroke: 'var(--text)', 'stroke-width': 3, 'stroke-linecap': 'round'
  }));
  svg.appendChild(svgEl('circle', { cx: cx, cy: cy, r: 5, fill: 'var(--text)' }));

  const label = svgEl('text', { x: cx, y: cy - 22, 'text-anchor': 'middle', class: 'chart-gauge-value' });
  label.textContent = opts.format ? opts.format(v) : (Math.round(v) + '%');
  svg.appendChild(label);

  el.innerHTML = '';
  el.appendChild(svg);
}

/* --------------------------------------------------------------------------
   Sparkline for a KPI card. values: [numbers]
   -------------------------------------------------------------------------- */
function drawSpark(el, values, opts) {
  opts = opts || {};
  const v = (values || []).map(Number).filter(x => !isNaN(x));
  if (v.length < 2) { el.innerHTML = ''; return; }

  const W = 120, H = 32, pad = 2;
  const max = Math.max(...v), min = Math.min(...v);
  const span = (max - min) || 1;
  const X = i => pad + (i / (v.length - 1)) * (W - pad * 2);
  const Y = x => pad + (1 - (x - min) / span) * (H - pad * 2);
  const color = opts.color || '#2f6fed';

  const pts = v.map((x, i) => X(i) + ',' + Y(x)).join(' ');
  const svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, class: 'chart-spark',
                             preserveAspectRatio: 'none' });
  svg.appendChild(svgEl('polyline', {
    points: pts, fill: 'none', stroke: color, 'stroke-width': 1.8,
    'stroke-linejoin': 'round', 'stroke-linecap': 'round'
  }));
  el.innerHTML = '';
  el.appendChild(svg);
}
