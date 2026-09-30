package psia.g17.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptRequest(@NotBlank @Size(max = 300) String prompt) {
}
