import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig(() => {
	return {
		plugins: [
			react(),
		],
		define: {
			CESIUM_BASE_URL: JSON.stringify("/cesium"),
		},
		optimizeDeps: {
			include: ["cesium"],
		},
		server: {
			host: true,
			port: 5173,
			watch: {
				usePolling: true,
			},
		},
	};
});
