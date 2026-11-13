/**
 * Pure helpers for the raw schema object returned by getDatabaseSchema.
 * No API calls here: only data transformation.
 */

/**
 * Returns all properties of a given Notion property type.
 * @param {object} schema  Raw properties object from getDatabaseSchema
 * @param {string} type    Notion property type: 'title' | 'date' | 'status' | 'relation' | ...
 * @returns {{ name: string, id: string }[]}
 */
export function propertiesByType(schema, type) {
  return Object.entries(schema || {})
    .filter(([, prop]) => prop.type === type)
    .map(([name, prop]) => ({ name, id: prop.id }));
}

/**
 * Returns the status options for a given status property.
 * Returns [] if the property doesn't exist or isn't a status type.
 * @param {object} schema
 * @param {string} propName  Name of the status property
 * @returns {{ name: string, color: string }[]}
 */
export function statusOptions(schema, propName) {
  const prop = schema?.[propName];
  if (!prop || prop.type !== 'status') return [];
  return (prop.status?.options ?? []).map((o) => ({ name: o.name, color: o.color }));
}

/**
 * How many of a workspace's extra fields the app can't show: the property is not in the
 * database schema (renamed or deleted in Notion, or a relation to a database that isn't shared
 * with the app, which Notion leaves out), or it has a type the app can't write. Same rule as the
 * Android app (`TimerViewModel.buildExtraFieldDefs`).
 * @param {{ propName: string }[]} extraProps  The workspace's configured extra fields
 * @param {object} schema  Raw properties object from getDatabaseSchema
 * @param {string[]} writableTypes  WRITABLE_EXTRA_TYPES
 */
export function countMissingExtraProps(extraProps, schema, writableTypes) {
  return (extraProps || []).filter(
    ({ propName }) => propName && !writableTypes.includes(schema?.[propName]?.type)
  ).length;
}
