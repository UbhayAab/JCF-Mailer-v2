package com.jarurat.mailer.security;

import com.jarurat.mailer.models.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Authorities are the role's permissions, not the role name, so endpoints can
 * say hasAuthority('CAMPAIGNS_SEND') and stay correct when roles change.
 */
public class AppUserDetails implements UserDetails {

    private final User user;
    private final List<GrantedAuthority> authorities;

    public AppUserDetails(User user) {
        this.user = user;
        this.authorities = user.getRole().getPermissions().stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .toList();
    }

    public User getUser() { return user; }
    public Role getRole() { return user.getRole(); }
    public String getFullName() { return user.getFullName(); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getEmail(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return !user.isLocked(); }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isActive(); }
}
