import {User, usersEqual} from "./User";

export interface Scenario{
	readonly id: string;
	name: string;
	owner: User;
	canDelete: boolean;
}

export function scenariosEqual(scenario1: Scenario, scenario2: Scenario | null){
	if(scenario2==null)
		return false;
	return scenario1.id==scenario2.id //
		&& scenario1.name==scenario2.name //
		&& usersEqual(scenario1.owner, scenario2.owner)//
		&& scenario1.canDelete==scenario2.canDelete;
}
