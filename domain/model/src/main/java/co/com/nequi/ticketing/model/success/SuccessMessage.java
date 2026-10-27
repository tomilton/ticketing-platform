package co.com.nequi.ticketing.model.success;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Catalog of success messages returned in the response metadata.
 */
@Getter
@RequiredArgsConstructor
public enum SuccessMessage {
    SUCCESSFUL("TKS0001", "Successful"),
    CREATED("TKS0002", "Created");

    private final String code;
    private final String message;

    @Override
    public String toString() {
        return code + " - " + message;
    }
}
