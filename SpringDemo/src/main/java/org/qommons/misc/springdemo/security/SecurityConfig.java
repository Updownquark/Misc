package org.qommons.misc.springdemo.security;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
			.requestMatchers("/docs", "/docs/**").permitAll()//
			.requestMatchers("/docs-ui").permitAll()//
			.requestMatchers("/swagger-ui", "/swagger-ui/**").permitAll()//
			.requestMatchers("/error").permitAll()//
			// Specific URL authorization rules matching Keycloak roles
			// .requestMatchers("/api/admin/**").hasRole("ADMIN")//
			// .requestMatchers("/api/user/**").hasAnyRole("USER", "ADMIN")
			// All other endpoints require authentication
			.anyRequest().authenticated())//
		.cors(cors -> cors.configurationSource(corsConfigurationSource()))//
		.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))//
		.oauth2Client(oauth2 -> {});

		return http.build();
	}

	private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			Set<GrantedAuthority> mappedAuthorities = new HashSet<>();

			// 1. Extract default authorities (like SCOPE_openid, SCOPE_profile)
			JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();
			mappedAuthorities.addAll(defaultConverter.convert(jwt));

			// 2. Extract your custom "groups" claim
			List<String> groups = jwt.getClaimAsStringList("groups");
			if (groups != null) {
				groups.forEach(group -> mappedAuthorities.add(new SimpleGrantedAuthority(group.toUpperCase())));
			}

			return mappedAuthorities;
		});

		return converter;
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
