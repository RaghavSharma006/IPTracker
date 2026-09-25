package org.example.handler;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class UiHandler {

    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Canary Tokens</title>
              <style>
                :root {
                  --bg: #0f1117;
                  --card: #1a1d26;
                  --border: #2d3139;
                  --text: #e6e6e9;
                  --muted: #8b8f97;
                  --accent: #5aa1ff;
                  --accent-hover: #7ab7ff;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  min-height: 100vh;
                  background: var(--bg);
                  color: var(--text);
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  padding: 2rem;
                }
                .card {
                  background: var(--card);
                  border: 1px solid var(--border);
                  border-radius: 14px;
                  padding: 2.25rem;
                  width: 100%;
                  max-width: 420px;
                }
                h1 { font-size: 1.35rem; margin-bottom: 0.6rem; }
                p { color: var(--muted); font-size: 0.875rem; margin-bottom: 1.4rem; }
                .field { margin-bottom: 1.1rem; }
                label { display: block; font-size: 0.8rem; text-transform: uppercase; letter-spacing: .03em; color: var(--muted); margin-bottom: .35rem; }
                input[type=email] {
                  width: 100%;
                  padding: .7rem .85rem;
                  border: 1px solid var(--border);
                  border-radius: 8px;
                  background: #12141a;
                  color: var(--text);
                  font-size: .95rem;
                  outline: none;
                  transition: border-color .18s;
                }
                input[type=email]:focus { border-color: var(--accent); }
                button {
                  width: 100%;
                  padding: .78rem;
                  border: none;
                  border-radius: 8px;
                  background: var(--accent);
                  color: #0f1117;
                  font-weight: 600;
                  font-size: .95rem;
                  cursor: pointer;
                  transition: background .18s;
                }
                button:hover { background: var(--accent-hover); }
                button:disabled { opacity: .6; cursor: not-allowed; }
                .result {
                  margin-top: 1.2rem;
                  padding: .9rem;
                  background: #12141a;
                  border: 1px solid var(--border);
                  border-radius: 8px;
                  word-break: break-all;
                  font-size: .85rem;
                  display: flex;
                  align-items: center;
                  gap: .5rem;
                }
                .result button {
                  width: auto;
                  padding: .35rem .7rem;
                  font-size: .78rem;
                  margin: 0;
                }
                .error-msg { color: #ff5f56; font-size: .85rem; margin-top: .5rem; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>Canary Tokens</h1>
                <p>Enter an email to generate a canary link. Visiting the link triggers an alert to that address.</p>
                <div class="field">
                  <label for="email">Email</label>
                  <input type="email" id="email" placeholder="you@example.com" autocomplete="email">
                </div>
                <button id="go" type="button">Generate Link</button>
                <p id="err" class="error-msg" style="display:none"></p>
                <div id="res" class="result" style="display:none">
                  <span id="link"></span>
                  <button id="copy" type="button">Copy</button>
                </div>
              </div>
              <script>
                const go = document.getElementById('go');
                const email = document.getElementById('email');
                const res = document.getElementById('res');
                const linkSpan = document.getElementById('link');
                const copyBtn = document.getElementById('copy');
                const errP = document.getElementById('err');

                go.onclick = async () => {
                  const addr = email.value.trim();
                  if (!addr) { errP.textContent = 'Please enter an email.'; errP.style.display = 'block'; return; }
                  errP.style.display = 'none';
                  go.disabled = true; go.textContent = 'Generating…';
                  try {
                    const r = await fetch('/api/tokens', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({email: addr}) });
                    if (!r.ok) { const j = await r.json().catch(()=>({})); throw new Error(j.error || 'Failed'); }
                    const data = await r.json();
                    linkSpan.textContent = data.link;
                    res.style.display = 'flex';
                  } catch (e) { errP.textContent = e.message; errP.style.display = 'block'; }
                  finally { go.disabled = false; go.textContent = 'Generate Link'; }
                };

                copyBtn.onclick = () => { navigator.clipboard.writeText(linkSpan.textContent); copyBtn.textContent = 'Copied'; setTimeout(()=>copyBtn.textContent='Copy', 1200); };
              </script>
            </body>
            </html>
            """;

    public static void serve(HttpExchange exchange) {
        try {
            byte[] body = HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }
}
