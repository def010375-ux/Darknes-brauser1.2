"use strict";

const express = require("express");
const helmet = require("helmet");
const cors = require("cors");
const rateLimit = require("express-rate-limit");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const { Pool } = require("pg");

const required = ["DATABASE_URL", "JWT_SECRET"];
for (const key of required) {
  if (!process.env[key]) {
    console.error(`Missing required environment variable: ${key}`);
    process.exit(1);
  }
}
if (process.env.JWT_SECRET.length < 32) {
  console.error("JWT_SECRET must contain at least 32 characters.");
  process.exit(1);
}

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.DATABASE_SSL === "false" ? false : { rejectUnauthorized: false },
  max: 10,
  connectionTimeoutMillis: 10000,
});
const app = express();
app.disable("x-powered-by");
app.use(helmet());
app.use(cors({
  origin: process.env.CORS_ORIGIN ? process.env.CORS_ORIGIN.split(",").map(v => v.trim()) : false,
  methods: ["GET", "POST"],
  allowedHeaders: ["Content-Type", "Authorization"],
}));
app.use(express.json({ limit: "10kb" }));

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 10,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "Слишком много попыток. Попробуйте позже." },
});

function createToken(user) {
  return jwt.sign(
    { sub: String(user.id), email: user.email, username: user.username },
    process.env.JWT_SECRET,
    { expiresIn: "7d", issuer: "darknes-id", audience: "darknes-apps" }
  );
}

function requireAuth(req, res, next) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) return res.status(401).json({ error: "Требуется вход в Darknes ID." });
  try {
    req.auth = jwt.verify(token, process.env.JWT_SECRET, {
      issuer: "darknes-id",
      audience: "darknes-apps",
    });
    next();
  } catch {
    return res.status(401).json({ error: "Сеанс истёк или токен недействителен." });
  }
}

async function initializeDatabase() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS darknes_users (
      id BIGSERIAL PRIMARY KEY,
      username VARCHAR(24) NOT NULL,
      email VARCHAR(254) NOT NULL,
      password_hash TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);
  await pool.query("CREATE UNIQUE INDEX IF NOT EXISTS darknes_users_email_lower_uq ON darknes_users (LOWER(email));");
  await pool.query("CREATE UNIQUE INDEX IF NOT EXISTS darknes_users_username_lower_uq ON darknes_users (LOWER(username));");
}

app.get("/health", (_req, res) => res.json({ status: "ok", service: "Darknes ID API", version: "1.0.0" }));

app.post("/v1/auth/register", authLimiter, async (req, res) => {
  try {
    const username = typeof req.body.username === "string" ? req.body.username.trim() : "";
    const email = typeof req.body.email === "string" ? req.body.email.trim().toLowerCase() : "";
    const password = typeof req.body.password === "string" ? req.body.password : "";

    if (!/^[a-zA-Z0-9_]{3,24}$/.test(username))
      return res.status(400).json({ error: "Имя пользователя: 3–24 символа, латинские буквы, цифры и _." });
    if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
      return res.status(400).json({ error: "Укажите корректную электронную почту." });
    if (password.length < 10 || password.length > 72)
      return res.status(400).json({ error: "Пароль должен содержать от 10 до 72 символов." });

    const passwordHash = await bcrypt.hash(password, 12);
    const result = await pool.query(
      "INSERT INTO darknes_users (username, email, password_hash) VALUES ($1, $2, $3) RETURNING id, username, email, created_at",
      [username, email, passwordHash]
    );
    const user = result.rows[0];
    return res.status(201).json({ user, accessToken: createToken(user), tokenType: "Bearer", expiresIn: 604800 });
  } catch (error) {
    if (error.code === "23505")
      return res.status(409).json({ error: "Такая почта или имя пользователя уже зарегистрированы." });
    console.error("Registration error:", error.message);
    return res.status(500).json({ error: "Не удалось создать аккаунт." });
  }
});

app.post("/v1/auth/login", authLimiter, async (req, res) => {
  try {
    const login = typeof req.body.login === "string" ? req.body.login.trim() : "";
    const password = typeof req.body.password === "string" ? req.body.password : "";
    if (!login || !password) return res.status(400).json({ error: "Введите почту или имя пользователя и пароль." });

    const result = await pool.query(
      "SELECT id, username, email, password_hash, created_at FROM darknes_users WHERE LOWER(email) = LOWER($1) OR LOWER(username) = LOWER($1) LIMIT 1",
      [login]
    );
    const user = result.rows[0];
    const valid = user ? await bcrypt.compare(password, user.password_hash) : false;
    if (!valid) return res.status(401).json({ error: "Неверная почта, имя пользователя или пароль." });

    delete user.password_hash;
    return res.json({ user, accessToken: createToken(user), tokenType: "Bearer", expiresIn: 604800 });
  } catch (error) {
    console.error("Login error:", error.message);
    return res.status(500).json({ error: "Не удалось выполнить вход." });
  }
});

app.get("/v1/auth/me", requireAuth, async (req, res) => {
  try {
    const result = await pool.query(
      "SELECT id, username, email, created_at FROM darknes_users WHERE id = $1",
      [req.auth.sub]
    );
    if (!result.rowCount) return res.status(404).json({ error: "Аккаунт не найден." });
    return res.json({ user: result.rows[0] });
  } catch (error) {
    console.error("Profile error:", error.message);
    return res.status(500).json({ error: "Не удалось загрузить профиль." });
  }
});

app.use((_req, res) => res.status(404).json({ error: "Маршрут не найден." }));

const port = Number(process.env.PORT || 10000);
initializeDatabase()
  .then(() => {
    app.listen(port, "0.0.0.0", () => console.log(`Darknes ID API listening on ${port}`));
  })
  .catch(error => {
    console.error("Database initialization failed:", error.message);
    process.exit(1);
  });

process.on("SIGTERM", async () => {
  await pool.end();
  process.exit(0);
});
