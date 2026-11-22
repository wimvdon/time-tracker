// Generates every app icon from one vector definition, without image dependencies:
//   assets/icon.svg         – master artwork, for reference
//   assets/icon.ico         – Windows app/installer icon, 16–256 px (PNG-in-ICO)
//   assets/icon-256.png     – window icon on non-Windows platforms
//   assets/tray-idle.png    – tray icon, idle (the same dial, in grey)
//   assets/tray-template.png – tray icon while a timer runs: black dial, tinted at runtime
//   ../android/app/src/main/res/drawable/ic_launcher_*.xml – Android adaptive icon layers
// Every size is rendered straight from the vectors, so small sizes stay crisp.
// Run with: npm run icons

const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

// ---- Minimal PNG encoder ----

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePNG(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // color type: RGBA
  // Raw image data: every scanline prefixed with filter byte 0
  const raw = Buffer.alloc(height * (1 + width * 4));
  for (let y = 0; y < height; y++) {
    rgba.copy(raw, y * (1 + width * 4) + 1, y * width * 4, (y + 1) * width * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

// PNG-in-ICO container (supported since Windows Vista), one entry per size
function encodeICO(images) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(1, 2); // type: icon
  header.writeUInt16LE(images.length, 4);
  let offset = 6 + images.length * 16;
  const entries = images.map(({ size, png }) => {
    const entry = Buffer.alloc(16);
    entry[0] = size >= 256 ? 0 : size; // 0 means 256
    entry[1] = size >= 256 ? 0 : size;
    entry.writeUInt16LE(1, 4); // color planes
    entry.writeUInt16LE(32, 6); // bits per pixel
    entry.writeUInt32LE(png.length, 8);
    entry.writeUInt32LE(offset, 12);
    offset += png.length;
    return entry;
  });
  return Buffer.concat([header, ...entries, ...images.map((i) => i.png)]);
}

// ---- Artwork ----
// Drawn on Android's 108×108 adaptive-icon grid; the launcher shows the middle 72 units and
// keeps everything within a radius of 33 from the centre. Angles run clockwise from 12 o'clock.

const WHITE = '#FAFAFA';
const ANDROID_BACKGROUND = '#FFFFFF';

// A linear gradient over a shape: colour stops as [offset, colour] along the line from → to,
// in grid units. Used as a shape's `color` instead of a plain colour.
const linear = (stops, from, to) => ({ stops, from, to });

const polar = (cx, cy, r, deg) => {
  const a = ((deg - 90) * Math.PI) / 180;
  return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
};

const FACE = { cx: 54, cy: 54 };
const DIAL_RADIUS = 27;
const HAND_END = polar(FACE.cx, FACE.cy, 16.5, 135);

const HAND = { type: 'line', x1: FACE.cx, y1: FACE.cy, x2: HAND_END[0], y2: HAND_END[1], width: 5.5 };
const PIVOT = { type: 'circle', ...FACE, r: 4.2 };
// Elapsed time, a translucent wedge behind the hand
const ELAPSED = { type: 'wedge', ...FACE, r: 20, from: 0, to: 135 };

// A filled red dial: it reads on light and dark surfaces alike, so one icon works everywhere.
// Like the sibling apps' icons, the dial runs a diagonal gradient through neighbouring hues:
// orange → red → rose, top left to bottom right.
const DIAL_GRADIENT = linear(
  [[0, '#FF8A3D'], [0.55, '#F04438'], [1, '#C81E5A']],
  [FACE.cx - 21, FACE.cy - 23],
  [FACE.cx + 21, FACE.cy + 23],
);
const ICON = [
  { type: 'circle', ...FACE, r: DIAL_RADIUS, color: DIAL_GRADIENT },
  { ...ELAPSED, color: WHITE, alpha: 0.35 },
  { ...HAND, color: WHITE },
  { ...PIVOT, color: WHITE },
];
// Android 13+ themed icon: the launcher tints this single-colour layer. Like the sibling apps'
// themed icons it is the solid shape with the detail cut out, kept simple: the dial minus the
// hand, the same hand and pivot as in the coloured icon, as one outline for the even-odd cut-out.
const MONOCHROME = [
  {
    type: 'cutout',
    base: { type: 'circle', ...FACE, r: DIAL_RADIUS },
    holes: [{ type: 'hand', ...FACE, r: PIVOT.r, x2: HAND_END[0], y2: HAND_END[1], tipR: HAND.width / 2 }],
    color: '#FFFFFF',
  },
];

// Windows: the stopwatch alone on a transparent background
const ICON_VIEW = { x: 25, y: 25, size: 58 };

// Wedges become polygons so every renderer only needs straight edges
function wedgePoints({ cx, cy, r, from, to }) {
  const steps = Math.max(8, Math.ceil((to - from) / 4));
  const pts = [[cx, cy]];
  for (let i = 0; i <= steps; i++) pts.push(polar(cx, cy, r, from + ((to - from) * i) / steps));
  return pts;
}

// ---- Signed distance per shape (negative inside), in grid units ----

function segmentDistance(px, py, x1, y1, x2, y2) {
  const dx = x2 - x1;
  const dy = y2 - y1;
  const t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
}

function polygonDistance(pts) {
  return (px, py) => {
    let d = Infinity;
    let inside = false;
    for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
      const [xi, yi] = pts[i];
      const [xj, yj] = pts[j];
      d = Math.min(d, segmentDistance(px, py, xj, yj, xi, yi));
      if (yi > py !== yj > py && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside ? -d : d;
  };
}

function distanceFn(s) {
  switch (s.type) {
    case 'circle':
      return (x, y) => Math.hypot(x - s.cx, y - s.cy) - s.r;
    case 'ring':
      return (x, y) => Math.abs(Math.hypot(x - s.cx, y - s.cy) - s.r) - s.width / 2;
    case 'line':
      return (x, y) => segmentDistance(x, y, s.x1, s.y1, s.x2, s.y2) - s.width / 2;
    case 'rrect': {
      const hw = s.w / 2 - s.r;
      const hh = s.h / 2 - s.r;
      const cx = s.x + s.w / 2;
      const cy = s.y + s.h / 2;
      return (x, y) => {
        const qx = Math.abs(x - cx) - hw;
        const qy = Math.abs(y - cy) - hh;
        return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - s.r;
      };
    }
    case 'poly':
      return polygonDistance(s.points);
    case 'wedge':
      return polygonDistance(wedgePoints(s));
    default:
      throw new Error(`Unknown shape ${s.type}`);
  }
}

// ---- Rasterizer (anti-aliased via signed distance, supersampled) ----

const hexRGB = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));

const isGradient = (color) => typeof color === 'object';

function colorFn(s, gridX, gridY) {
  if (!isGradient(s.color)) {
    const rgb = hexRGB(s.color);
    return () => rgb;
  }
  const { stops, from, to } = s.color;
  const rgbStops = stops.map(([offset, hex]) => [offset, hexRGB(hex)]);
  const [dx, dy] = [to[0] - from[0], to[1] - from[1]];
  const lengthSq = dx * dx + dy * dy;
  return (x, y) => {
    const t = Math.max(0, Math.min(1, ((gridX(x) - from[0]) * dx + (gridY(y) - from[1]) * dy) / lengthSq));
    let i = 1;
    while (i < rgbStops.length - 1 && t > rgbStops[i][0]) i++;
    const [o0, c0] = rgbStops[i - 1];
    const [o1, c1] = rgbStops[i];
    const u = o1 > o0 ? Math.max(0, Math.min(1, (t - o0) / (o1 - o0))) : 0;
    return c0.map((v, k) => v + (c1[k] - v) * u);
  };
}

class Canvas {
  constructor(size) {
    this.size = size;
    this.data = Buffer.alloc(size * size * 4);
  }

  // Composites colorAt(x, y) → [r,g,b] over each pixel with alpha from coverage(x, y) → 0..1
  paint(coverage, colorAt) {
    const { size, data } = this;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const a = coverage(x + 0.5, y + 0.5);
        if (a <= 0) continue;
        const [r, g, b] = colorAt(x + 0.5, y + 0.5);
        const i = (y * size + x) * 4;
        const srcA = Math.min(1, a);
        const dstA = data[i + 3] / 255;
        const outA = srcA + dstA * (1 - srcA);
        if (outA === 0) continue;
        data[i] = Math.round((r * srcA + data[i] * dstA * (1 - srcA)) / outA);
        data[i + 1] = Math.round((g * srcA + data[i + 1] * dstA * (1 - srcA)) / outA);
        data[i + 2] = Math.round((b * srcA + data[i + 2] * dstA * (1 - srcA)) / outA);
        data[i + 3] = Math.round(outA * 255);
      }
    }
  }
}

