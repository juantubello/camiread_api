package net.casapipis.camireads.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Perfil de la usuaria: una UNICA fila (id = 1), creada por la migracion
 * 004_perfil.sql. La base lo impone con CHECK (id = 1), asi que aca no hay
 * @GeneratedValue: el id se asigna a mano y siempre vale 1.
 *
 * Por eso el servicio nunca distingue "crear" de "modificar": la fila ya esta.
 *
 * Las tres CHECK de la base (tamanio de la foto, mime obligatorio si hay foto,
 * fila unica) se validan ANTES en ProfileService, para contestar 400 con
 * mensaje en castellano en vez de dejar que reviente la constraint con un 500
 * y el SQL crudo de Postgres en pantalla. Es el mismo criterio que ya usa
 * ReviewService con title/author/rating.
 */
@Entity
@Table(name = "app_profile")
public class AppProfile {

    /** Siempre 1. La base lo fuerza con app_profile_single_row. */
    @Id
    private Short id;

    @Column(name = "display_name")
    private String displayName;

    /** Base64 PELADO (sin el prefijo "data:<mime>;base64,"). */
    @Column(name = "photo_b64", columnDefinition = "text")
    private String photoB64;

    @Column(name = "photo_mime")
    private String photoMime;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Short getId() {
        return id;
    }

    public void setId(Short id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPhotoB64() {
        return photoB64;
    }

    public void setPhotoB64(String photoB64) {
        this.photoB64 = photoB64;
    }

    public String getPhotoMime() {
        return photoMime;
    }

    public void setPhotoMime(String photoMime) {
        this.photoMime = photoMime;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
