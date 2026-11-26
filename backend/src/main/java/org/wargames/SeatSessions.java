package org.wargames;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.ResponseCookie;

/**
 * Device-held seat credentials. Each seat (the host or one character) gets a random secret
 * that lives only in an HttpOnly cookie, so page scripts cannot read it. The cookie is
 * scoped to one game's API path, and the server keeps only a SHA-256 hash of the secret.
 *
 * Cookies are named per seat, so one browser can hold several seats (one per tab). The tab
 * says which seat it is acting as with the non-secret X-Game-Seat header. Requiring that
 * custom header also blocks cross-site form posts, on top of SameSite=Strict.
 */
final class SeatSessions {
    static final String SEAT_HEADER = "X-Game-Seat";
    static final String HOST_SEAT = "host";
    private static final String COOKIE_PREFIX = "wg_seat_";
    private static final Duration COOKIE_LIFETIME = Duration.ofDays(90);
    private static final Pattern SEAT_PATTERN = Pattern.compile("^[a-z]{1,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private SeatSessions() {}

    /** A new 256-bit secret, URL-safe so it fits in a cookie without escaping. */
    static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /** Constant-time comparison of a presented secret against a stored hash. */
    static boolean matches(String secret, String storedHash) {
        if (secret == null || secret.isBlank() || storedHash == null) return false;
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
            storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** The seat the request claims to act as, or null when it claims none. */
    static String requestedSeat(HttpServletRequest request) {
        String seat = request.getHeader(SEAT_HEADER);
        if (seat == null || seat.isBlank()) return null;
        return SEAT_PATTERN.matcher(seat).matches() ? seat : "";
    }

    static String cookieSecret(HttpServletRequest request, String seat) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ((COOKIE_PREFIX + seat).equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    static void issue(HttpServletRequest request, HttpServletResponse response, String gameCode,
                      String seat, String secret) {
        response.addHeader("Set-Cookie", cookie(request, gameCode, seat, secret, COOKIE_LIFETIME).toString());
    }

    static void clear(HttpServletRequest request, HttpServletResponse response, String gameCode, String seat) {
        response.addHeader("Set-Cookie", cookie(request, gameCode, seat, "", Duration.ZERO).toString());
    }

    private static ResponseCookie cookie(HttpServletRequest request, String gameCode, String seat,
                                         String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_PREFIX + seat, value)
            .httpOnly(true)
            // Secure only over HTTPS, so phones on the local network over plain HTTP still work.
            .secure(isHttps(request))
            .sameSite("Strict")
            .path("/api/games/" + gameCode)
            .maxAge(maxAge)
            .build();
    }

    private static boolean isHttps(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
