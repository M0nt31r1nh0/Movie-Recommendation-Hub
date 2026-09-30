package psia.g17.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * An error answer.
 *
 * @param requestId the request it's about, e.g. the one still being prepared when asking again
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorView(String error, UUID requestId) {

    public ErrorView(String error) {
        this(error, null);
    }
}
