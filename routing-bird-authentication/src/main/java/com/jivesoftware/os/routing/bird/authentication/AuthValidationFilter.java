package com.jivesoftware.os.routing.bird.authentication;

import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.health.checkers.PercentileHealthChecker;
import com.jivesoftware.os.routing.bird.shared.AuthEvaluator;
import com.jivesoftware.os.routing.bird.shared.AuthEvaluator.AuthStatus;

import static com.jivesoftware.os.routing.bird.server.session.RouteSessionValidator.SESSION_REDIR;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Response;
import jersey.repackaged.com.google.common.collect.Lists;

/**
 *
 */
public class AuthValidationFilter implements ContainerRequestFilter {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final PercentileHealthChecker successRate;

    private final List<PathedAuthEvaluator> evaluators = Lists.newArrayList();
    private boolean dryRun = false;
    private String instanceKey = "";

    public AuthValidationFilter(PercentileHealthChecker successRate) {
        this.successRate = successRate;
    }

    public AuthValidationFilter addEvaluator(AuthEvaluator authEvaluator, String... paths) {
        evaluators.add(new PathedAuthEvaluator(authEvaluator, paths));
        return this;
    }

    public AuthValidationFilter dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public AuthValidationFilter setInstanceKey(String instanceKey) {
        this.instanceKey = instanceKey;
        return this;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = '/' + requestContext.getUriInfo().getPath();
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        List<AuthEvaluator> matches = Lists.newArrayList();
        List<AuthEvaluator> misses = Lists.newArrayList();
        AuthStatus failedAuthStatus = AuthStatus.not_handled;
        for (PathedAuthEvaluator pathedAuthEvaluator : evaluators) {
            if (pathedAuthEvaluator.matches(path)) {
                matches.add(pathedAuthEvaluator.evaluator);
                AuthStatus authStatus = pathedAuthEvaluator.evaluator.authorize(requestContext);
                if (authStatus == AuthStatus.authorized) {
                    if (successRate != null) {
                        successRate.check(1d, "", "");
                    }
                    LOG.inc("auth>authorized");
                    LOG.inc("auth>authorized>" + pathedAuthEvaluator.evaluator.name());
                    return;
                } else if (authStatus != AuthStatus.not_handled) {
                    failedAuthStatus = authStatus;
                }
            } else {
                misses.add(pathedAuthEvaluator.evaluator);
            }
        }
        if (successRate != null) {
            successRate.check(0d, "", "");
        }
        if (dryRun) {
            LOG.inc("auth>unauthorize>dryRun");
            LOG.warn("Dry run validation failed, matches:{} misses:{}", matches, misses);
        } else {
            LOG.inc("auth>unauthorized");
            LOG.warn("Authorization error: {}", failedAuthStatus.description());
            try {
                Cookie redirCookie = requestContext.getCookies().get(SESSION_REDIR);
                if (redirCookie != null && !instanceKey.isEmpty()) {
                    URI redirUri = new URI(redirCookie.getValue() +
                        "/ui/deployable/redirect/" + instanceKey + "?portName=main&path=" + path);
                    requestContext.abortWith(Response.temporaryRedirect(redirUri).build());
                    return;
                }
            } catch(URISyntaxException e) {
                LOG.warn("Redirect error occurred.", e);
            }

            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity(failedAuthStatus.description()).build());
        }
    }

    static class PathedAuthEvaluator {

        private final AuthEvaluator evaluator;
        private final String[] paths;
        private final boolean[] wildcards;

        /**
         * /a matches /a
         * /a/* matches /a, /a/, /a/b, but NOT /ab
         */
        PathedAuthEvaluator(AuthEvaluator evaluator, String... paths) {
            this.evaluator = evaluator;
            this.paths = new String[paths.length];
            this.wildcards = new boolean[paths.length];

            for (int i = 0; i < paths.length; i++) {
                boolean wildcard = paths[i].endsWith("/*");
                if (!wildcard && paths[i].contains("*")) {
                    throw new IllegalArgumentException("Wildcard paths must end in /*");
                }
                this.paths[i] = wildcard ? paths[i].substring(0, paths[i].length() - 2) : paths[i];
                this.wildcards[i] = wildcard;
            }
        }

        boolean matches(String path) {
            for (int i = 0; i < paths.length; i++) {
                if (wildcards[i]) {
                    if (path.startsWith(paths[i]) && (path.length() == paths[i].length() || path.charAt(paths[i].length()) == '/')) {
                        return true;
                    }
                } else if (path.equals(paths[i])) {
                    return true;
                }
            }
            return false;
        }
    }

}
