package org.wargames;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class MapEditorWriteProtection implements HandlerInterceptor, WebMvcConfigurer {
    private static final Set<String> EDITABLE_RESOURCES = Set.of(
        "campaigns", "places", "routes", "events", "armies", "terrain-regions");
    private final MapEditorAccess access;

    public MapEditorWriteProtection(MapEditorAccess access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!isWriteMethod(request.getMethod()) || !isEditableResource(request.getRequestURI())) return true;
        if (!access.isConfigured()) {
            return reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Map editor access is not configured.");
        }
        if (!access.isAuthorized(request.getHeader("X-Map-Editor-Token"))) {
            return reject(response, HttpServletResponse.SC_FORBIDDEN,
                "A valid map editor token is required.");
        }
        return true;
    }

    private static boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method)
            || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static boolean isEditableResource(String requestUri) {
        if (!requestUri.startsWith("/api/")) return false;
        String path = requestUri.substring("/api/".length());
        int separator = path.indexOf('/');
        String resource = separator < 0 ? path : path.substring(0, separator);
        return EDITABLE_RESOURCES.contains(resource);
    }

    private static boolean reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}
