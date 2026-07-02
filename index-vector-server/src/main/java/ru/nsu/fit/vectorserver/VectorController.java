package ru.nsu.fit.vectorserver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
        try {
            VectorObject savedObject = vectorService.save(request);
            VectorResponse response = new VectorResponse(request.id(), savedObject.getVector(), savedObject.getUrl());
            return ResponseEntity.ok(response);
        }catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getVector(@PathVariable Long id) {
        try {
            VectorObject object = vectorService.get(id);
            if (object == null) {
                return ResponseEntity.notFound().build();
            }
            VectorResponse response = new VectorResponse(id, object.getVector(), object.getUrl());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest request){
        try {
            List<VectorResponse> result = vectorService.search(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}