package org.qommons.misc.springdemo.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
			// Public endpoints that don't require authentication
			// .requestMatchers("/api/public/**").permitAll()
			// Specific URL authorization rules matching Keycloak roles
			// .requestMatchers("/api/admin/**").hasRole("ADMIN")//
			// .requestMatchers("/api/user/**").hasAnyRole("USER", "ADMIN")
			// All other endpoints require authentication
			.anyRequest().authenticated())//
		.cors(cors -> cors.configurationSource(corsConfigurationSource()))//
		.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))//
		.oauth2Client(oauth2 -> {});

		return http.build();
	}

	@Bean
	GrantedAuthoritiesMapper userAuthoritiesMapper() {
		return (authorities) -> {
			Set<SimpleGrantedAuthority> mappedAuthorities = new HashSet<>();

			authorities.forEach(authority -> {
				if (authority instanceof OidcUserAuthority oidcAuthority) {
					// "groups" is the provider-agnostic standard cross-platform claim string
					var groups = oidcAuthority.getIdToken().getClaimAsStringList("groups");

					if (groups instanceof Collection<?> groupList)
						groupList.forEach(group -> mappedAuthorities.add(new SimpleGrantedAuthority(group.toString().toUpperCase())));
				}
			});

			// Retain standard scopes (like openid, profile) along with the new roles
			Set<org.springframework.security.core.GrantedAuthority> finalAuthorities = new HashSet<>(authorities);
			finalAuthorities.addAll(mappedAuthorities);
			return finalAuthorities;
		};
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();

		// Explicitly allow your local React dev server port
		configuration.setAllowedOrigins(List.of("http://localhost:5173"));

		// Allow standard HTTP methods
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));

		// Allow the authorization headers your React OIDC client attaches
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

		// Allow credentials (cookies/auth headers) to pass through cleanly
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration); // Apply rules to all API endpoints
		return source;
	}

	// Handles the automatic token requests/refreshes for the service account
	@Bean
	AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository clientRegistrationRepository,
		OAuth2AuthorizedClientService authorizedClientService) {

		OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials()
			.build();

		AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
			clientRegistrationRepository, authorizedClientService);
		authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

		return authorizedClientManager;
	}
}
