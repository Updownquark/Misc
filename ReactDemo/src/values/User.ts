
export interface User{
	readonly id: string;
	readonly userName: string;
	readonly fullName: string;
}

export function usersEqual(user1: User, user2: User | null): boolean {
	if(user2==null)
		return false;
	return user1.id==user2.id //
		&& user1.userName==user2.userName //
		&& user1.fullName==user2.fullName;
}
