import { toNotionDateTime } from './format.js';

/**
 * Property types the Notion API can write and the app supports as extra
 * fields. Read-only/computed types (formula, rollup, created_time, …) and
 * the managed title are excluded.
 */
export const WRITABLE_EXTRA_TYPES = [
  'rich_text',
  'number',
  'select',
  'multi_select',
  'status',
  'date',
  'checkbox',
  'url',
  'email',
  'phone_number',
  'people',
  'relation',
  'files',
];

/** The neutral input value for a given property type. */
export function emptyValueFor(type) {
  switch (type) {
    case 'checkbox':
      return false;
    case 'multi_select':
      return [];
    default:
      return ''; // text-likes, number, select, status, date, people, relation, files
  }
}

/**
 * Brings a stored value into the input shape this app uses for `type`.
 * Presets synced from the Android app store every value as a string,
 * multi-select and people as comma-separated names/ids, checkboxes as
 * "true"/"false", which this app's inputs don't read: a multi-select was
 * silently dropped and "false" ticked the checkbox.
 */
export function coerceInputValue(type, value) {
  if (value == null) return emptyValueFor(type);
  const list = () =>
    (Array.isArray(value) ? value : String(value).split(','))
      .map((v) => String(v).trim())
      .filter(Boolean);
  switch (type) {
    case 'multi_select':
      return list();
    case 'checkbox':
      return typeof value === 'boolean' ? value : String(value).trim().toLowerCase() === 'true';
    case 'people':
    case 'relation':
      return list()[0] ?? '';
    default:
      return Array.isArray(value) ? value.join(',') : String(value);
  }
}

/**
 * Converts a raw input value into a Notion API property value.
 * Returns null when the value is empty; empty fields are omitted from the
 * create payload so Notion keeps its own defaults.
 *
 * @param {string} type   Notion property type (one of WRITABLE_EXTRA_TYPES)
 * @param {*} value       Input value: string for most types, boolean for
 *                        checkbox, string[] for multi_select, page/user id
 *                        for relation/people, datetime-local string for date
 */
export function buildPropertyValue(type, value) {
  switch (type) {
    case 'rich_text': {
      const text = String(value ?? '').trim();
      return text ? { rich_text: [{ text: { content: text } }] } : null;
    }
    case 'number': {
      const n = parseFloat(value);
      return Number.isFinite(n) ? { number: n } : null;
    }
    case 'select':
      return value ? { select: { name: value } } : null;
    case 'status':
      return value ? { status: { name: value } } : null;
    case 'multi_select':
      return Array.isArray(value) && value.length
        ? { multi_select: value.map((name) => ({ name })) }
        : null;
    case 'date':
      // value comes from an <input type="datetime-local">, which is local time
      return value ? { date: { start: toNotionDateTime(new Date(value)) } } : null;
    case 'checkbox':
      return { checkbox: !!value };
    case 'url': {
      const url = String(value ?? '').trim();
      return url ? { url } : null;
    }
    case 'email': {
      const email = String(value ?? '').trim();
      return email ? { email } : null;
    }
    case 'phone_number': {
      const phone = String(value ?? '').trim();
      return phone ? { phone_number: phone } : null;
    }
    case 'people':
      return value ? { people: [{ id: value }] } : null;
    case 'relation':
      return value ? { relation: [{ id: value }] } : null;
    case 'files': {
      const url = String(value ?? '').trim();
      // Notion requires a name; the URL itself is the clearest one available
      return url ? { files: [{ name: url.slice(0, 100), type: 'external', external: { url } }] } : null;
    }
    default:
      return null;
  }
}

/** "2026-06-11T09:30:00.000+02:00" -> "2026-06-11T09:30" (local), for <input type="datetime-local">. */
function isoToDateTimeLocal(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * Reverse of buildPropertyValue: reads a page's raw Notion property value
 * back into the input value shape used by extraValues/ExtraFieldInput.
 *
 * @param {string} type   Notion property type (one of WRITABLE_EXTRA_TYPES)
 * @param {object} prop   The raw property object from the Notion API, e.g. `page.properties[name]`
 */
export function parsePropertyValue(type, prop) {
  if (!prop) return emptyValueFor(type);
  switch (type) {
    case 'rich_text':
      return (prop.rich_text || []).map((t) => t.plain_text ?? '').join('');
    case 'number':
      return typeof prop.number === 'number' ? String(prop.number) : '';
    case 'select':
      return prop.select?.name ?? '';
    case 'status':
      return prop.status?.name ?? '';
    case 'multi_select':
      return (prop.multi_select || []).map((o) => o.name);
    case 'date':
      return prop.date?.start ? isoToDateTimeLocal(prop.date.start) : '';
    case 'checkbox':
      return !!prop.checkbox;
    case 'url':
      return prop.url ?? '';
    case 'email':
      return prop.email ?? '';
    case 'phone_number':
      return prop.phone_number ?? '';
    case 'people':
      return prop.people?.[0]?.id ?? '';
    case 'relation':
      return prop.relation?.[0]?.id ?? '';
    case 'files': {
      const file = prop.files?.[0];
      return file?.external?.url ?? file?.file?.url ?? '';
    }
    default:
      return emptyValueFor(type);
  }
}
