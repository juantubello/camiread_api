package net.casapipis.camireads.web.controller;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.ProfileResponse;
import net.casapipis.camireads.dto.UpdateProfileRequest;
import net.casapipis.camireads.service.ProfileService;
import org.springframework.web.bind.annotation.*;

/**
 * Perfil de la usuaria. Fila unica: no hay id en la ruta ni POST de alta,
 * el PUT siempre actualiza la misma fila (ver ProfileService).
 *
 * El @CrossOrigin es el mismo que ya usan ReviewController y TagController.
 */
@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.PUT,
                RequestMethod.OPTIONS
        }
)
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /** GET /profile -> {displayName, photoDataUrl, updatedAt}. */
    @GetMapping
    public ProfileResponse get() {
        return profileService.getProfile();
    }

    /**
     * PUT /profile {displayName?, photoDataUrl?, photoMime?}
     *
     * Devuelve el perfil ya actualizado, asi el front no necesita un GET extra
     * para refrescar la pantalla.
     */
    @PutMapping
    public ProfileResponse update(@RequestBody(required = false) UpdateProfileRequest request) {
        return profileService.updateProfile(request);
    }
}
