package ru.nsu.fit.vectorserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.nsu.fit.vectorserver.core.Index;
import ru.nsu.fit.vectorserver.dto.*;

import java.util.List;


@RestController
@RequestMapping("/vectors")
public class VectorController {
    private final VectorService vectorService;

    public VectorController(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    @PostMapping
    public ResponseEntity<?> saveVector(@RequestBody AddRequest request) {
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