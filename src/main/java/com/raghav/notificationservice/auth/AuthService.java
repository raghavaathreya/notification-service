package com.raghav.notificationservice.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;

    /**
     * Registers a new user.
     *
     * Password is BCrypt-hashed before storage — the plain text
     * password is never persisted anywhere.
     *
     * @throws IllegalArgumentException if email is already registered
     */
    @Transactional
    public AuthDtos.RegisterResponse register(AuthDtos.RegisterRequest request) {
        log.info("[AUTH] Register attempt for email={}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            log.warn("[AUTH] Registration failed — email already exists: {}", request.getEmail());
            throw new IllegalArgumentException("Email already registered: " + request.getEmail());
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("ROLE_USER")
                .enabled(true)
                .build();

        user = userRepository.save(user);
        log.info("[AUTH] Registered new user id={}, email={}", user.getId(), user.getEmail());

        return AuthDtos.RegisterResponse.builder()
                .userId(user.getId().toString())
                .email(user.getEmail())
                .message("Registration successful. Use /auth/login to get your token.")
                .build();
    }

    /**
     * Authenticates user credentials and returns a signed JWT.
     *
     * Spring's AuthenticationManager handles the credential check —
     * it calls UserDetailsService + PasswordEncoder internally.
     * If credentials are wrong it throws BadCredentialsException.
     *
     * @throws BadCredentialsException if email/password don't match
     */
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        log.info("[AUTH] Login attempt for email={}", request.getEmail());

        // Throws BadCredentialsException / DisabledException automatically
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // Credentials are valid — load fresh UserDetails and generate token
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getEmail());
        String token = jwtService.generateToken(userDetails);

        log.info("[AUTH] Login successful for email={}", request.getEmail());

        return AuthDtos.AuthResponse.builder()
                .token(token)
                .email(request.getEmail())
                .role(userDetails.getAuthorities().stream()
                        .findFirst()
                        .map(a -> a.getAuthority())
                        .orElse("ROLE_USER"))
                .expiresInMs(jwtService.getExpirationMs())
                .build();
    }
}