// Box-downsample by an integer factor
function downsample(canvas, factor) {
  const src = canvas.data;
  const srcSize = canvas.size;
  const size = srcSize / factor;
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < factor; sy++) {
        for (let sx = 0; sx < factor; sx++) {
          const i = ((y * factor + sy) * srcSize + x * factor + sx) * 4;
          const pa = src[i + 3];
          r += src[i] * pa;
          g += src[i + 1] * pa;
          b += src[i + 2] * pa;
          a += pa;
        }
      }
      const o = (y * size + x) * 4;
      if (a > 0) {
        out[o] = Math.round(r / a);
        out[o + 1] = Math.round(g / a);
        out[o + 2] = Math.round(b / a);
        out[o + 3] = Math.round(a / (factor * factor));
      }
    }
  }
  return out;
}

// Renders shapes seen through a square view of the grid, on a transparent background
function render(size, view, { shapes }) {
  const SS = size <= 32 ? 8 : 4;
  const c = new Canvas(size * SS);
  const k = (size * SS) / view.size; // pixels per grid unit
  const toGrid = (p, origin) => origin + p / k;

  for (const s of shapes) {
    const d = distanceFn(s);
    const alpha = s.alpha ?? 1;
    c.paint((x, y) => Math.min(1, 0.5 - d(toGrid(x, view.x), toGrid(y, view.y)) * k) * alpha, colorFn(s, (x) => toGrid(x, view.x), (y) => toGrid(y, view.y)));
  }
  return encodePNG(size, size, downsample(c, SS));
}

