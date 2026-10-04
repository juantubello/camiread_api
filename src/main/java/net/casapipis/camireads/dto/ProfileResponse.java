package net.casapipis.camireads.dto;

import net.casapipis.camireads.domain.model.AppProfile;

import java.time.OffsetDateTime;

/**
 * GET /profile -> {displayName, photoDataUrl, updatedAt}
 *
 * photoDataUrl viene ARMADO y listo para enchufar en un <img src="...">:
 * "data:image/jpeg;base64,/9j/4AAQ...". Si no hay foto cargada es null.
 *
 * La entidad guarda el base64 pelado y el mime por separado (asi lo pide el
 * esquema de la tabla); rearmar el data URL es trabajo del backend para que el
 * front no tenga que conocer ese detalle.
 */
public record ProfileResponse(
        String displayName,
        String photoDataUrl,
        OffsetDateTime updatedAt
) {

    public static ProfileResponse from(AppProfile profile) {
        if (profile == null) {
            return new ProfileResponse(null, null, null);
        }
        return new ProfileResponse(
                profile.getDisplayName(),
                toDataUrl(profile.getPhotoB64(), profile.getPhotoMime()),
                profile.getUpdatedAt()
        );
    }

    /**
     * La base garantiza con app_profile_photo_mime que foto y mime van juntos,
     * pero el null-check igual esta: si alguna vez se toca la tabla a mano y
     * queda una foto sin mime, preferimos devolver null antes que un data URL
     * roto que el <img> no pueda mostrar.
     */
    private static String toDataUrl(String b64, String mime) {
        if (b64 == null || b64.isBlank() || mime == null || mime.isBlank()) {
            return null;
        }
        return "data:" + mime + ";base64," + b64;
    }
}
