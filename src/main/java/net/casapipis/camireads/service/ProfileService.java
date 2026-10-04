package net.casapipis.camireads.service;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.domain.model.AppProfile;
import net.casapipis.camireads.domain.repository.AppProfileRepository;
import net.casapipis.camireads.dto.ProfileResponse;
import net.casapipis.camireads.dto.UpdateProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Perfil de la usuaria (GET / PUT /profile).
 *
 * TODA validacion que la base impone con un CHECK se hace ACA primero, para
 * contestar 400 con un mensaje en castellano en vez de dejar que reviente la
 * constraint y salga un 500 con el SQL crudo. Mismo criterio que ReviewService.
 *
 * Los tres CHECK de app_profile (ver 004_perfil.sql):
 *   - app_profile_photo_size  -> length(photo_b64) <= 400000
 *   - app_profile_photo_mime  -> si hay foto, hay mime
 *   - app_profile_single_row  -> id = 1 (lo garantiza PROFILE_ID, no hay endpoint
 *                                que permita elegir otro id)
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** La fila es unica y ya existe (la inserto la migracion 004). */
    private static final short PROFILE_ID = 1;

    /**
     * Techo de la base (app_profile_photo_size), replicado aca.
     *
     * NO ES DECORATIVO: ~400.000 caracteres base64 son ~300 KB de imagen.
     * Una foto de iPhone sin procesar son ~5,5 MB en base64 y quintuplicaria
     * el pg_dump (hoy 1,2 MB), volviendo lento cada backup. El front
     * redimensiona a 400x400 (~50 KB) antes de subir; esto es la red de
     * seguridad por si ese redimensionado falla.
     */
    private static final int MAX_PHOTO_B64_CHARS = 400_000;

    /** app_profile.display_name es VARCHAR(80). */
    private static final int MAX_DISPLAY_NAME = 80;

    /**
     * Solo imagenes, y solo formatos que un <img> puede mostrar.
     * Todos entran en el VARCHAR(40) de photo_mime.
     *
     * heic/heif estan porque son el formato nativo del iPhone: si algun dia el
     * front deja de convertir a jpeg, la foto igual entra. Safari los muestra.
     */
    private static final Set<String> ALLOWED_MIMES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "image/gif", "image/heic", "image/heif", "image/avif"
    );

    private static final ZoneId AR = ZoneId.of("America/Argentina/Buenos_Aires");

    private final AppProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public ProfileResponse getProfile() {
        return ProfileResponse.from(profileRepository.findById(PROFILE_ID).orElse(null));
    }

    /**
     * PUT /profile — actualiza nombre y/o foto.
     *
     * Semantica por campo (ver UpdateProfileRequest):
     *   ausente        -> no se toca
     *   null explicito -> se borra (nombre a NULL, foto + mime a NULL)
     *   valor          -> se valida y se reemplaza
     *
     * Un PUT de solo {"displayName": "Cami"} NO borra la foto, y uno de solo
     * {"photoDataUrl": null} NO borra el nombre.
     */
    @Transactional
    public ProfileResponse updateProfile(UpdateProfileRequest request) {

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el cuerpo del pedido");
        }

        // orElseGet: si alguien borro la fila a mano, el PUT la vuelve a crear
        // en vez de tirar 500. Igual la migracion 004 ya la dejo insertada.
        AppProfile profile = profileRepository.findById(PROFILE_ID)
                .orElseGet(() -> {
                    AppProfile fresh = new AppProfile();
                    fresh.setId(PROFILE_ID);
                    return fresh;
                });

        if (request.isDisplayNamePresent()) {
            profile.setDisplayName(cleanDisplayName(request.getDisplayName()));
        }

        if (request.isPhotoDataUrlPresent()) {
            String raw = request.getPhotoDataUrl();
            if (raw == null || raw.isBlank()) {
                // Borrado explicito. Los dos campos juntos, o revienta
                // app_profile_photo_mime.
                profile.setPhotoB64(null);
                profile.setPhotoMime(null);
            } else {
                Photo photo = parsePhoto(raw, request.getPhotoMime());
                profile.setPhotoB64(photo.b64());
                profile.setPhotoMime(photo.mime());
            }
        }

        profile.setUpdatedAt(OffsetDateTime.now(AR));

        return ProfileResponse.from(profileRepository.save(profile));
    }

    // ---------------------------------------------------------------
    // Validaciones
    // ---------------------------------------------------------------

    /**
     * Nombre visible: trim, y vacio equivale a "sin nombre" (NULL).
     *
     * DECISION: mandar "" o "   " BORRA el nombre en vez de guardar una cadena
     * vacia. Para el front "sin nombre" y "nombre vacio" se ven igual, y
     * teniendo un unico valor posible para ese estado no hay que preguntarse
     * despues cual de los dos guardo la base.
     */
    private String cleanDisplayName(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_DISPLAY_NAME) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre no puede superar los " + MAX_DISPLAY_NAME
                            + " caracteres (llegaron " + value.length() + ")");
        }
        return value;
    }

    /** Base64 pelado + mime, ya validados y listos para guardar. */
    private record Photo(String b64, String mime) {}

    /**
     * Acepta la foto como data URL ("data:image/jpeg;base64,...") o como base64
     * pelado acompaniado de photoMime.
     *
     * ORDEN DE LOS CHEQUEOS (importante en un i3 de 2 nucleos): primero el
     * formato y el mime, despues el LARGO, y reciencito al final el decode.
     * Asi una foto de 5,5 MB se rechaza midiendo un String, sin gastar CPU ni
     * ~4 MB de heap decodificandola para tirarla.
     */
    private Photo parsePhoto(String raw, String explicitMime) {

        String value = raw.trim();
        String mime;
        String payload;

        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw badPhoto("La foto tiene que venir como data URL completa, "
                        + "con la forma data:image/jpeg;base64,<contenido>");
            }
            String header = value.substring(5, comma);      // ej: "image/jpeg;base64"
            int semi = header.indexOf(';');
            if (semi < 0 || !header.substring(semi).toLowerCase(Locale.ROOT).contains("base64")) {
                throw badPhoto("La foto tiene que estar en base64 "
                        + "(data:image/jpeg;base64,<contenido>)");
            }
            mime = header.substring(0, semi).trim().toLowerCase(Locale.ROOT);
            if (mime.isEmpty()) {
                throw badPhoto("La foto no dice de qué tipo es. "
                        + "Mandala como data:image/jpeg;base64,<contenido>");
            }
            payload = value.substring(comma + 1);
        } else {
            // Base64 pelado: el tipo tiene que venir aparte, si o si.
            if (explicitMime == null || explicitMime.isBlank()) {
                throw badPhoto("La foto no dice de qué tipo es. Mandala como data URL "
                        + "(data:image/jpeg;base64,<contenido>) o agregá el campo photoMime");
            }
            mime = explicitMime.trim().toLowerCase(Locale.ROOT);
            payload = value;
        }

        if (!ALLOWED_MIMES.contains(mime)) {
            throw badPhoto("El tipo de archivo '" + mime + "' no está permitido. "
                    + "La foto tiene que ser una imagen: "
                    + String.join(", ", sortedAllowedMimes()));
        }

        // Los data URL largos suelen venir cortados en lineas; sacamos todo el
        // blanco antes de medir y de guardar (la base cuenta caracteres reales).
        payload = payload.replaceAll("\\s", "");

        if (payload.isEmpty()) {
            throw badPhoto("La foto llegó vacía");
        }

        if (payload.length() > MAX_PHOTO_B64_CHARS) {
            throw badPhoto("La foto es demasiado grande: " + payload.length()
                    + " caracteres en base64 y el máximo es " + MAX_PHOTO_B64_CHARS
                    + " (unos 300 KB de imagen). Redimensionala a 400x400 antes de subirla.");
        }

        try {
            Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw badPhoto("La foto no es base64 válido");
        }

        return new Photo(payload, mime);
    }

    private List<String> sortedAllowedMimes() {
        return ALLOWED_MIMES.stream().sorted().toList();
    }

    private ResponseStatusException badPhoto(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
