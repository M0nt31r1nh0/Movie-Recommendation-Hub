package psia.g17.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import psia.g17.metrics.ProfileStats;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static psia.g17.TestUsers.BOB;
import static psia.g17.TestUsers.CAROL;

@QuarkusTest
class ProfileMetricsTest {

    @Inject
    ProfileStats stats;

    @Test
    @TestSecurity(user = "carol")
    @OidcSecurity(claims = {@Claim(key = "sub", value = CAROL), @Claim(key = "preferred_username", value = "carol")})
    void countsWhatUsersDoAndWhatIsStored() {
        given().when().put("/profiles/me/following/" + BOB).then().statusCode(204);
        stats.count();
        String whileFollowing = metrics();
        given().when().delete("/profiles/me/following/" + BOB).then().statusCode(204);
        given().when().delete("/profiles/me/following/" + BOB).then().statusCode(204); // nothing to remove: not counted

        String metrics = metrics();
        assertThat(metrics, containsString("profiles_actions_total{action=\"followed\"} 1.0"));
        assertThat(metrics, containsString("profiles_actions_total{action=\"unfollowed\"} 1.0"));
        assertThat(whileFollowing, matchesPattern("(?s).*\nprofiles_stored\\{what=\"follows\"\\} 1\\.0\n.*"));
        assertThat(whileFollowing, matchesPattern("(?s).*\nprofiles_stored\\{what=\"profiles\"\\} [1-9][0-9]*\\.0\n.*"));
    }

    private static String metrics() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }
}
