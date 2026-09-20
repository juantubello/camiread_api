package net.casapipis.camireads.web.controller;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.NewTagRequest;
import net.casapipis.camireads.dto.TagResponse;
import net.casapipis.camireads.dto.TagWithCountResponse;
import net.casapipis.camireads.dto.UpdateTagRequest;
import net.casapipis.camireads.service.TagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS
        }
)
@RestController
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /** GET /tags -> [{id, name, slug, color, bookCount}], ordenado por nombre. */
    @GetMapping
    public List<TagWithCountResponse> list() {
        return tagService.listWithBookCount();
    }

    /**
     * POST /tags {name, color?}
     * 201 si lo creo, 200 si ya existia un tag con ese slug (idempotente).
     */
    @PostMapping
    public ResponseEntity<TagResponse> create(@RequestBody NewTagRequest request) {
        TagService.CreateResult result = tagService.createOrGet(request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(TagResponse.from(result.tag()));
    }

    /** PUT /tags/{id} {name?, color?} -> 409 si el slug nuevo ya es de otro tag. */
    @PutMapping("/{id}")
    public TagResponse update(@PathVariable Long id, @RequestBody UpdateTagRequest request) {
        return TagResponse.from(tagService.update(id, request));
    }

    /** DELETE /tags/{id} -> borra el tag y sus filas de book_tags. Nunca libros ni resenias. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
