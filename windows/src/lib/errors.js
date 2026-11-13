import { t } from './i18n.js';

// Codes the main process uses instead of text (electron/notionAuth.cjs, electron/notionClient.cjs).
const NOT_SIGNED_IN = 'NOT_SIGNED_IN';
const SIGN_IN_CANCELLED = 'SIGN_IN_CANCELLED';

/**
 * Returns a clean, user-displayable message from an error. Errors crossing
 * the IPC bridge arrive wrapped as "Error invoking remote method 'x': Error:
 * actual message"; strip that noise so toasts show only the actual message.
 */
export function errorMessage(err) {
  const message = String(err?.message || err).replace(
    /^Error invoking remote method '[^']+':\s*(Error:\s*)?/,
    ''
  );
  return message === NOT_SIGNED_IN ? t('errorNotSignedIn') : message;
}

/** True when the user stopped "Sign in with Notion" themselves; nothing to report then. */
export function isSignInCancelled(err) {
  return errorMessage(err) === SIGN_IN_CANCELLED;
}
