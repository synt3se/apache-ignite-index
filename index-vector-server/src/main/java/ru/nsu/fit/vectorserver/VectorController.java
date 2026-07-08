package ru.nsu.fit.vectorserver;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.nsu.fit.vector.common.dto.LoadRequest;
import ru.nsu.fit.vector.common.dto.SaveRequest;
import ru.nsu.fit.vector.common.dto.SearchRequest;

import javax.validation.Valid;
import javax.validation.constraints.Positive;


@RestController
@RequestMapping("/vectors")
@Validated
public class VectorController {
    private final VectorService vectorService;

    public VectorController(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    @PostMapping
    public ResponseEntity<?> saveVector(@Valid @RequestBody ru.nsu.fit.vector.common.dto.AddRequest request) {
        return vectorService.add(request);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getVector(@Positive(message = "id must be greater than zero") @PathVariable Long id) {
        return vectorService.get(id);
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@Valid @RequestBody SearchRequest request){
        return vectorService.search(request);
    }

    @PostMapping("/save")
    public ResponseEntity<?> saveDatabase(@Valid @RequestBody SaveRequest request) {
        return vectorService.save(request);
    }
    @PostMapping("/load")
    public ResponseEntity<?> saveDatabase(@Valid @RequestBody LoadRequest request) {
        return vectorService.load(request);
    }
}