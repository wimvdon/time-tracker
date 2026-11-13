/** Date/time formatting helpers. */

const pad = (n) => String(n).padStart(2, '0');

/**
 * Formats a Date as ISO 8601 with the local timezone offset,
 * e.g. "2026-06-11T14:00:00.000+02:00". (Date.toISOString() would convert
 * to UTC, which is not what the Notion buttons produce.)
 */
export function toNotionDateTime(date) {
  const tzOffset = -date.getTimezoneOffset();
  const sign = tzOffset >= 0 ? '+' : '-';
  const absOffset = Math.abs(tzOffset);
  const hh = pad(Math.floor(absOffset / 60));
  const mm = pad(absOffset % 60);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:00.000` +
    `${sign}${hh}:${mm}`
  );
}

/** Every date format offered in Settings. */
export const DATE_FORMATS = ['DD/MM/YYYY', 'MM/DD/YYYY', 'YYYY-MM-DD'];

/**
 * The page title written to Notion when the description is left empty: the
 * entry's own start date, in the format chosen in Settings. The description
 * input itself is never pre-filled with it; the fallback only ever exists on
 * the Notion page.
 */
export function fallbackDescription(date, dateFormat = 'DD/MM/YYYY') {
  return formatDateStr(date, dateFormat);
}

/**
 * True when `title` is a title this app generated for an empty description on
 * `date`. Every date format is checked, not just the current one, so a title
 * written before the setting was changed is still recognised. Used to keep the
 * description input empty when a running timer is adopted from Notion.
 */
export function isFallbackDescription(title, date, dateFormat = 'DD/MM/YYYY') {
  const trimmed = (title || '').trim();
  if (!trimmed || !date) return false;
  const formats = [dateFormat, ...DATE_FORMATS];
  return formats.some((fmt) => formatDateStr(date, fmt) === trimmed);
}

/** Formats a date object according to the user's preferences. */
export function formatDateStr(date, dateFormat = 'DD/MM/YYYY') {
  if (!date) return '';
  const d = new Date(date);
  const day = pad(d.getDate());
  const month = pad(d.getMonth() + 1);
  const year = d.getFullYear();
  
  if (dateFormat === 'MM/DD/YYYY') {
    return `${month}/${day}/${year}`;
  } else if (dateFormat === 'YYYY-MM-DD') {
    return `${year}-${month}-${day}`;
  }
  return `${day}/${month}/${year}`;
}

/** Formats time as HH:MM or HH:MM AM/PM according to preferences. */
export function formatTimeStr(date, timeFormat = '24h') {
  if (!date) return '';
  const d = new Date(date);
  const hours = d.getHours();
  const minutes = d.getMinutes();
  
  if (timeFormat === '12h') {
    const period = hours >= 12 ? 'PM' : 'AM';
    const h12 = hours % 12 || 12;
    return `${pad(h12)}:${pad(minutes)} ${period}`;
  }
  return `${pad(hours)}:${pad(minutes)}`;
}

/** "14:00" */
export function formatHHMM(date) {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Seconds → "HH:MM:SS" */
export function formatElapsed(totalSeconds) {
  const s = Math.max(0, totalSeconds);
  const hours = Math.floor(s / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const seconds = s % 60;
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
}

/** Parse time string that may be in 12h or 24h format. */
export function parseTimeStr(hhmm) {
  const clean = hhmm.trim().toUpperCase();
  const isPM = clean.endsWith('PM');
  const isAM = clean.endsWith('AM');
  const timePart = clean.replace(/AM|PM/, '').trim();
  const [hStr, mStr] = timePart.split(':');
  let hours = parseInt(hStr, 10) || 0;
  const minutes = parseInt(mStr, 10) || 0;
  
  if (isPM && hours < 12) {
    hours += 12;
  } else if (isAM && hours === 12) {
    hours = 0;
  }
  return { hours, minutes };
}
