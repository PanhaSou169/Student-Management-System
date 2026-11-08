/* ================================================================
   config.js — tell the website where the backend (Java server) is.

   * Running on your computer, or the Java server (Railway) serves the site itself:
       leave it EMPTY:      window.SMS_API_BASE = "";

   * Website on Vercel, backend on Railway: put the Railway link here,
     with https:// and WITHOUT a slash at the end:
       window.SMS_API_BASE = "https://your-app.up.railway.app";

   Check the link first: open  <your link>/api/health  in the browser.
   It must show {"status":"OK",...}
   ================================================================ */
window.SMS_API_BASE = "";