const renderIcon = (size, shapes) => render(size, ICON_VIEW, { shapes });

// ---- Vector output (SVG + Android VectorDrawable) ----

const n = (v) => +v.toFixed(2);
const circlePath = (cx, cy, r) =>
  `M${n(cx - r)},${n(cy)}a${r},${r} 0 1,0 ${2 * r},0a${r},${r} 0 1,0 ${-2 * r},0z`;

// Returns { d, fill } or { d, stroke, width, cap } for one shape
function vectorPath(s) {
  switch (s.type) {
    case 'circle':
      return { d: circlePath(s.cx, s.cy, s.r) };
    case 'ring':
      return { d: circlePath(s.cx, s.cy, s.r), stroke: true, width: s.width };
    case 'line':
      return { d: `M${n(s.x1)},${n(s.y1)}L${n(s.x2)},${n(s.y2)}`, stroke: true, width: s.width, cap: 'round' };
    case 'rrect': {
      const { x, y, w, h, r } = s;
      return {
        d: `M${x + r},${y}h${w - 2 * r}a${r},${r} 0 0,1 ${r},${r}v${h - 2 * r}a${r},${r} 0 0,1 ${-r},${r}` +
          `h${-(w - 2 * r)}a${r},${r} 0 0,1 ${-r},${-r}v${-(h - 2 * r)}a${r},${r} 0 0,1 ${r},${-r}z`,
      };
    }
    case 'poly':
      return { d: 'M' + s.points.map(([x, y]) => `${n(x)},${n(y)}`).join('L') + 'z' };
    case 'wedge': {
      const [x0, y0] = polar(s.cx, s.cy, s.r, s.from);
      const [x1, y1] = polar(s.cx, s.cy, s.r, s.to);
      const large = s.to - s.from > 180 ? 1 : 0;
      return { d: `M${s.cx},${s.cy}L${n(x0)},${n(y0)}A${s.r},${s.r} 0 ${large},1 ${n(x1)},${n(y1)}z` };
    }
    case 'hand': {
      // The outline of the coloured icon's hand: the pivot circle (radius r) joined to a bar of
      // half-width tipR with a rounded tip, as one closed path for the even-odd cut-out
      const len = Math.hypot(s.x2 - s.cx, s.y2 - s.cy);
      const [ux, uy] = [(s.x2 - s.cx) / len, (s.y2 - s.cy) / len];
      const [px, py] = [-uy, ux];
      const d0 = Math.sqrt(s.r * s.r - s.tipR * s.tipR); // where the bar leaves the pivot circle
      const at = (x, y, along, side) => `${n(x + along * ux + side * px)},${n(y + along * uy + side * py)}`;
      return {
        d: `M${at(s.cx, s.cy, d0, s.tipR)}L${at(s.x2, s.y2, 0, s.tipR)}` +
          `A${s.tipR},${s.tipR} 0 0,0 ${at(s.x2, s.y2, 0, -s.tipR)}` +
          `L${at(s.cx, s.cy, d0, -s.tipR)}A${s.r},${s.r} 0 1,0 ${at(s.cx, s.cy, d0, s.tipR)}z`,
      };
    }
    case 'cutout':
      return { d: [s.base, ...s.holes].map((shape) => vectorPath(shape).d).join(''), evenOdd: true };
    default:
      throw new Error(`Unknown shape ${s.type}`);
  }
}

function svgShape(s, i) {
  const p = vectorPath(s);
  const alpha = s.alpha != null ? ` opacity="${s.alpha}"` : '';
  if (p.stroke) {
    return `<path d="${p.d}" fill="none" stroke="${s.color}" stroke-width="${p.width}"${p.cap ? ` stroke-linecap="${p.cap}"` : ''}${alpha}/>`;
  }
  if (!isGradient(s.color)) return `<path d="${p.d}" fill="${s.color}"${alpha}/>`;
  const { stops, from, to } = s.color;
  return `<linearGradient id="g${i}" x1="${from[0]}" y1="${from[1]}" x2="${to[0]}" y2="${to[1]}" gradientUnits="userSpaceOnUse">` +
    stops.map(([offset, color]) => `<stop offset="${offset}" stop-color="${color}"/>`).join('') +
    `</linearGradient><path d="${p.d}" fill="url(#g${i})"${alpha}/>`;
}

