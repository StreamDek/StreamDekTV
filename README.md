# StreamDek Android TV

This folder contains the TV-first StreamDek client scaffold.

The app is intended to share the same account, preferences, watchlist, continue-watching, and playback ecosystem as mobile and web while feeling native to Android TV / Google TV.

## What is included

- TV app architecture and product plan
- Shared API and auth layer
- TV focusable UI primitives
- Starter screens for auth, home, search, detail, player, and settings

## Key backend routes used now

- `POST /auth/login`
- `POST /auth/register`
- `GET /account/bootstrap`
- `GET /account/preferences`
- `PATCH /account/preferences`
- `PATCH /account/profile`
- `POST /account/pairing-codes`
- `GET /tmdb/*`
- `GET /trakt/*`
- `GET /addons/*`
- `POST /stream/*`

See `ARCHITECTURE.md` for the proposed TV sync model and screen-by-screen design.

## Local setup

Set `STREAMDEK_API_URL` in the repository root `.env` for the Android TV emulator:

- `STREAMDEK_API_URL=http://10.0.2.2:3000`

The TV app now uses the backend JWT auth flow and reads only `STREAMDEK_API_URL` from the repository root `.env`.
