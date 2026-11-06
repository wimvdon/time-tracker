// The few pages the worker shows in the browser during sign-in, in the apps' six languages.

const TEXT = {
  en: {
    title: 'Time Tracker',
    openApp: 'Signed in to Notion. Go back to Time Tracker to finish.',
    button: 'Open Time Tracker',
    expired: 'This sign-in link has expired. Start again from Time Tracker.',
    badRequest: 'Something went wrong. Start the sign-in again from Time Tracker.',
  },
  nl: {
    title: 'Time Tracker',
    openApp: 'Aangemeld bij Notion. Ga terug naar Time Tracker om af te ronden.',
    button: 'Time Tracker openen',
    expired: 'Deze aanmeldlink is verlopen. Begin opnieuw vanuit Time Tracker.',
    badRequest: 'Er ging iets mis. Begin de aanmelding opnieuw vanuit Time Tracker.',
  },
  fr: {
    title: 'Time Tracker',
    openApp: 'Connecté à Notion. Revenez à Time Tracker pour terminer.',
    button: 'Ouvrir Time Tracker',
    expired: 'Ce lien de connexion a expiré. Recommencez depuis Time Tracker.',
    badRequest: 'Une erreur est survenue. Recommencez la connexion depuis Time Tracker.',
  },
  de: {
    title: 'Time Tracker',
    openApp: 'Bei Notion angemeldet. Kehre zu Time Tracker zurück, um abzuschließen.',
    button: 'Time Tracker öffnen',
    expired: 'Dieser Anmeldelink ist abgelaufen. Beginne erneut in Time Tracker.',
    badRequest: 'Etwas ist schiefgelaufen. Beginne die Anmeldung erneut in Time Tracker.',
  },
  es: {
    title: 'Time Tracker',
    openApp: 'Sesión iniciada en Notion. Vuelve a Time Tracker para terminar.',
    button: 'Abrir Time Tracker',
    expired: 'Este enlace de inicio de sesión ha caducado. Empieza de nuevo desde Time Tracker.',
    badRequest: 'Algo salió mal. Vuelve a iniciar sesión desde Time Tracker.',
  },
  it: {
    title: 'Time Tracker',
    openApp: 'Accesso a Notion effettuato. Torna a Time Tracker per completare.',
    button: 'Apri Time Tracker',
    expired: 'Questo link di accesso è scaduto. Ricomincia da Time Tracker.',
    badRequest: 'Qualcosa è andato storto. Ricomincia l’accesso da Time Tracker.',
  },
};

/** The first of the browser's languages this page has, else English. */
export function pickLanguage(acceptLanguage) {
  for (const part of String(acceptLanguage || '').split(',')) {
    const lang = part.trim().split(/[-_;]/)[0].toLowerCase();
    if (TEXT[lang]) return lang;
  }
  return 'en';
}

const escapeHtml = (text) =>
  String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

/** A small page with `message`; with `link`, also a button to (and an automatic jump to) the app. */
export function messagePage(status, message, acceptLanguage, link) {
  const text = TEXT[pickLanguage(acceptLanguage)];
  const href = link ? escapeHtml(link) : '';
  const html = `<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
${link ? `<meta http-equiv="refresh" content="0;url=${href}">` : ''}
<title>${escapeHtml(text.title)}</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f3f3f3;color:#1b1b1b}
main{max-width:22rem;padding:2rem;text-align:center}
a{display:inline-block;margin-top:1rem;padding:.75rem 1.25rem;border-radius:.5rem;background:#E53935;color:#fff;text-decoration:none;font-weight:600}
@media (prefers-color-scheme:dark){body{background:#202020;color:#f3f3f3}}
</style></head>
<body><main><p>${escapeHtml(text[message])}</p>${link ? `<a href="${href}">${escapeHtml(text.button)}</a>` : ''}</main></body></html>`;
  return new Response(html, {
    status,
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'",
      'Referrer-Policy': 'no-referrer',
    },
  });
}