function iconSVG(shapes) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="${ICON_VIEW.x} ${ICON_VIEW.y} ${ICON_VIEW.size} ${ICON_VIEW.size}">
  ${shapes.map(svgShape).join('\n  ')}
</svg>
`;
}

// Android launcher layers sit in one group that scales the dial about the middle of the canvas,
// so its furthest point is 26.3 units from the centre, the same padding as the sibling apps' icons.
const ANDROID_PAD = { pivot: [54, 54], scale: 0.9724, translate: [0, 0] };

function vectorDrawable(shapes) {
  const paths = shapes.map((s) => {
    const p = vectorPath(s);
    const attrs = p.stroke
      ? [`android:strokeColor="${s.color}"`, `android:strokeWidth="${p.width}"`, ...(p.cap ? [`android:strokeLineCap="${p.cap}"`] : [])]
      : isGradient(s.color) ? [] : [`android:fillColor="${s.color}"`];
    if (s.alpha != null) attrs.push(`android:${p.stroke ? 'strokeAlpha' : 'fillAlpha'}="${s.alpha}"`);
    if (p.evenOdd) attrs.push('android:fillType="evenOdd"');
    attrs.push(`android:pathData="${p.d}"`);
    if (!isGradient(s.color)) return `    <path\n        ${attrs.join('\n        ')}/>`;
    const { stops, from, to } = s.color;
    const items = stops.map(([offset, color]) => `                <item android:offset="${offset}" android:color="${color}"/>`);
    return `    <path\n        ${attrs.join('\n        ')}>
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="${from[0]}"
                android:startY="${from[1]}"
                android:endX="${to[0]}"
                android:endY="${to[1]}">
${items.join('\n')}
            </gradient>
        </aapt:attr>
    </path>`;
  });
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by windows/scripts/generate-icons.cjs (npm run icons). Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:pivotX="${ANDROID_PAD.pivot[0]}"
        android:pivotY="${ANDROID_PAD.pivot[1]}"
        android:scaleX="${ANDROID_PAD.scale}"
        android:scaleY="${ANDROID_PAD.scale}"
        android:translateX="${ANDROID_PAD.translate[0]}"
        android:translateY="${ANDROID_PAD.translate[1]}">
${paths.map((p) => p.replace(/^/gm, '    ')).join('\n')}
    </group>
</vector>
`;
}

const androidBackground = `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by windows/scripts/generate-icons.cjs (npm run icons). Do not edit by hand. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="${ANDROID_BACKGROUND}"/>
</shape>
`;

// ---- Write files ----

const assetsDir = path.join(__dirname, '..', 'assets');
fs.mkdirSync(assetsDir, { recursive: true });

const ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256];
const icons = ICO_SIZES.map((size) => ({ size, png: renderIcon(size, ICON) }));
fs.writeFileSync(path.join(assetsDir, 'icon.svg'), iconSVG(ICON));
fs.writeFileSync(path.join(assetsDir, 'icon-256.png'), icons.at(-1).png);
fs.writeFileSync(path.join(assetsDir, 'icon.ico'), encodeICO(icons));
// Idle tray icon: the same dial in grey, so the coloured running icon stands out against it
const TRAY_IDLE = ICON.map((s, i) =>
  i === 0 ? { ...s, color: linear([[0, '#A1A1AA'], [1, '#71717A']], [FACE.cx, FACE.cy - DIAL_RADIUS], [FACE.cx, FACE.cy + DIAL_RADIUS]) } : s,
);
fs.writeFileSync(path.join(assetsDir, 'tray-idle.png'), renderIcon(32, TRAY_IDLE));
// Running tray icon: the dial in black, which main.cjs tints with the running
// timer's workspace colour (each pixel's grey level is how far it is toward white)
const TRAY_TEMPLATE = ICON.map((s, i) => (i === 0 ? { ...s, color: '#000000' } : s));
fs.writeFileSync(path.join(assetsDir, 'tray-template.png'), renderIcon(32, TRAY_TEMPLATE));

const androidRes = path.join(__dirname, '..', '..', 'android', 'app', 'src', 'main', 'res', 'drawable');
fs.writeFileSync(path.join(androidRes, 'ic_launcher_background.xml'), androidBackground);
fs.writeFileSync(path.join(androidRes, 'ic_launcher_foreground.xml'), vectorDrawable(ICON));
fs.writeFileSync(path.join(androidRes, 'ic_launcher_monochrome.xml'), vectorDrawable(MONOCHROME));

console.log('Icons written to assets/ and android/app/src/main/res/drawable/');
