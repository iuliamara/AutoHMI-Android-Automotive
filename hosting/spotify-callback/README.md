# Static callback hosting template

Follow `docs/SPOTIFY_APP_LINKS.md` in the AutoHMI project before publishing.

Generate a publishable directory using `scripts/prepare-spotify-app-links.ps1` with your actual GitHub username and signing-certificate SHA-256. The checked-in `assetlinks.json.template` deliberately has no real certificate or live callback domain.

Publish the generated directory's contents at the **domain root**, not under an `/AutoHMI/` project path. Include the hidden `.nojekyll` file and `.well-known` directory. Check `/.well-known/assetlinks.json` returns HTTPS 200 `application/json` without redirects.

These are static public files, not an OAuth backend. The callback page does not collect, display, exchange or store credentials, authorization codes or tokens. Do not add third-party scripts, analytics, a client secret, or tokens.
