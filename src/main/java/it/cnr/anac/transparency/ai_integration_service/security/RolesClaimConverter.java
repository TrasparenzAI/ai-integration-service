/*
 * Copyright (C) 2025 Consiglio Nazionale delle Ricerche
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package it.cnr.anac.transparency.ai_integration_service.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public class RolesClaimConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private static final String CLAIM_REALM_ACCESS = "realm_access";
  private static final String CLAIM_RESOURCE_ACCESS = "resource_access";
  private static final String CLAIM_ROLES = "roles";
  private final JwtGrantedAuthoritiesConverter wrappedConverter;

  public RolesClaimConverter(JwtGrantedAuthoritiesConverter conv) {
    wrappedConverter = conv;
  }

  @Override
  public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
    var grantedAuthorities = new ArrayList<>(wrappedConverter.convert(jwt));
    Map<String, Collection<String>> realmAccess = jwt.getClaim(CLAIM_REALM_ACCESS);

    if (realmAccess != null && !realmAccess.isEmpty()) {
      Collection<String> roles = realmAccess.get(CLAIM_ROLES);
      if (roles != null && !roles.isEmpty()) {
        Collection<GrantedAuthority> realmRoles = roles.stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toList());
        grantedAuthorities.addAll(realmRoles);
      }
    }
    Map<String, Map<String, Collection<String>>> resourceAccess = jwt.getClaim(CLAIM_RESOURCE_ACCESS);

    if (resourceAccess != null && !resourceAccess.isEmpty()) {
      resourceAccess.forEach((resource, resourceClaims) -> resourceClaims.get(CLAIM_ROLES).forEach(
          role -> grantedAuthorities.add(new SimpleGrantedAuthority(resource + "_" + role))
          ));
    }
    return new JwtAuthenticationToken(jwt, grantedAuthorities);
  }
}
