package psia.g17;

import jakarta.enterprise.context.RequestScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

/**
 * The logged-in user, from their Keycloak access token. Their profile is created (or their username
 * refreshed) the first time a request asks for it, so every registered user gets a profile as soon
 * as they use the app, without hooking into Keycloak's registration.
 */
@RequestScoped
public class CurrentUser {

    private static final String UPSERT_PROFILE = """
            INSERT INTO profiles.profile (profile_id, username) VALUES (:id, :username)
            ON CONFLICT (profile_id) DO UPDATE SET username = EXCLUDED.username
            WHERE profiles.profile.username IS DISTINCT FROM EXCLUDED.username""";

    private final JsonWebToken token;
    private final EntityManager em;
    private UUID profileId;

    public CurrentUser(JsonWebToken token, EntityManager em) {
        this.token = token;
        this.em = em;
    }

    /** The user's profile id (their Keycloak user id), making sure the profile exists. */
    @Transactional
    public UUID profileId() {
        if (profileId == null) {
            UUID id = UUID.fromString(token.getSubject());
            String username = token.<String>getClaim("preferred_username");
            em.createNativeQuery(UPSERT_PROFILE)
                    .setParameter("id", id)
                    .setParameter("username", username != null ? username : id.toString())
                    .executeUpdate();
            profileId = id;
        }
        return profileId;
    }
}
