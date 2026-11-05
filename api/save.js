// GET /api/save?url=https://cdn.ttsforfree.com/....mp3&name=voiceover.mp3
// Downloads the MP3 and commits it to audio/<name> in this GitHub repo.
module.exports = async (req, res) => {
  try {
    const { url, name } = req.query;
    const u = new URL(url || "");
    if (u.protocol !== "https:" || u.hostname !== "cdn.ttsforfree.com")
      return res.status(400).json({ error: "Only https://cdn.ttsforfree.com URLs are allowed" });
    const file = String(name || "voiceover.mp3").replace(/[^a-zA-Z0-9._-]/g, "_");
    if (!file.endsWith(".mp3")) return res.status(400).json({ error: "name must end with .mp3" });

    const owner = process.env.VERCEL_GIT_REPO_OWNER;
    const repo = process.env.VERCEL_GIT_REPO_SLUG;
    const token = process.env.GITHUB_TOKEN;
    if (!owner || !repo || !token)
      return res.status(500).json({ error: "Missing GITHUB_TOKEN or repo info" });

    const audio = await fetch(u, { redirect: "error" });
    if (!audio.ok) return res.status(502).json({ error: "Download failed: " + audio.status });
    const buf = Buffer.from(await audio.arrayBuffer());
    if (buf.length > 10e6) return res.status(413).json({ error: "File too large (10 MB max)" });

    const api = `https://api.github.com/repos/${owner}/${repo}/contents/audio/${file}`;
    const h = {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "tts-saver",
      "X-GitHub-Api-Version": "2022-11-28",
    };
    const old = await fetch(api, { headers: h });
    const sha = old.ok ? (await old.json()).sha : undefined;
    const put = await fetch(api, {
      method: "PUT",
      headers: h,
      body: JSON.stringify({ message: "Add " + file, content: buf.toString("base64"), sha }),
    });
    if (!put.ok)
      return res.status(502).json({ error: "GitHub error " + put.status, detail: (await put.text()).slice(0, 300) });
    res.status(200).json({
      ok: true,
      bytes: buf.length,
      raw_url: `https://raw.githubusercontent.com/${owner}/${repo}/HEAD/audio/${file}`,
    });
  } catch (e) {
    res.status(500).json({ error: String(e.message || e) });
  }
};
