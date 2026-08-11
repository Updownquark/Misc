import {useEffect} from "react";
import { useAuth } from 'react-oidc-context';
import DemoHomePage from "./components/DemoHomePage";
import { createTheme, ThemeProvider } from "@mui/material/styles";
import { authContextHolder, lifeCycle } from "./services/services";

function App() {
	const auth = useAuth();
	// Inject the hook's runtime functions into our global bridge object
	authContextHolder.getToken = () => auth.user?.access_token;
	authContextHolder.triggerLogin = () => auth.signinRedirect();
	
	useEffect(() => {
		if(auth.isAuthenticated){
			lifeCycle.start();
		}
		return () => lifeCycle.stop();
	}, [auth.isAuthenticated]);

	if (auth.isLoading)
		return <div>Loading authorization...</div>
	else if (auth.error) {
		return (
			<div style={{ color: "red", padding: "20px" }}>
				<h3>Authentication Error:</h3>
				<p>{auth.error.message}</p>
				<button onClick={() => window.location.href = "/"}>Reset Page</button>
			</div>
		);
	} else if (!auth.isAuthenticated) {
		auth.signinRedirect();
		return <div>Redirecting to login...</div>
	}

	const appTheme = createTheme({
		palette: {
			primary: {
				main: "#008000",
				contrastText: "#ffffff",
			},
			secondary: {
				main: "#000080",
				contrastText: "#ffffff",
			}
		}
	});

	return (
		<ThemeProvider theme={appTheme}>
			<DemoHomePage />
		</ThemeProvider>
	);
}

export default App;
