import axios, { AxiosInstance } from "axios";
import { User } from "../values/User";

class DemoBackend {
	_me: Promise<User> | null;
	constructor(private _axios: AxiosInstance) {}

	public get = async <T>(endpoint: string): Promise<T> => {
		return (await this._axios.get<T>(endpoint)).data;
	};

	public put = async <T>(endpoint: string): Promise<T> => {
		return (await this._axios.put<T>(endpoint)).data;
	};

	/** @returns The user currently logged in to the app */
	public get me(): Promise<User> {
		if(this._me==null)
			this._me=this.get<User>("/my-data/me");
		return this._me;
	}
};

export default DemoBackend;
