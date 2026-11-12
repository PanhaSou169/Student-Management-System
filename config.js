/* ================================================================
   config.js — tell the website where the backend (Java server) is.

   * Local use, or a Vercel site with the /api proxy:
       leave it EMPTY:      window.SMS_API_BASE = "";
     On Vercel, set SMS_BACKEND_URL in the project's environment variables.

   * Only for a static host without an /api proxy, set the backend's HTTPS
     origin here (no trailing slash). Prefer the same-origin proxy when available.
   ================================================================ */
window.SMS_API_BASE = "";
