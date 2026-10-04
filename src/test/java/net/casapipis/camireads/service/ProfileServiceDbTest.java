package net.casapipis.camireads.service;

import net.casapipis.camireads.dto.ProfileResponse;
import net.casapipis.camireads.dto.UpdateProfileRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PUT/GET /profile contra la base REAL (la copia sandbox en 127.0.0.1:5434).
 *
 * Toca la UNICA fila de app_profile, asi que la guarda antes de cada test y la
 * restaura despues: la corrida deja el perfil exactamente como lo encontro.
 * No toca books, reviews ni review_quotes (ver elPerfilNoTocaLosLibros).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class ProfileServiceDbTest {

    /** PNG de 1x1 valido, chiquito, para no ensuciar la base de prueba. */
    private static final String PNG_1PX =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    /** El mismo techo que el CHECK app_profile_photo_size de la migracion 004. */
    private static final int MAX_B64 = 400_000;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> snapshot;

    @BeforeEach
    void guardarPerfil() {
        snapshot = jdbc.queryForMap(
                "SELECT display_name, photo_b64, photo_mime, updated_at FROM app_profile WHERE id = 1");
    }

    @AfterEach
    void restaurarPerfil() {
        jdbc.update("""
                        UPDATE app_profile
                           SET display_name = ?, photo_b64 = ?, photo_mime = ?, updated_at = ?
                         WHERE id = 1""",
                snapshot.get("display_name"), snapshot.get("photo_b64"),
                snapshot.get("photo_mime"), snapshot.get("updated_at"));
    }

    private UpdateProfileRequest conFoto(String dataUrl) {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setPhotoDataUrl(dataUrl);
        return r;
    }

    private ResponseStatusException esperar400(UpdateProfileRequest request) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> profileService.updateProfile(request));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertNotNull(e.getReason(), "el 400 tiene que explicar que paso, no venir pelado");
        return e;
    }

    // -----------------------------------------------------------------
    // Camino feliz
    // -----------------------------------------------------------------

    @Test
    @DisplayName("SUBIR FOTO: se guarda el base64 pelado + el mime, y vuelve como data URL para el <img>")
    void subirFotoDevuelveDataUrl() {
        ProfileResponse res = profileService.updateProfile(conFoto("data:image/png;base64," + PNG_1PX));

        assertEquals("data:image/png;base64," + PNG_1PX, res.photoDataUrl());

        // En la base va el base64 PELADO y el mime aparte (asi es el esquema):
        // el prefijo "data:" lo rearma el backend al devolverlo.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT photo_b64, photo_mime FROM app_profile WHERE id = 1");
        assertEquals(PNG_1PX, row.get("photo_b64"));
        assertEquals("image/png", row.get("photo_mime"));
    }

    @Test
    @DisplayName("BORRAR FOTO: photoDataUrl null explicito limpia foto Y mime (si no, revienta el CHECK)")
    void borrarFotoConNullExplicito() {
        profileService.updateProfile(conFoto("data:image/png;base64," + PNG_1PX));

        ProfileResponse res = profileService.updateProfile(conFoto(null));

        assertNull(res.photoDataUrl());

        // Los dos campos juntos: app_profile_photo_mime exige que si no hay
        // foto tampoco haya mime.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT photo_b64, photo_mime FROM app_profile WHERE id = 1");
        assertNull(row.get("photo_b64"));
        assertNull(row.get("photo_mime"));
    }

    @Test
    @DisplayName("CAMPO AUSENTE = NO TOCAR: mandar solo el nombre no borra la foto, y viceversa")
    void camposAusentesNoPisanLoQueYaEstaba() {
        profileService.updateProfile(conFoto("data:image/png;base64," + PNG_1PX));

        // Solo el nombre: la foto tiene que sobrevivir.
        UpdateProfileRequest soloNombre = new UpdateProfileRequest();
        soloNombre.setDisplayName("Camila");
        ProfileResponse res = profileService.updateProfile(soloNombre);
        assertEquals("Camila", res.displayName());
        assertNotNull(res.photoDataUrl(), "un PUT con solo el nombre NO puede borrar la foto");

        // Solo la foto: el nombre tiene que sobrevivir.
        res = profileService.updateProfile(conFoto(null));
        assertEquals("Camila", res.displayName(), "un PUT con solo la foto NO puede borrar el nombre");
        assertNull(res.photoDataUrl());
    }

    @Test
    @DisplayName("BASE64 PELADO con photoMime tambien se acepta")
    void base64PeladoConMimeExplicito() {
        UpdateProfileRequest r = conFoto(PNG_1PX);
        r.setPhotoMime("image/png");

        assertEquals("data:image/png;base64," + PNG_1PX, profileService.updateProfile(r).photoDataUrl());
    }

    @Test
    @DisplayName("NOMBRE vacio se guarda como NULL (un unico valor para 'sin nombre')")
    void nombreVacioEsNull() {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setDisplayName("   ");
        assertNull(profileService.updateProfile(r).displayName());
    }

    // -----------------------------------------------------------------
    // Validaciones de la foto (todas 400, NUNCA 500 de la constraint)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FOTO DEMASIADO GRANDE: 400 con mensaje, no un 500 del CHECK de la base")
    void fotoDemasiadoGrandeDa400() {
        // Una foto de iPhone sin procesar son ~5,5 MB en base64. Con 400.001
        // caracteres ya alcanza para pasarse por uno del techo de la base.
        String gigante = "data:image/jpeg;base64," + "A".repeat(MAX_B64 + 1);

        String motivo = esperar400(conFoto(gigante)).getReason();
        assertTrue(motivo.contains(String.valueOf(MAX_B64)),
                "el mensaje tiene que decir cual es el limite");
        assertTrue(motivo.toLowerCase().contains("400x400"),
                "el mensaje tiene que decir que hay que redimensionar");

        // Y la foto que ya estaba no se toco.
        assertNull(jdbc.queryForMap("SELECT photo_b64 FROM app_profile WHERE id = 1").get("photo_b64"));
    }

    @Test
    @DisplayName("LIMITE EXACTO: 400.000 caracteres ENTRAN (el borde es el mismo que el de la base)")
    void elLimiteExactoEntra() {
        // Si el backend cortara en 399.999 estaria siendo mas estricto que la
        // base sin motivo; si cortara en 400.001 la constraint tiraria un 500.
        String justo = "data:image/jpeg;base64," + "A".repeat(MAX_B64);

        assertNotNull(profileService.updateProfile(conFoto(justo)).photoDataUrl());
        assertEquals(MAX_B64, jdbc.queryForObject(
                "SELECT length(photo_b64) FROM app_profile WHERE id = 1", Integer.class));
    }

    @Test
    @DisplayName("MIME INVALIDO: un PDF disfrazado de foto no entra")
    void mimeNoImagenDa400() {
        String motivo = esperar400(conFoto("data:application/pdf;base64,JVBERi0xLjQK")).getReason();
        assertTrue(motivo.contains("application/pdf"), "el mensaje tiene que decir que tipo llego");
    }

    @Test
    @DisplayName("FOTO SIN TIPO: base64 pelado sin photoMime da 400 (la base exige el mime)")
    void fotoSinMimeDa400() {
        esperar400(conFoto(PNG_1PX));
    }

    @Test
    @DisplayName("DATA URL MAL FORMADA: sin coma, o sin ;base64, da 400")
    void dataUrlMalFormadaDa400() {
        esperar400(conFoto("data:image/png;base64"));   // sin coma
        esperar400(conFoto("data:image/png,hola"));     // sin ;base64
        esperar400(conFoto("data:;base64,AAAA"));       // sin mime
    }

    @Test
    @DisplayName("BASE64 ROTO: 400 antes de intentar escribirlo")
    void base64RotoDa400() {
        esperar400(conFoto("data:image/png;base64,@@@esto-no-es-base64@@@"));
    }

    @Test
    @DisplayName("NOMBRE de mas de 80 caracteres: 400, no un 500 del VARCHAR(80)")
    void nombreDemasiadoLargoDa400() {
        UpdateProfileRequest r = new UpdateProfileRequest();
        r.setDisplayName("x".repeat(81));
        assertTrue(esperar400(r).getReason().contains("80"));
    }

    // -----------------------------------------------------------------
    // Lo que NO tiene que pasar
    // -----------------------------------------------------------------

    @Test
    @DisplayName("El perfil NO toca libros, resenias ni frases, y app_profile sigue teniendo UNA fila")
    void elPerfilNoTocaLosLibros() {
        long books = jdbc.queryForObject("SELECT count(*) FROM books", Long.class);
        long reviews = jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class);
        long quotes = jdbc.queryForObject("SELECT count(*) FROM review_quotes", Long.class);

        profileService.updateProfile(conFoto("data:image/png;base64," + PNG_1PX));
        UpdateProfileRequest nombre = new UpdateProfileRequest();
        nombre.setDisplayName("Camila");
        profileService.updateProfile(nombre);
        profileService.updateProfile(conFoto(null));

        assertEquals(books, jdbc.queryForObject("SELECT count(*) FROM books", Long.class));
        assertEquals(reviews, jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class));
        assertEquals(quotes, jdbc.queryForObject("SELECT count(*) FROM review_quotes", Long.class));

        // Nunca se inserta una segunda fila: el PUT siempre actualiza la id = 1.
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM app_profile", Long.class));
    }
}
