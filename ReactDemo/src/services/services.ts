import axios from "axios";
import { LifeCycleService } from "./LifeCycleService";
import DemoBackend from "./DemoBackend";
import ScenarioService from "./ScenarioService"
import DemoTabService from "./DemoTabService"
import { BACKEND_API_URL } from "../config/backend";
import DemoMapService from "./DemoMapService";

// Google Gemini helped me with this authorization code
const api = axios.create({
	baseURL: BACKEND_API_URL
});

// 2. Define a type for a function that can fetch the token dynamically
export type TokenProvider = () => string | undefined;

// 3. We create a placeholder reference that we will populate inside App.tsx
export const authContextHolder: { getToken?: TokenProvider; triggerLogin?: () => void } = {};

// Attach bearer token to outgoing requests
api.interceptors.request.use((config) => {
	if (authContextHolder.getToken) {
		const token = authContextHolder.getToken();
		if (token) {
			config.headers.Authorization = `Bearer ${token}`;
		}
	}
	return config;
});

//Redirect to the login page if an API call fails due to the token expiring
api.interceptors.response.use(
	(response) => response,
	(error) => {
		if (error.response && error.response.status == 401) {
			if (authContextHolder.triggerLogin) {
				authContextHolder.triggerLogin();
			}
		}
		return Promise.reject(error);
	}
);

export const lifeCycle = new LifeCycleService();
export const backend = new DemoBackend(api);
export const scenarioService = new ScenarioService(lifeCycle, backend);
export const demoMap = new DemoMapService();
export const tabService = new DemoTabService();
