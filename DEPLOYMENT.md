# Running and deploying the Student Management System

## Run locally from VS Code

Start MySQL, then run `run-browser.bat` from the VS Code terminal. Enter the
MySQL password when prompted. The browser opens after the API and database are
ready. Keep the terminal open while using the app.

## Deploy the web app to Vercel

Vercel serves the static web page and the `/api/*` proxy. It does not run this
Java API server. The app therefore needs two deployments: the frontend and API
proxy on Vercel, and the Java API as a Docker service (for example on Railway,
Render, or Fly.io). The Java service must be able to connect to a reachable
MySQL database.

1. Deploy this folder's `Dockerfile` as the backend service. Configure its
   database environment variables there. The server accepts
   `MYSQL_URL` or `DATABASE_URL`, or `MYSQLHOST`, `MYSQLPORT`, `MYSQLUSER`,
   `MYSQLPASSWORD`, and `MYSQLDATABASE`. Set `SMS_ADMIN_PASSWORD` on first
   startup if the database has no admin account; use at least 8 characters,
   including a letter and a digit.
2. Confirm the backend is healthy by opening
   `https://your-api.example.com/api/health` and checking that it reports
   `"status": "OK"`.
3. Import the repository into Vercel and set the project root to this `Java`
   folder. Use the `Other` framework preset and leave the build command empty;
   the static files and `api/[...path].js` function are deployed directly.
   The `.vercelignore` file excludes Java sources and local data files.
4. In the Vercel project's Settings > Environment Variables, set
   `SMS_BACKEND_URL` to the backend's public HTTPS origin only, for example
   `https://your-api.example.com` (no `/api` path or trailing route). Apply it
   to the environments you use, then redeploy. Leave `window.SMS_API_BASE`
   empty in `config.js`: the browser calls the same-origin `/api/*` proxy, and
   the proxy forwards requests to the Java backend. Do not put backend or
   database credentials in frontend files.
5. Open the Vercel deployment URL. The web client sends `/api/*` requests to
   the Vercel function, which forwards them to the Java backend.

The backend and MySQL database must remain running and reachable by each other.
Do not put database passwords in Vercel's frontend files or in
`SMS_BACKEND_URL`. Deploying only to Vercel is not sufficient for this Java +
MySQL app; Vercel hosts the frontend and proxy, not the Java API or database.
