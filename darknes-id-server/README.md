# Darknes ID Server

Independent authentication API for the Darknes ecosystem.

## Endpoints

- `GET /health` — service health check
- `POST /v1/auth/register` — create account
- `POST /v1/auth/login` — sign in with email or username
- `GET /v1/auth/me` — current account (Bearer token)

## Required environment variables

- `DATABASE_URL` — PostgreSQL connection string
- `JWT_SECRET` — random secret, at least 32 characters
- `DATABASE_SSL` — optional; set to `false` only for a local database
- `CORS_ORIGIN` — optional comma-separated list of allowed web origins

## Deploy on Render

Create a PostgreSQL database and a Node web service from this repository. Set the web service Root Directory to `darknes-id-server`, Build Command to `npm install`, Start Command to `npm start`. Add `DATABASE_URL` using the database's **internal** connection string, and generate a long random `JWT_SECRET`. Keep secrets in Render environment variables, never in Git.

After deployment, check `https://YOUR-SERVICE.onrender.com/health`.

## Important

This first API version supports account creation, login, and profile lookup. Email verification, password reset, refresh-token/session management, and production monitoring must be added before treating the service as a fully production-ready identity platform.
