package net.casapipis.camireads.dto;

/**
 * Body del PUT /profile. Los dos campos son OPCIONALES e independientes.
 *
 * ⚠️ POR QUE ESTO NO ES UN record NI USA LOMBOK:
 * hay que distinguir tres casos por campo, no dos:
 *
 *   {}                          -> campo AUSENTE  -> no se toca
 *   {"photoDataUrl": null}      -> null EXPLICITO -> se BORRA la foto
 *   {"photoDataUrl": "data:..."}-> valor          -> se reemplaza la foto
 *
 * Con un DTO comun "ausente" y "null explicito" llegan los dos como null y no
 * habria forma de borrar la foto (que es un requisito). El truco es que Jackson
 * solo llama al setter cuando la clave ESTA en el JSON: el flag *Present se
 * prende ahi y distingue los dos casos, sin sumar la dependencia
 * jackson-databind-nullable al pom.
 */
public class UpdateProfileRequest {

    private String displayName;
    private boolean displayNamePresent;

    /**
     * La foto como data URL: "data:image/jpeg;base64,...".
     * Tambien se acepta base64 pelado, pero entonces hay que mandar photoMime
     * (una foto sin tipo declarado es un 400: la base lo exige con
     * app_profile_photo_mime).
     */
    private String photoDataUrl;
    private boolean photoDataUrlPresent;

    /** Solo se usa cuando photoDataUrl viene como base64 pelado, sin prefijo. */
    private String photoMime;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.displayNamePresent = true;
    }

    public boolean isDisplayNamePresent() {
        return displayNamePresent;
    }

    public String getPhotoDataUrl() {
        return photoDataUrl;
    }

    public void setPhotoDataUrl(String photoDataUrl) {
        this.photoDataUrl = photoDataUrl;
        this.photoDataUrlPresent = true;
    }

    public boolean isPhotoDataUrlPresent() {
        return photoDataUrlPresent;
    }

    public String getPhotoMime() {
        return photoMime;
    }

    public void setPhotoMime(String photoMime) {
        this.photoMime = photoMime;
    }
}
