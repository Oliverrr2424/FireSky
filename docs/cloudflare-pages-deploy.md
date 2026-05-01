# Cloudflare Pages Deployment

This app is a static Vite site with Cloudflare Pages Functions for shared API caching.

## Build Settings

- Build command: `npm run build`
- Build output directory: `dist`
- Functions directory: `functions`
- Node version: Cloudflare default is fine for the current build.

## KV Cache

Create a KV namespace and bind it to Pages Functions:

- Binding name: `FIRESKY_CACHE`
- Preview binding: use the same namespace or a separate preview namespace.

The Functions cache Open-Meteo responses by rounded coordinates:

- `/api/forecast`: fresh for 90 minutes, stale fallback for 6 hours.
- `/api/grid`: fresh for 90 minutes, stale fallback for 6 hours.
- `/api/geocode`: fresh for 7 days, stale fallback for 30 days.

The browser still keeps a small `localStorage` cache, but the KV cache is the shared cache that lets multiple users reuse the same upstream responses.

## Notes

- Free Open-Meteo use is non-commercial and limited by their terms.
- OpenFreeMap tiles are still loaded directly by MapLibre.
- If you deploy somewhere other than Cloudflare Pages, the `/api/*` routes need an equivalent serverless or edge function layer.
