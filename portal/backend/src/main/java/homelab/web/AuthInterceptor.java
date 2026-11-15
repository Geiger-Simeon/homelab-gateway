package homelab.web;

import homelab.auth.CurrentUser;
import homelab.auth.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards everything under /api, so a new endpoint can't forget the check.
 * Handlers get the user via {@code @RequestAttribute(AuthInterceptor.CURRENT_USER)}.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER = "homelab.currentUser";

    private final CurrentUserResolver users;

    public AuthInterceptor(final CurrentUserResolver users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
                             final Object handler) throws Exception {
        // without headers the request didn't come through oauth2-proxy
        final CurrentUser user = users.resolve(request).orElse(null);
        if (user == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        request.setAttribute(CURRENT_USER, user);
        return true;
    }
}
