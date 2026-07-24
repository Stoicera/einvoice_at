package com.stoicera.einvoice.app.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for minting a new API key: a human label so a tenant can tell their keys apart
 * ("ci-pipeline", "erp-connector"). Validated at the boundary.
 */
public record CreateApiKeyRequest(@NotBlank @Size(max = 255) String name) {}
