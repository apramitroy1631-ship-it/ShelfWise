package com.shelfwise.core.auth;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import reactor.core.publisher.Mono;

/**
 * Turns a valid access token into the signed-in user, re-reading the user on every request.
 * So a deactivated user is cut off on their next request (D20), and a role change applies at once.
 */
public class CurrentUserConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final UserAccountRepository users;

    public CurrentUserConverter(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        return users.findById(jwt.getSubject())
                .filter(UserAccount::active)
                .switchIfEmpty(Mono.error(new DisabledException("User is unknown or inactive")))
                .map(user -> new AuthenticatedUser(user, jwt));
    }

    /** The authentication object for a signed-in user; the principal is the {@link UserAccount}. */
    static final class AuthenticatedUser extends AbstractAuthenticationToken {

        private final UserAccount user;
        private final Jwt jwt;

        AuthenticatedUser(UserAccount user, Jwt jwt) {
            super(List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
            this.user = user;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return jwt;
        }

        @Override
        public Object getPrincipal() {
            return user;
        }
    }
}
