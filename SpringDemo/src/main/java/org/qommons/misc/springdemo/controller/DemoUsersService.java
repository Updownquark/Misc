package org.qommons.misc.springdemo.controller;

import java.util.List;
import java.util.Set;

import org.qommons.misc.springdemo.service.AuthenticatedUser;
import org.qommons.misc.springdemo.service.AvailableSharingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoUsersService {
	private AvailableSharingService theSharingService;

	public DemoUsersService(AvailableSharingService sharingService) {
		theSharingService = sharingService;
	}

	@GetMapping("/users")
	public List<ApiUser> getAllUsers() {
		return theSharingService.getAvailableVistaUsers().stream()//
			.map(DemoUsersService::toApi)//
			.toList();
	}

	@GetMapping("/roles")
	public Set<String> getAllRoles() {
		return theSharingService.getAvailableVistaRoles();
	}

	private static ApiUser toApi(AuthenticatedUser user) {
		return new ApiUser(user.id(), user.userName(), user.fullName(), null);
	}
}
