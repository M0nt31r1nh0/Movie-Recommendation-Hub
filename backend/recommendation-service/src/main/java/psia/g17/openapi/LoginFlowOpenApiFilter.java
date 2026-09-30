package psia.g17.openapi;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

import java.util.Map;

/**
 * Describes the API's login as Keycloak's browser login only (authorization code with PKCE).
 * The generated OpenID Connect scheme points Swagger UI at Keycloak's discovery document, which
 * lists every grant type Keycloak supports, so the Authorize dialog also offered password,
 * client-credentials and implicit logins that the realm's clients reject.
 */
@OpenApiFilter(OpenApiFilter.RunStage.RUNTIME_STARTUP)
public class LoginFlowOpenApiFilter implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        // Unset in tests, which don't use Keycloak; the generated scheme is left as it is there.
        ConfigProvider.getConfig().getOptionalValue("quarkus.oidc.auth-server-url", String.class).ifPresent(realm ->
                openAPI.getComponents().addSecurityScheme("keycloak", OASFactory.createSecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Log in (or register) with Keycloak")
                        .flows(OASFactory.createOAuthFlows()
                                .authorizationCode(OASFactory.createOAuthFlow()
                                        .authorizationUrl(realm + "/protocol/openid-connect/auth")
                                        .tokenUrl(realm + "/protocol/openid-connect/token")
                                        .refreshUrl(realm + "/protocol/openid-connect/token")
                                        .scopes(Map.of("openid", "Required to log in"))))));
    }
}
