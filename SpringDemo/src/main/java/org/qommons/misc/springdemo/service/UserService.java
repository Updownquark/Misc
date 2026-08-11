package org.qommons.misc.springdemo.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.qommons.TimeUtils;
import org.qommons.Transaction;
import org.qommons.misc.springdemo.entities.DemoUser;
import org.qommons.misc.springdemo.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class UserService {
	private UserRepository theUserRepo;
	private final ConcurrentHashMap<String, Boolean> theUserLock;

	public UserService(UserRepository userRepo) {
		theUserRepo = userRepo;
		theUserLock = new ConcurrentHashMap<>();
	}

	public static AuthenticatedUser authenticated(Jwt jwt) {
		String userId = jwt.getSubject();
		String userName = jwt.getClaimAsString("preferred_username");
		if (userName == null)
			userName = userId;
		return new AuthenticatedUser(userId, userName, jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));

	}

	public static Set<String> getRoles(Authentication auth) {
		return auth.getAuthorities().stream()//
			.map(GrantedAuthority::getAuthority)//
			.filter(role -> role.startsWith(AvailableSharingService.ROLE_PREFIX + AvailableSharingService.APP_ROLE_PREFIX))//
			.map(role -> role.substring(AvailableSharingService.ROLE_PREFIX.length()))//
			.collect(Collectors.toSet())
			;
	}

	public DemoUser getOrCreateUser(AuthenticatedUser authUser) {
		DemoUser user;
		Optional<DemoUser> foundUser = theUserRepo.findById(authUser.id());
		if (foundUser.isEmpty()) {
			user = new DemoUser(authUser.id(), authUser.userName(), authUser.fullName());
			theUserRepo.save(user);
		} else {
			user = foundUser.get();
			Instant now = Instant.now();
			if (!user.getUserName().equals(authUser.userName()) //
				|| !Objects.equals(user.getFullName(), authUser.fullName())//
				|| TimeUtils.between(user.getLastActive(), now).getSeconds() > 60) {
				user.setUserName(authUser.userName());
				user.setFullName(authUser.fullName());
				user.setLastActive(now);
				theUserRepo.save(user);
			}
		}
		return user;
	}

	public Transaction lockUser(String userId) throws InternalResourceUnavailableException {
		int tries;
		for (tries = 0; tries < 10 && null != theUserLock.putIfAbsent(userId, Boolean.TRUE); tries++)
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {}
		if (tries == 10)
			throw new InternalResourceUnavailableException("Could not obtain lock on user to guarantee scenario name uniqueness");
		return () -> theUserLock.remove(userId);
	}

	public void saveUser(DemoUser user) {
		theUserRepo.save(user);
	}
}
