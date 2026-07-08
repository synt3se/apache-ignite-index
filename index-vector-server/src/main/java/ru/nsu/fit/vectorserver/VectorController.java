package ru.nsu.fit.vectorserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.nsu.fit.vector.common.dto.SearchRequest;


@RestController
@RequestMapping("/vectors")
public class VectorController {
    private final VectorService vectorService;

    public VectorController(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    @PostMapping
    public ResponseEntity<?> saveVector(@RequestBody ru.nsu.fit.vector.common.dto.AddRequest request) {
        return vectorService.add(request);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getVector(@PathVariable Long id) {
        return vectorService.get(id);
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest request){
        return vectorService.search(request);
    }
}