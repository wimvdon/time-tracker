// Derives contrast-safe variants of a user-chosen workspace color, one for
// use on the light theme's near-white background and one for the dark
// theme's near-black background. Hue/saturation are preserved; lightness is
// only nudged when the original color wouldn't meet ~3:1 contrast (WCAG's
// non-text/UI-component minimum, enough to stay legible without pulling
// saturated colors like yellow so dark they read as brown).

const LIGHT_BG_LUMINANCE = 1; // page background in light theme (white)
const DARK_BG_LUMINANCE = 0.012; // page background in dark theme (zinc-900 ~ #18181b)
const MIN_CONTRAST = 3;

// Max luminance a color can have and still hit MIN_CONTRAST against a white
// background (also covers white text drawn on top of a solid fill of that
// color, since the contrast formula is symmetric when one side is white).
const LIGHT_TARGET_LUMINANCE = (LIGHT_BG_LUMINANCE + 0.05) / MIN_CONTRAST - 0.05;
// Min luminance a color needs to hit MIN_CONTRAST against the dark background.
const DARK_TARGET_LUMINANCE = MIN_CONTRAST * (DARK_BG_LUMINANCE + 0.05) - 0.05;

export function hexToRgb(hex) {
  const clean = hex.replace('#', '');
  return {
    r: parseInt(clean.slice(0, 2), 16),
    g: parseInt(clean.slice(2, 4), 16),
    b: parseInt(clean.slice(4, 6), 16),
  };
}

export function rgbToHex(r, g, b) {
  const toHex = (n) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, '0');
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

export function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let h = 0;
  let s = 0;
  if (d !== 0) {
    s = d / (1 - Math.abs(2 * l - 1));
    switch (max) {
      case r: h = ((g - b) / d) % 6; break;
      case g: h = (b - r) / d + 2; break;
      default: h = (r - g) / d + 4; break;
    }
    h *= 60;
    if (h < 0) h += 360;
  }
  return { h, s, l };
}

export function hslToRgb(h, s, l) {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  let r1, g1, b1;
  if (h < 60) { r1 = c; g1 = x; b1 = 0; }
  else if (h < 120) { r1 = x; g1 = c; b1 = 0; }
  else if (h < 180) { r1 = 0; g1 = c; b1 = x; }
  else if (h < 240) { r1 = 0; g1 = x; b1 = c; }
  else if (h < 300) { r1 = x; g1 = 0; b1 = c; }
  else { r1 = c; g1 = 0; b1 = x; }
  return { r: (r1 + m) * 255, g: (g1 + m) * 255, b: (b1 + m) * 255 };
}

function srgbChannelToLinear(c) {
  const v = c / 255;
  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

export function relativeLuminance({ r, g, b }) {
  return (
    0.2126 * srgbChannelToLinear(r) +
    0.7152 * srgbChannelToLinear(g) +
    0.0722 * srgbChannelToLinear(b)
  );
}

export function contrastRatio(hexA, hexB) {
  const lumA = relativeLuminance(hexToRgb(hexA));
  const lumB = relativeLuminance(hexToRgb(hexB));
  const lighter = Math.max(lumA, lumB);
  const darker = Math.min(lumA, lumB);
  return (lighter + 0.05) / (darker + 0.05);
}

// Binary-searches lightness (hue/saturation fixed) for the extreme value of L
// that still satisfies the luminance constraint, so the color moves as
// little as possible off the user's original choice.
function solveLightness(h, s, targetLuminance, direction) {
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 24; i++) {
    const mid = (lo + hi) / 2;
    const lum = relativeLuminance(hslToRgb(h, s, mid));
    const midSatisfies = direction === 'darken' ? lum <= targetLuminance : lum >= targetLuminance;
    if (direction === 'darken') {
      if (midSatisfies) lo = mid; else hi = mid;
    } else {
      if (midSatisfies) hi = mid; else lo = mid;
    }
  }
  return direction === 'darken' ? lo : hi;
}

/**
 * Given a user-chosen hex color, returns { light, dark } variants that keep
 * the same hue/saturation but guarantee ~3:1 contrast against the app's
 * light-theme (near-white) and dark-theme (near-black) backgrounds. Also
 * safe to use as a solid fill behind white text/icons.
 */
export function getWorkspaceColorVariants(hex) {
  const rgb = hexToRgb(hex);
  const { h, s, l } = rgbToHsl(rgb.r, rgb.g, rgb.b);
  const currentLuminance = relativeLuminance(rgb);

  const lightL = currentLuminance <= LIGHT_TARGET_LUMINANCE
    ? l
    : solveLightness(h, s, LIGHT_TARGET_LUMINANCE, 'darken');
  const darkL = currentLuminance >= DARK_TARGET_LUMINANCE
    ? l
    : solveLightness(h, s, DARK_TARGET_LUMINANCE, 'lighten');

  const lightRgb = hslToRgb(h, s, lightL);
  const darkRgb = hslToRgb(h, s, darkL);

  return {
    light: rgbToHex(lightRgb.r, lightRgb.g, lightRgb.b),
    dark: rgbToHex(darkRgb.r, darkRgb.g, darkRgb.b),
  };
}

/** Picks the variant matching the currently active theme. */
export function getWorkspaceAccentColor(hex, isDarkTheme) {
  if (!hex) return null;
  const variants = getWorkspaceColorVariants(hex);
  return isDarkTheme ? variants.dark : variants.light;
}

/**
 * The variant guaranteed dark/saturated enough to sit behind white text or
 * icons (e.g. the Start button, tray icon), independent of the app's theme.
 */
export function getWorkspaceSolidFillColor(hex) {
  if (!hex) return null;
  return getWorkspaceColorVariants(hex).light;
}
