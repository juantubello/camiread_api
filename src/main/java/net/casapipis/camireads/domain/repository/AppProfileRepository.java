package net.casapipis.camireads.domain.repository;

import net.casapipis.camireads.domain.model.AppProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de la fila unica de perfil (id = 1).
 * No hace falta ningun metodo propio: findById(1) y save() alcanzan.
 */
public interface AppProfileRepository extends JpaRepository<AppProfile, Short> {
}
