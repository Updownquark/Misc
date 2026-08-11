import "cesium/Build/Cesium/Widgets/widgets.css";
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from "react-oidc-context";
import './index.css'
import App from './App.tsx'
import { LOGIN_SERVER_URI, AUTH_REDIRECT_URI } from './config/backend.ts';

const oidcConfig = {
	authority: LOGIN_SERVER_URI,
	client_id: "react-demo",
	redirect_uri: AUTH_REDIRECT_URI,
	response_type: "code", // Secures the app using PKCE authorization flows
	scope: "openid profile email",
	// Add this property to cleanly strip the URL code fragment after handling it:
	onSigninCallback: () => {
		window.history.replaceState({}, document.title, window.location.pathname);
	}
};


createRoot(document.getElementById('root')).render(
	<StrictMode>
		<AuthProvider {...oidcConfig}>
			<App />
		</AuthProvider>
	</StrictMode>,
)
