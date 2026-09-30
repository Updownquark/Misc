import { backend, lifeCycle } from "./services";

class ClientDataService {
	private readonly _data: object;

	constructor() {
		this._data = {};
		lifeCycle.onInit(() => this.init());
	}

	private async init() {
		let dataStr = sessionStorage.getItem("VISTA_CLIENT_DATA");
		let data: object;
		if (dataStr) data = JSON.parse(dataStr) as object;
		else data = (await backend.get<object>("/my-data/client-data")).data; //Grab all client data
		for (const key in data) this._data[key] = data[key];
	}

	public get(path: string, defaultValue?: () => any): any {
		let data = this._data as any;
		const splitPath = path.split("/");
		for (let p = 0; p < splitPath.length; p++) {
			const key = splitPath[p];
			if (typeof data == "object") {
				data = data[key];
			} else if (!defaultValue) return null;
			else if (p == splitPath.length - 1) {
				const v = defaultValue();
				data[key] = v;
				return v;
			} else {
				const v = {};
				data[key] = v;
				data = v;
			}
		}
		return data;
	}

	/**
	 * Sets a configuration item to be remembered between sessions
	 *
	 * @param path The path to the value to save.
     *      Objects will be created along the path as necessary to store the value (unless value is null).
	 * @param value The value to set for the property.  If null, the value will be deleted.
	 * @param excludeServerProperties An optional set of property names to omit from the object-type value
     *      for storage on the server. These properties will be persisted between refreshes of the same browser tab,
     *      but will not be stored for re-use in other sessions.
	 * 		A special value of ["*"] will exclude all properties from the server, but still store the value in the session storage for this browser tab.
     *      This special case may be used with non-object values.
	 */
	public set(path: string, value: any, excludeServerProperties?: string[]) {
		let data = this._data;
		const splitPath = path.split("/");
		for (let p = 0; p < splitPath.length; p++) {
			const key = splitPath[p];
			if (p == splitPath.length - 1) {
				if (value == null || value == undefined) delete data[key];
				else data[key] = value;
			} else {
				if (data == null || data == undefined) return;
				let pData = data[key];
				if (typeof pData != "object") {
					pData = {};
					data[key] = pData;
				}
				data = pData as object;
			}
		}
		sessionStorage.setItem("VISTA_CLIENT_DATA", JSON.stringify(this._data));
        if(!excludeServerProperties || !(excludeServerProperties.length==1 && excludeServerProperties[0]=="*")){
            let serverValue = value;
            if (excludeServerProperties) {
                serverValue = { ...value };
                for (const prop of excludeServerProperties) delete serverValue[prop];
            }
            backend.put("/my-data/client-data/" + path, JSON.stringify(serverValue));
        }
	}

	/**
	 * Instead of replacing the entire object at the given path, this method only replaces properties defined in the provided value object
	 * 
	 * @param path The path to the value to save.
     *      Objects will be created along the path as necessary to store the value (unless value is null).
	 * @param value The object whose properties to replace in the selected client property
	 * @param excludeServerProperties An optional set of property names to omit from the object-type value
     *      for storage on the server. These properties will be persisted between refreshes of the same browser tab,
     *      but will not be stored for re-use in other sessions.
	 * 		A special value of ["*"] will exclude all properties from the server, but still store the value in the session storage for this browser tab.
     *      This special case may be used with non-object values.
	 */
	public setPartial(path: string, value: object, excludeServerProperties?: string []){
		let data = this._data;
		const splitPath = path.split("/");
		for (let p = 0; p < splitPath.length; p++) {
			const key = splitPath[p];
			if (p == splitPath.length - 1) {
				if(!data[key]){
					data[key]=value;
					data=data[key];
				}else{
					data=data[key];
					for(const prop in value)
						data[prop]=value[prop];
				}
			} else {
				if (value == null || value == undefined) return;
				let pData = data[key];
				if (typeof pData != "object") {
					pData = {};
					data[key] = pData;
				}
				data = pData as object;
			}
		}
		sessionStorage.setItem("VISTA_CLIENT_DATA", JSON.stringify(this._data));
        if(!excludeServerProperties || !(excludeServerProperties.length==1 && excludeServerProperties[0]=="*")){
            let serverValue = data as object;
			let callServer=true;
            if (excludeServerProperties) {
				//First, let's see if the caller excluded this entire operation from the server
				callServer=!Object.keys(value).every(s=>excludeServerProperties.indexOf(s)>=0);

				if(callServer){
					serverValue = { ...serverValue as object };
					for (const prop of excludeServerProperties) delete serverValue[prop];
				}
            }
			if(callServer)
	            backend.put("/my-data/client-data/" + path, JSON.stringify(serverValue));
        }
	}
}

export default ClientDataService;
