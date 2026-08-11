//src/config/backend.js

export const BACKEND_API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8080";
export const AUTH_REDIRECT_URI = import.meta.env.VITE_AUTH_REDIRECT_URI ?? "http://localhost:5173";
export const LOGIN_SERVER_URI = import.meta.env.VITE_LOGIN_SERVER_URI ?? "http://localhost:8081/realms/master/";
