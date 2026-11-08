module.exports = async function handler(req, res) {
  const backend = process.env.SMS_BACKEND_URL;
  if (!backend) {
    res.status(503).json({
      message: "The API backend is not configured. Set SMS_BACKEND_URL in Vercel.",
    });
    return;
  }

  let base;
  try {
    base = new URL(backend);
    if (
      !["http:", "https:"].includes(base.protocol) ||
      base.username ||
      base.password ||
      base.pathname !== "/" ||
      base.search ||
      base.hash
    ) {
      throw new Error("SMS_BACKEND_URL must be an HTTP(S) origin.");
    }
  } catch (error) {
    console.error("Invalid SMS_BACKEND_URL:", error.message);
    res.status(500).json({
      message: "SMS_BACKEND_URL must be the backend origin, such as https://your-api.example.com.",
    });
    return;
  }

  if (!["GET", "HEAD", "POST", "OPTIONS"].includes(req.method)) {
    res.status(405).json({ message: "Method not allowed." });
    return;
  }

  const headers = { accept: req.headers.accept || "application/json" };
  if (req.headers.authorization) headers.authorization = req.headers.authorization;
  if (req.headers["content-type"]) headers["content-type"] = req.headers["content-type"];

  let body;
  if (!["GET", "HEAD"].includes(req.method) && req.body !== undefined) {
    body = typeof req.body === "string" || Buffer.isBuffer(req.body)
      ? req.body
      : JSON.stringify(req.body);
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);

  try {
    const url = new URL(req.url, base);
    const response = await fetch(url, {
      method: req.method,
      headers,
      body,
      redirect: "manual",
      signal: controller.signal,
    });

    res.status(response.status);
    res.setHeader(
      "Content-Type",
      response.headers.get("content-type") || "application/json; charset=utf-8",
    );
    const responseBody = await response.arrayBuffer();
    res.end(Buffer.from(responseBody));
  } catch (error) {
    const timedOut = error.name === "AbortError";
    console.error("Vercel API proxy failed:", error.message);
    res.status(timedOut ? 504 : 502).json({
      message: timedOut
        ? "The API backend did not respond in time. Check that the backend is running."
        : "Cannot reach the API backend. Check SMS_BACKEND_URL and the backend service.",
    });
  } finally {
    clearTimeout(timeout);
  }
};
