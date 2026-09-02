// Every request the page makes, and the handful of answers that mean something other than data.

import { t } from './i18n.js';
import { state } from './state.js';

export function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : '';
}

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  // FormData sets its own Content-Type, with the multipart boundary in it. Setting one here would
  // replace that boundary with nothing and the server would fail to parse the body.
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  // Every state-changing request echoes the cookie back in a header. The cookie is readable and the
  // header is not settable cross-origin, which is what makes the pair proof of same-origin.
  if (options.method && options.method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();

  let response;
  try {
    response = await fetch(path, { ...options, headers });
  } catch (networkError) {
    throw new Error(t('error.offline'));
  }

  if (response.status === 401) {
    // The session is gone. Back through the front door rather than an error they can do nothing with.
    window.location.href = '/oauth2/authorization/feishu';
    const handled = new Error('unauthenticated');
    handled.handled = true;
    throw handled;
  }
  if (response.status === 403) {
    // Either this deployment does not serve them, or the CSRF cookie is missing. Both are worth
    // saying out loud; /api/me is what distinguishes them, and start() has already asked it.
    throw new Error(state.me && state.me.allowed === false
      ? t('denied.short') : t('error.forbidden'));
  }
  if (!response.ok) {
    let message = `${t('error.generic')} (${response.status})`;
    try {
      const body = await response.json();
      // Spring's ProblemDetail puts the reason in `detail`; ResponseStatusException in `message`.
      if (body && (body.detail || body.message)) message = body.detail || body.message;
    } catch (e) { /* a non-JSON error body is still an error */ }
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}
