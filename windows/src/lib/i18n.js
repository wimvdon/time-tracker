import enGB from '../locales/en-GB.js';
import nlBE from '../locales/nl-BE.js';
import frFR from '../locales/fr.js';
import deDE from '../locales/de.js';
import esES from '../locales/es.js';
import itIT from '../locales/it.js';

const locales = {
  'en-GB': enGB,
  'nl-BE': nlBE,
  'fr': frFR,
  'de': deDE,
  'es': esES,
  'it': itIT
};

/**
 * Maps any locale tag to one of the bundled locales by its language subtag.
 * Both apps write their own tags into the shared sync page: this app stores
 * `en-GB`/`nl-BE`, the Android app stores `en`/`nl`, so a tag pulled from the
 * other device has to resolve by language, not by exact match, or the language
 * silently falls back to English on every sync.
 */
export function normaliseLocale(locale) {
  const lang = String(locale || '').toLowerCase().split(/[-_]/)[0];
  switch (lang) {
    case 'nl': return 'nl-BE';
    case 'fr': return 'fr';
    case 'de': return 'de';
    case 'es': return 'es';
    case 'it': return 'it';
    case 'en': return 'en-GB';
    default: return null;
  }
}

export function detectLocale() {
  const lang = navigator.language || navigator.languages?.[0] || 'en-GB';
  return normaliseLocale(lang) ?? 'en-GB';
}

let resolvedLocaleStr = detectLocale();

export function setLocale(locale) {
  if (locale === 'system' || !locale) {
    resolvedLocaleStr = detectLocale();
  } else {
    resolvedLocaleStr = normaliseLocale(locale) ?? 'en-GB';
  }
}

export function t(key) {
  const strings = locales[resolvedLocaleStr] || enGB;
  return strings[key] ?? enGB[key] ?? key;
}
